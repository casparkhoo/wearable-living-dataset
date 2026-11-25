#!/usr/bin/env python3
"""Run PaPaGei over stored ring recordings after collection, and score a heart-rate head.

The ring's green PPG is cut into 10 s windows (250 samples at the ring's 25 Hz, 5 s step),
prepared with the same derived-input pipeline as the inference worker (filter, resample to
125 Hz, normalise) and embedded with PaPaGei-S. A ridge-regression head maps the 512-d
embedding to heart rate. It is trained on windows labelled with the EmbracePlus per-minute
pulse rate and scored leave-one-recording-out, next to the live algorithm on the same minutes.

    python scripts/papagei_posthoc.py \
        --labelled tmp/replay55.tsv:tmp/trace55.txt tmp/replay60.tsv:tmp/trace60.txt ... \
        --embrace tmp/embrace/pulse-rate.csv \
        --apply tmp/replay66u.tsv:tmp/trace66u.txt \
        --out tmp/papagei

Run it with the inference worker's Python (ml-inference-server/venv-py311), which has torch.
"""
import argparse
import csv
import json
import os
import re
import statistics as st
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, '..', 'ml-inference-server')
sys.path.insert(0, WORKER)
sys.path.insert(0, os.path.join(WORKER, 'papagei'))

WIN, STEP, RATE = 250, 125, 25.0


def load_model():
    import torch
    from models.resnet import ResNet1DMoE
    model = ResNet1DMoE(in_channels=1, base_filters=32, kernel_size=3, stride=2, groups=1,
                        n_block=18, n_classes=512, n_experts=3)
    state = torch.load(os.path.join(WORKER, 'weights', 'papagei_s.pt'), map_location='cpu')
    state = state.get('state_dict', state) if isinstance(state, dict) else state
    model.load_state_dict({k[7:] if k.startswith('module.') else k: v for k, v in state.items()})
    model.eval()
    return model


def read_ring(path):
    seen, ts, green = set(), [], []
    for line in open(path):
        p = line.rstrip('\n').split('\t')
        key = tuple(p[:3])
        if key in seen:
            continue
        seen.add(key)
        ts.append(int(p[0]) / 1000.0)
        green.append(float(p[1]))
    return np.array(ts), np.array(green, dtype=np.float32)


def read_trace(path, origin):
    out = []
    for line in open(path):
        m = re.match(r't=\s*(\d+) hr=(Some\(([\d.]+)\)|None)', line)
        if m:
            out.append((origin + int(m.group(1)), float(m.group(3)) if m.group(3) else None))
    return out


def embed_recording(model, path):
    import torch
    from preprocessing import PpgWindowConfig, prepare_ppg_window
    ts, green = read_ring(path)
    cfg = PpgWindowConfig(source_sample_rate_hz=RATE, window_duration_s=10.0, target_sample_rate_hz=125.0, normalize=True)
    times, embs, ms = [], [], []
    for start in range(0, len(green) - WIN + 1, STEP):
        x, _ = prepare_ppg_window(green[start:start + WIN], cfg)
        t0 = time.perf_counter()
        with torch.no_grad():
            out = model(torch.tensor(x).float().reshape(1, 1, -1))
        ms.append((time.perf_counter() - t0) * 1000)
        e = out[0] if isinstance(out, (tuple, list)) else out
        embs.append(e.reshape(-1).numpy())
        times.append(ts[start + WIN // 2])
    return np.array(times), np.array(embs), ts[0], ms


def per_minute(times, values, min_count):
    groups = {}
    for t, v in zip(times, values):
        if v is not None and np.isfinite(v):
            groups.setdefault(int(t // 60 * 60), []).append(v)
    return {m: st.median(v) for m, v in groups.items() if len(v) >= min_count}


def score(pred, ref):
    keys = sorted(set(pred) & set(ref))
    d = [pred[k] - ref[k] for k in keys]
    if not d:
        return {'minutes': 0}
    return {'minutes': len(d), 'bias': round(st.mean(d), 1), 'mae': round(st.mean(abs(x) for x in d), 1),
            'within5': sum(abs(x) <= 5 for x in d)}


def save_head(pipeline, head_id, summary, n_windows):
    """Fold scaler + ridge into one linear map and write it in the worker's head format."""
    scaler, ridge = pipeline[0], pipeline[-1]
    w = ridge.coef_ / scaler.scale_
    b = ridge.intercept_ - float(np.dot(w, scaler.mean_))
    heads = os.path.join(WORKER, 'heads')
    os.makedirs(heads, exist_ok=True)
    np.savez(os.path.join(heads, f'{head_id}.npz'), weights=w.reshape(-1, 1).astype(np.float32),
             bias=np.array([b], dtype=np.float32))
    loro = summary['papagei_head_leave_one_recording_out']
    manifest = {
        'head_id': head_id,
        'head_version': time.strftime('%Y-%m-%d'),
        'display_name': 'Heart rate (PaPaGei-S + ridge)',
        'base_model_id': 'papagei_s',
        'input_dimension': int(w.size),
        'output_type': 'regression',
        'unit': 'bpm',
        'weights_file': f'{head_id}.npz',
        'task_description': 'Heart rate from a 10 s ring PPG window, labelled with EmbracePlus per-minute pulse rate.',
        'training': {'recordings': summary['recordings'], 'labelled_windows': int(n_windows),
                     'ridge_alpha': float(ridge.alpha_)},
        'validation': {'method': 'leave-one-recording-out, per-minute median', **loro},
        'validation_status': 'pilot: resting recordings only; not yet shown to beat a constant baseline',
    }
    with open(os.path.join(heads, f'{head_id}.json'), 'w') as f:
        json.dump(manifest, f, indent=2, default=float)
    print(f'saved head {head_id} to {heads}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--labelled', nargs='+', required=True, help='ring.tsv:trace.txt pairs inside the EmbracePlus recording')
    ap.add_argument('--embrace', required=True)
    ap.add_argument('--apply', help='ring.tsv:trace.txt of a recording without a reference, to run the trained head on')
    ap.add_argument('--out', default='papagei_out')
    ap.add_argument('--save-head', help='head id; writes the head trained on all labelled windows into ml-inference-server/heads/')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    from sklearn.linear_model import RidgeCV
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    ref = {}
    for row in csv.DictReader(open(args.embrace)):
        if row['pulse_rate_bpm']:
            ref[int(row['timestamp_unix']) // 1000] = float(row['pulse_rate_bpm'])

    model = load_model()
    recs, all_ms = [], []
    for pair in args.labelled:
        ring, trace = pair.split(':', 1) if pair.count(':') == 1 else pair.rsplit(':', 1)
        times, embs, origin, ms = embed_recording(model, ring)
        all_ms += ms
        labels = np.array([ref.get(int(t // 60 * 60), np.nan) for t in times])
        recs.append({'name': os.path.basename(ring), 'times': times, 'embs': embs, 'labels': labels,
                     'live': read_trace(trace, origin)})

    head = lambda: make_pipeline(StandardScaler(), RidgeCV(alphas=np.logspace(-1, 4, 30)))
    pap_pred, live_pred = {}, {}
    for i, r in enumerate(recs):
        train = [x for j, x in enumerate(recs) if j != i]
        X = np.vstack([x['embs'][~np.isnan(x['labels'])] for x in train])
        y = np.concatenate([x['labels'][~np.isnan(x['labels'])] for x in train])
        h = head().fit(X, y)
        pap_pred.update(per_minute(r['times'], h.predict(r['embs']), 6))
        live_pred.update(per_minute([t for t, _ in r['live']], [v for _, v in r['live']], 1))
    summary = {
        'windows_embedded': int(sum(len(r['times']) for r in recs)),
        'labelled_windows': int(sum((~np.isnan(r['labels'])).sum() for r in recs)),
        'cpu_ms_per_window_median': round(st.median(all_ms), 1),
        'papagei_head_leave_one_recording_out': score(pap_pred, ref),
        'live_algorithm_same_recordings': score(live_pred, ref),
        'recordings': [r['name'] for r in recs],
    }
    rows = [(m, ref.get(m), pap_pred.get(m), live_pred.get(m)) for m in sorted(set(pap_pred) | set(live_pred)) if m in ref]

    X = np.vstack([r['embs'][~np.isnan(r['labels'])] for r in recs])
    y = np.concatenate([r['labels'][~np.isnan(r['labels'])] for r in recs])
    h = head().fit(X, y)
    if args.save_head:
        save_head(h, args.save_head, summary, len(y))

    applied = None
    if args.apply:
        ring, trace = args.apply.rsplit(':', 1)
        times, embs, origin, ms = embed_recording(model, ring)
        pred = h.predict(embs)
        live = read_trace(trace, origin)
        lt = {int(round((t - origin) / 5)): v for t, v in live if v is not None}
        pairs = [(p, lt[k]) for t, p in zip(times, pred) for k in [int(round((t - origin) / 5))] if k in lt]
        diffs = [abs(a - b) for a, b in pairs]
        applied = {'recording': os.path.basename(ring), 'windows': int(len(times)),
                   'minutes': round((times[-1] - times[0]) / 60, 1),
                   'cpu_ms_per_window_median': round(st.median(ms), 1),
                   'windows_with_live_rate': len(pairs),
                   'median_abs_difference_to_live': round(st.median(diffs), 1) if diffs else None}
        summary['applied'] = applied

    with open(os.path.join(args.out, 'summary.json'), 'w') as f:
        json.dump(summary, f, indent=2, default=float)
    print(json.dumps(summary, indent=2, default=float))

    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import datetime as dt
    INK, ACC, REF = '#1A1A1A', '#B08F00', '#7A7A7A'
    n = 2 if applied else 1
    fig, axes = plt.subplots(n, 1, figsize=(12, 3.4 * n + 0.6), dpi=200)
    axes = np.atleast_1d(axes)
    a = axes[0]
    xs = [dt.datetime.fromtimestamp(m, dt.timezone.utc) for m, *_ in rows]
    a.plot(xs, [r[1] for r in rows], 'o-', color=REF, lw=2, ms=5, label='EmbracePlus (reference)')
    a.plot(xs, [r[2] if r[2] is not None else np.nan for r in rows], 's-', color=ACC, lw=2, ms=5, label='PaPaGei + linear head (held-out recording)')
    a.plot(xs, [r[3] if r[3] is not None else np.nan for r in rows], '^', color=INK, ms=6, label='Live algorithm')
    a.set_title('After collection: 23 Oct recordings, per minute', loc='left', fontsize=13)
    a.set_ylabel('bpm')
    a.legend(frameon=False, fontsize=10, loc='upper left')
    a.grid(alpha=0.25)
    a.spines[['top', 'right']].set_visible(False)
    if applied:
        b = axes[1]
        b.plot((times - times[0]) / 60, pred, color=ACC, lw=1.4, label='PaPaGei + linear head')
        b.plot([(t - origin) / 60 for t, v in live if v is not None], [v for t, v in live if v is not None], '.', color=INK, ms=4, label='Live algorithm')
        b.set_title(f"Same head on a new recording ({applied['minutes']} min, 3 Nov), every 5 s", loc='left', fontsize=13)
        b.set_xlabel('minutes since start')
        b.set_ylabel('bpm')
        b.set_ylim(40, 110)
        b.legend(frameon=False, fontsize=10, loc='upper left')
        b.grid(alpha=0.25)
        b.spines[['top', 'right']].set_visible(False)
    fig.tight_layout()
    fig.savefig(os.path.join(args.out, 'papagei.png'), facecolor='white')


if __name__ == '__main__':
    main()
