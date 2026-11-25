#!/usr/bin/env python3
"""Train a PaPaGei heart-rate head on the rules engine's confident windows, and test it
against the EmbracePlus on a recording it never saw.

Why: the first head (hr_ring_embraceplus) was trained on 134 windows from three resting
recordings whose reference spanned only about 55-67 bpm. It learnt to answer about 62
whatever the pulse did (61-63 bpm across a 37-minute recording). The EmbracePlus has not
yet recorded anything wider, but the rule-based Live Analysis Engine has: windows it rated
"good" or "fair" reach 44-106 bpm across the ring recordings, including exercise. Those
windows are used as labels here; the head can then be no more accurate than the rules on
such windows, but it learns the range, and it gives a reading where the rules withhold one.

Data comes from a running instance's database (deployment/local by default) through
`docker compose exec`, embeddings from its PaPaGei worker container, so nothing leaves the
machine. Output: per-minute scores on the held-out recording for the old and new heads,
and, with --save, the new head in ml-inference-server/heads/.

    python scripts/train_head_from_rules.py --test-session 66 \
        --reference tmp/embrace_1103/pulse-rate.csv --save hr_ring_rules_v2
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import statistics as st
import subprocess
import sys
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
HEADS = os.path.join(ROOT, 'ml-inference-server', 'heads')
WINDOW_MS = 10_000
STEP_MS = 5_000


def compose(args_env: str, compose_file: str) -> list[str]:
    return ['docker', 'compose', '--env-file', args_env, '-f', compose_file]


def sql(base: list[str], query: str) -> list[list[str]]:
    cmd = base + ['exec', '-T', 'mysql', 'sh', '-c',
                  'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def ring_sessions(base):
    rows = sql(base, "SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id "
                     "WHERE d.device_type='OpenRing' ORDER BY s.id;")
    return [int(r[0]) for r in rows]


RESET_DROP = 100   # an index drop larger than this (4 s at 25 Hz) is a counter reset, not reordering


def sample_order(rows, reset_drop=RESET_DROP):
    """Put (arrival ms, sample index, value) rows, given in database (insertion) order, into sample order.

    Fix (25 Nov 2026): rows used to be kept in insertion order, but in the October and early-November
    recordings (sessions 55-68) about 21% of consecutive rows were stored out of sample order within a
    batch, so windows had shuffled samples (outputs/hr_benchmark/tables.md, "Stored row order"). The
    index cannot order a whole recording because it resets, so the rows are split at resets (a drop of
    more than reset_drop), each segment is sorted by index, and an exact repeat of a sample (a
    redelivered batch: same index within a segment) is dropped. Segments stay in arrival order.
    Returns a list of (t, i, value, segment)."""
    out, seg, start = [], 0, 0
    rows = list(rows)
    for k in range(1, len(rows) + 1):
        if k == len(rows) or rows[k][1] < rows[k - 1][1] - reset_drop:
            seen = set()
            for t, i, v in sorted(rows[start:k], key=lambda r: r[1]):   # stable: first arrival wins
                if i in seen:
                    continue
                seen.add(i)
                out.append((t, i, v, seg))
            seg += 1
            start = k
    return out


def green_series(base, session_id):
    """(backend arrival ms, sample index, green, segment) for a recording, in sample order (see sample_order)."""
    rows = sql(base, f"SELECT backend_received_timestamp_ms, sample_index, "
                     f"JSON_EXTRACT(channel_values, '$.green') FROM stream_samples "
                     f"WHERE session_id={session_id} AND stream_name='ppg' ORDER BY id;")
    clean = [(int(t), int(i), float(g)) for t, i, g in rows
             if t != 'NULL' and i != 'NULL' and g not in ('NULL', 'null')]
    return sample_order(clean)


def window_ending(series, end_ms, n, span_ms):
    """The last n values (sample order) of samples that arrived in (end_ms - span_ms, end_ms], or None unless they
    are n consecutive indices of one segment. series: rows from green_series (sample order)."""
    sel = [r for r in series if end_ms - span_ms < r[0] <= end_ms]
    if len(sel) < n:
        return None
    sel = sel[-n:]
    segment = (lambda q: q[3] if len(q) > 3 else 0)
    if sel[-1][1] - sel[0][1] != n - 1 or segment(sel[0]) != segment(sel[-1]):
        return None
    return [r[2] for r in sel]


def to_model_input(values, invert=True):
    """Ring PPG counts as PaPaGei input. Fix (25 Nov 2026): the ring reports detected light, which falls when
    blood volume rises, so its pulse is upside down relative to the photoplethysmogram PaPaGei was trained on
    (rise fraction 0.54-0.56 at rest vs 0.40 for PPG-DaLiA BVP; outputs/hr_benchmark/results.md). Negating it
    makes the beat rise fast and fall slowly, as PaPaGei expects."""
    x = np.asarray(values, dtype=float)
    return (-x if invert else x).tolist()


def rule_windows(base, session_id):
    rows = sql(base, f"SELECT window_end_ms, hr_bpm, quality FROM derived_windows "
                     f"WHERE session_id={session_id} AND method_id='live_ppg' AND hr_bpm IS NOT NULL "
                     f"AND quality IN ('good','fair');")
    return [(int(e), float(h), q) for e, h, q in rows]


# Time-stretch factors: k x 10 s of pulse squeezed into a 10 s window has k times the
# heart rate. The ring recordings are mostly at rest, so without this the head never
# sees enough different rates to learn them and answers about the same number always.
STRETCHES = (0.67, 0.8, 0.9, 1.0, 1.11, 1.25, 1.5)   # symmetric on a log scale


def stretched(series, end_ms):
    """(factor, 250 samples) for each stretch of the pulse ending at end_ms, or [] (375 consecutive samples needed)."""
    values = window_ending(series, end_ms, 375, 15_000 + 2_000)
    if values is None:
        return []
    values = np.asarray(values, dtype=float)
    out = []
    for k in STRETCHES:
        n = int(round(250 * k))
        seg = values[-n:]
        out.append((k, np.interp(np.linspace(0, n - 1, 250), np.arange(n), seg).tolist()))
    return out


def cut(series, end_ms):
    """The last 250 consecutive samples (sample order) that arrived by end_ms, or None."""
    return window_ending(series, end_ms, 250, WINDOW_MS + 2_000)


EMBED_SCRIPT = r'''
import json, sys
sys.path.insert(0, '/app')
import app as worker
worker.load_model()
client = worker.app.test_client()
for line in sys.stdin:
    item = json.loads(line)
    r = client.post('/infer', json={'ppg_data': item['x'], 'sample_rate': 25, 'window_duration_s': 10})
    body = r.get_json()
    print(json.dumps({'k': item['k'], 'e': body.get('embeddings')}), flush=True)
'''


def embed(base, windows):
    """PaPaGei embeddings for {key: samples}, computed in the worker container."""
    payload = ''.join(json.dumps({'k': k, 'x': x}) + '\n' for k, x in windows.items())
    cmd = base + ['exec', '-T', 'papagei', 'python', '-c', EMBED_SCRIPT]
    out = subprocess.run(cmd, input=payload, capture_output=True, text=True, check=True).stdout
    result = {}
    for line in out.splitlines():
        try:
            item = json.loads(line)
        except ValueError:
            continue
        if item.get('e'):
            result[item['k']] = np.asarray(item['e'], dtype=np.float64)
    return result


def ridge(X, y, alpha):
    mean, std = X.mean(0), X.std(0) + 1e-9
    Z = (X - mean) / std
    ym = y.mean()
    w = np.linalg.solve(Z.T @ Z + alpha * np.eye(Z.shape[1]), Z.T @ (y - ym))
    # Fold the standardisation into raw-embedding weights, the form heads are stored in.
    weights = w / std
    bias = ym - mean @ weights
    return weights, bias


def reference_minutes(path, start_ms, end_ms):
    out = {}
    with open(path) as f:
        for row in csv.DictReader(f):
            if not row['pulse_rate_bpm']:
                continue
            t = int(row['timestamp_unix'])
            if start_ms <= t <= end_ms:
                out[t // 60000] = float(row['pulse_rate_bpm'])
    return out


def score(per_window, ref):
    """Per-minute median of estimates (minutes with at least three) against the reference."""
    by_minute = defaultdict(list)
    for t, v in per_window:
        by_minute[t // 60000].append(v)
    pairs = [(st.median(v), ref[m]) for m, v in by_minute.items() if len(v) >= 3 and m in ref]
    if not pairs:
        return None
    est = np.array([p[0] for p in pairs]); r = np.array([p[1] for p in pairs])
    err = est - r
    corr = float(np.corrcoef(est, r)[0, 1]) if est.std() > 0 and r.std() > 0 else float('nan')
    return {'minutes': len(pairs), 'mae': float(np.abs(err).mean()), 'bias': float(err.mean()),
            'sd_estimate': float(est.std()), 'sd_reference': float(r.std()),
            'range': (float(est.min()), float(est.max())), 'correlation': corr,
            'best_constant_mae': float(np.abs(r - np.median(r)).mean())}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--test-session', type=int, required=True, help='recording held out and scored against the reference')
    ap.add_argument('--reference', required=True, help='EmbracePlus pulse-rate.csv covering the test recording')
    ap.add_argument('--old-head', default='hr_ring_embraceplus')
    ap.add_argument('--save', help='head id to write into ml-inference-server/heads/')
    ap.add_argument('--no-invert', dest='invert', action='store_false',
                    help='feed raw ring counts (the pre-25-Nov behaviour) instead of the inverted pulse')
    args = ap.parse_args()
    base = compose(args.env, args.compose)

    train_x, train_y, groups = {}, {}, {}
    for sid in ring_sessions(base):
        if sid == args.test_session:
            continue
        labels = rule_windows(base, sid)
        if not labels:
            continue
        series = green_series(base, sid)
        for end, hr, _ in labels:
            for k, x in stretched(series, end):
                key = f'{sid}:{end}:{k}'
                train_x[key], train_y[key], groups[key] = to_model_input(x, args.invert), hr * k, sid
    print(f'training windows: {len(train_x)} from {len(set(groups.values()))} recordings, '
          f'labels {min(train_y.values()):.0f}-{max(train_y.values()):.0f} bpm', flush=True)

    test_series = green_series(base, args.test_session)
    t0, t1 = min(r[0] for r in test_series), max(r[0] for r in test_series)
    test_x = {}
    for end in range(t0 + WINDOW_MS, t1, STEP_MS):
        x = cut(test_series, end)
        if x:
            test_x[f'test:{end}'] = to_model_input(x, args.invert)
    print(f'test windows: {len(test_x)} (recording {args.test_session})', flush=True)

    emb = embed(base, {**train_x, **test_x})
    keys = [k for k in train_x if k in emb]
    X = np.stack([emb[k] for k in keys]); y = np.array([train_y[k] for k in keys])
    g = np.array([groups[k] for k in keys])

    # Ridge strength by leave-one-recording-out on the rule labels.
    best = None
    for alpha in (30, 100, 300, 1000, 3000):
        errs = []
        for held in np.unique(g):
            if (g == held).sum() < 5 or (g != held).sum() < 20:
                continue
            w, b = ridge(X[g != held], y[g != held], alpha)
            errs.extend(np.abs(X[g == held] @ w + b - y[g == held]))
        mae = float(np.mean(errs)) if errs else float('inf')
        print(f'  alpha {alpha:>5}: leave-one-recording-out MAE vs rules {mae:.2f} bpm')
        if best is None or mae < best[1]:
            best = (alpha, mae)
    alpha = best[0]
    # What the same check gives for a head that ignores the pulse: each held-out
    # recording predicted as the mean of the others.
    const = []
    for held in np.unique(g):
        if (g == held).sum() < 5 or (g != held).sum() < 20:
            continue
        const.extend(np.abs(y[g != held].mean() - y[g == held]))
    print(f'  (a constant guess scores {np.mean(const):.2f} bpm on the same check)')
    w, b = ridge(X, y, alpha)
    # Set the offset so the unstretched windows (real pulses at their real rate) are
    # right on average; the stretched copies are there to teach the slope.
    real = np.array([k.endswith(':1.0') for k in keys])
    if real.any():
        b += float(np.mean(y[real] - (X[real] @ w + b)))

    ref = reference_minutes(args.reference, t0 - 60000, t1 + 60000)
    tkeys = [k for k in test_x if k in emb]
    tX = np.stack([emb[k] for k in tkeys]); tt = [int(k.split(':')[1]) for k in tkeys]
    new = score(list(zip(tt, tX @ w + b)), ref)
    old_w = np.load(os.path.join(HEADS, f'{args.old_head}.npz'))
    # Heads saved before 25 Nov were trained on raw (not inverted) counts; their score here is only fair with --no-invert.
    old = score(list(zip(tt, (tX @ old_w['weights'] + old_w['bias']).reshape(-1))), ref) if not args.invert else None
    rules = score([(int(e), h) for e, h, _ in rule_windows(base, args.test_session)], ref)
    print(f'\nHeld-out recording {args.test_session} vs EmbracePlus, per minute:')
    for name, s in (('old head', old), ('new head', new), ('rules (good/fair)', rules)):
        if s:
            print(f"  {name:<18} minutes {s['minutes']:>3}  MAE {s['mae']:.2f}  bias {s['bias']:+.2f}  "
                  f"range {s['range'][0]:.0f}-{s['range'][1]:.0f}  SD {s['sd_estimate']:.2f} "
                  f"(ref {s['sd_reference']:.2f})  r {s['correlation']:.2f}")
    if new:
        print(f"  best constant MAE {new['best_constant_mae']:.2f}")

    if args.save:
        np.savez(os.path.join(HEADS, f'{args.save}.npz'), weights=w.reshape(-1, 1).astype(np.float32),
                 bias=np.array([b], dtype=np.float32))
        manifest = {
            'head_id': args.save,
            'head_version': '2026-11-11',
            'display_name': 'Heart rate (PaPaGei-S + ridge, rule-labelled, time-stretched)',
            'base_model_id': 'papagei_s',
            'input_dimension': 512,
            'output_type': 'regression',
            'unit': 'bpm',
            'weights_file': f'{args.save}.npz',
            'task_description': 'Heart rate from a 10 s ring PPG window, trained on windows the Live '
                                'Analysis Engine rated good or fair (its heart rate as the label), each also '
                                'time-stretched x0.7-x1.5 with the label scaled to match.',
            'training': {'recordings': sorted({int(s) for s in g}), 'labelled_windows': int(len(y)),
                         'label_range_bpm': [float(y.min()), float(y.max())], 'ridge_alpha': alpha,
                         'labels': 'live_ppg heart rate, quality good or fair',
                         'augmentation': {'time_stretch': list(STRETCHES)}},
            'validation': {'method': f'held-out recording {args.test_session} vs EmbracePlus, per-minute median',
                           **({k: v for k, v in new.items() if k != 'range'} if new else {})},
            'validation_status': 'pilot: labels from the rules engine, one held-out resting recording',
        }
        with open(os.path.join(HEADS, f'{args.save}.json'), 'w', encoding='utf8') as f:
            json.dump(manifest, f, indent=2)
        print(f'\nsaved head {args.save}')


if __name__ == '__main__':
    sys.exit(main())
