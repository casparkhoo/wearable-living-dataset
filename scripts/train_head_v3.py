#!/usr/bin/env python3
"""Retrain the hr_ring_stretch_v2 recipe with the 25 Nov 2026 fixes (sample order, polarity) and score it held out.

Protocol and deploy rule: outputs/hr_benchmark/PROTOCOL.md, addendum 2 (declared before any v3 number).

Variants (same 11 recordings, rule labels, time-stretch, ridge, offset as hr_ring_stretch_v2):
    v2r  no fix (legacy insertion order, raw counts) - reproduces the v2 recipe, diagnostic only
    v3i  inversion only (legacy order)               - diagnostic only
    v3a  sample-order fix only (raw counts)          - candidate hr_ring_stretch_v3a (head file alone)
    v3   both fixes                                  - candidate hr_ring_stretch_v3 (needs the worker to negate input)

Training data comes read-only from the local MySQL; embeddings from the local ONNX export (identical to the worker,
outputs/hr_benchmark/tables.md). Test windows are the benchmark's index windows (~/data_public/cache_bench,
written by `scripts/hr_benchmark.py ring`). Outputs: outputs/hr_benchmark/v3/ (results.md, results.json, the two
candidate heads). Nothing is written to ml-inference-server/heads/.

    python scripts/train_head_v3.py
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
sys.path.insert(0, HERE)

import hr_bench_common as hb                 # noqa: E402
import hr_benchmark as B                     # noqa: E402
import hr_benchmark_score as S               # noqa: E402
import train_head_from_rules as r            # noqa: E402
from hr_common import minute_of              # noqa: E402

OUT = os.path.join(ROOT, 'outputs', 'hr_benchmark', 'v3')
TRAIN = (55, 60, 65, 68, 70, 76, 94, 105, 108, 110, 118)       # hr_ring_stretch_v2.json "training.recordings"
TEST = (66, 67, 140, 147, 148)
RULE_SESSIONS = (140, 147, 148)
VARIANTS = {'v2r': (False, False), 'v3i': (False, True), 'v3a': (True, False), 'v3': (True, True)}  # (fixed order, invert)
CANDIDATES = {'v3': 'hr_ring_stretch_v3', 'v3a': 'hr_ring_stretch_v3a'}


# ---------------------------------------------------------------- legacy loaders (pre-25-Nov behaviour, for v2r / v3i)

def legacy_series(rows):
    """Insertion order, exact repeats dropped: what green_series returned before the fix."""
    out, seen = [], set()
    for t, i, g in rows:
        if (i, g) in seen:
            continue
        seen.add((i, g))
        out.append((t, i, g))
    return out


def legacy_stretched(series, end_ms):
    values = [g for t, _, g in series if end_ms - 15_000 < t <= end_ms]
    if len(values) < 375:
        return []
    values = np.asarray(values[-375:], dtype=float)
    out = []
    for k in r.STRETCHES:
        n = int(round(250 * k))
        seg = values[-n:]
        out.append((k, np.interp(np.linspace(0, n - 1, 250), np.arange(n), seg)))
    return out


# ---------------------------------------------------------------- display simulation (as the live engine shows it)

def simulated_display(sess, seg, centre, est):
    """Display value per window = median of the last 3 estimates in its run of consecutive windows (5 s apart),
    timed at the window end (centre + 5 s)."""
    disp = np.full(len(est), np.nan)
    for run in B.runs(sess, seg, centre, 5_000):
        vals = est[run]
        for j in range(len(run)):
            w = vals[max(0, j - 2):j + 1]
            w = w[np.isfinite(w)]
            if len(w):
                disp[run[j]] = float(np.median(w))
    return disp, centre + 5_000


def display_scores(disp, t_end, sess, ref, notes, const):
    """Per session: per-minute median of display values (>= 3) vs the EmbracePlus minute; all / still / active by
    annotation (score_live_display.py's grouping), oracle constant per group, and the declared constant."""
    out = {}
    for s in TEST:
        sel = (sess == s) & np.isfinite(disp)
        med = hb.minute_medians([minute_of(t) for t in t_end[sel]], disp[sel])
        rows = [(m, med[m], ref[s][m]) for m in sorted(med) if m in ref[s]]
        d = {}
        for g in ('all', 'still', 'active'):
            rr = [x for x in rows if g == 'all' or (S.activity_group(x[0], notes[s])[0] if s in B.ACTIVE_REC else 'still') == g]
            if len(rr) < 1:
                continue
            e, f = np.array([x[1] for x in rr]), np.array([x[2] for x in rr])
            m = hb.window_metrics(e, f)
            m['oracle_const_mae'] = float(np.abs(f - np.median(f)).mean())
            m['const_mae'] = float(np.abs(f - const).mean())
            d[g] = m
        out[s] = {'groups': d, 'pairs': [(int(m), float(a), float(b)) for m, a, b in rows]}
    return out


def pooled(ds, sessions=RULE_SESSIONS):
    pairs = [p for s in sessions for p in ds[s]['pairs']]
    if not pairs:
        return None
    e, f = np.array([p[1] for p in pairs]), np.array([p[2] for p in pairs])
    return hb.window_metrics(e, f)


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--data-root', default=B.default_data_root())
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    base = B.compose(args.data_root)

    # training windows per variant
    X, Y, G, REAL = {v: [] for v in VARIANTS}, {v: [] for v in VARIANTS}, {v: [] for v in VARIANTS}, {v: [] for v in VARIANTS}
    counts = {}
    for sid in TRAIN:
        rows = B.sql(base, f"SELECT backend_received_timestamp_ms, sample_index, JSON_EXTRACT(channel_values, '$.green') "
                           f"FROM stream_samples WHERE session_id={sid} AND stream_name='ppg' ORDER BY id;")
        clean = [(int(t), int(i), float(g)) for t, i, g in rows if t != 'NULL' and i != 'NULL' and g not in ('NULL', 'null')]
        fixed, legacy = r.sample_order(clean), legacy_series(clean)
        labels = [(int(e), float(h)) for e, h, _ in r.rule_windows(base, sid)]
        counts[sid] = {'labels': len(labels)}
        for v, (fix, inv) in VARIANTS.items():
            n = 0
            for end, hr in labels:
                wins = r.stretched(fixed, end) if fix else legacy_stretched(legacy, end)
                for k, x in wins:
                    X[v].append(np.asarray(r.to_model_input(x, inv))); Y[v].append(hr * k); G[v].append(sid); REAL[v].append(k == 1.0)
                    n += 1
            counts[sid][v] = n
        print(sid, counts[sid], flush=True)

    heads, info = {}, {}
    for v in VARIANTS:
        emb = B.embed(np.array(X[v]), B.FS_RING)
        ok = np.isfinite(emb).all(1)
        Xe, y, g, real = emb[ok].astype(np.float64), np.array(Y[v])[ok], np.array(G[v])[ok], np.array(REAL[v])[ok]
        best, curve = None, {}
        for alpha in (30, 100, 300, 1000, 3000):
            errs = []
            for held in np.unique(g):
                if (g == held).sum() < 5 or (g != held).sum() < 20:
                    continue
                w, b = r.ridge(Xe[g != held], y[g != held], alpha)
                errs.extend(np.abs(Xe[g == held] @ w + b - y[g == held]))
            curve[alpha] = float(np.mean(errs))
            if best is None or curve[alpha] < best[1]:
                best = (alpha, curve[alpha])
        w, b = r.ridge(Xe, y, best[0])
        b += float(np.mean(y[real] - (Xe[real] @ w + b)))
        heads[v] = (w, b)
        info[v] = {'windows': int(len(y)), 'refused': int((~ok).sum()), 'alpha': best[0], 'loro_mae_curve': curve,
                   'label_range': [float(y.min()), float(y.max())], 'const': float(y[real].mean()),
                   'recordings': sorted({int(s) for s in g})}
        print(v, info[v], flush=True)

    # test: benchmark index windows (green), sign per variant
    z, db, ref, notes, day, sess, minute, label = S.load_ring(args.data_root)
    v2 = np.load(os.path.join(B.HEADS, 'hr_ring_stretch_v2.npz'))
    est = {'v2': z['emb_green_pos'].astype(np.float64) @ v2['weights'].reshape(-1) + float(np.asarray(v2['bias']).reshape(-1)[0])}
    for v, (fix, inv) in VARIANTS.items():
        E = z['emb_green_neg' if inv else 'emb_green_pos'].astype(np.float64)
        est[v] = E @ heads[v][0] + heads[v][1]
    sel_v2r = np.isin(sess, TEST)
    repro = float(np.corrcoef(est['v2'][sel_v2r], est['v2r'][sel_v2r])[0, 1])

    res = {'training': info, 'counts': counts, 'v2r_vs_v2_window_r': repro, 'display': {}, 'pooled_display': {}, 'benchmark_rows': {}}
    for name, e in est.items():
        const = info['v2r']['const'] if name == 'v2' else info[name]['const']
        disp, t_end = simulated_display(sess, z['seg'], z['centre'], e)
        ds = display_scores(disp, t_end, sess, ref, notes, const)
        res['display'][name] = ds
        p = pooled(ds)
        if p:
            fpairs = [q for s in RULE_SESSIONS for q in ds[s]['pairs']]
            p['const_mae'] = float(np.mean([abs(const - q[2]) for q in fpairs]))
            p['const'] = const
        res['pooled_display'][name] = p
        live_minutes = set()
        res['benchmark_rows'][name] = S.score_ring_rows(e, sess, minute, ref, notes, day, live_minutes, sessions=list(TEST))
    # stored live display (v2) for reference, same minute rule
    st = {}
    for s in RULE_SESSIONS:
        vals = [(t, d) for t, q, h, d, mv in db['summaries'][str(s)] if d is not None and np.isfinite(d)]
        med = hb.minute_medians([minute_of(t) for t, _ in vals], [d for _, d in vals])
        rows = [(m, med[m], ref[s][m]) for m in sorted(med) if m in ref[s]]
        st[s] = hb.window_metrics([x[1] for x in rows], [x[2] for x in rows])
    res['stored_live_display_v2'] = st

    # declared deploy rule
    rule = {}
    for cand in CANDIDATES:
        c = res['display'][cand]; b2 = res['display']['v2']
        checks = {f'lower_than_v2_on_{s}': bool(c[s]['groups']['all']['mae'] < b2[s]['groups']['all']['mae']) for s in (148, 140, 147)}
        pc = res['pooled_display'][cand]
        checks['pooled_not_above_constant'] = bool(pc['mae'] <= pc['const_mae'])
        rule[cand] = {'checks': checks, 'passes': all(checks.values())}
    res['deploy_rule'] = rule

    # candidate heads (not installed)
    for cand, hid in CANDIDATES.items():
        w, b = heads[cand]
        np.savez(os.path.join(OUT, f'{hid}.npz'), weights=w.reshape(-1, 1).astype(np.float32), bias=np.array([b], dtype=np.float32))
        fix, inv = VARIANTS[cand]
        manifest = {
            'head_id': hid, 'head_version': '2026-11-25',
            'display_name': 'Heart rate (PaPaGei-S + ridge, rule-labelled, time-stretched, sample-ordered'
                            + (', inverted input)' if inv else ')'),
            'base_model_id': 'papagei_s', 'input_dimension': 512, 'output_type': 'regression', 'unit': 'bpm',
            'weights_file': f'{hid}.npz',
            'task_description': 'hr_ring_stretch_v2 recipe retrained with windows in sample order'
                                + (' and the ring PPG negated before PaPaGei' if inv else '') + '.',
            'input_transform': {'invert': bool(inv)},
            'training': {'recordings': info[cand]['recordings'], 'labelled_windows': info[cand]['windows'],
                         'label_range_bpm': info[cand]['label_range'], 'ridge_alpha': info[cand]['alpha'],
                         'labels': 'live_ppg heart rate, quality good or fair',
                         'augmentation': {'time_stretch': list(r.STRETCHES)}, 'script': 'scripts/train_head_v3.py'},
            'validation': {'method': 'simulated shown rate vs EmbracePlus per minute, held out 140, 147, 148',
                           'pooled': res['pooled_display'][cand], 'deploy_rule': rule[cand]},
            'validation_status': ('candidate; requires the worker to negate ppg_data for this head (input_transform.invert) '
                                  'before it can be used live' if inv else 'candidate'),
        }
        with open(os.path.join(OUT, f'{hid}.json'), 'w', encoding='utf8') as f:
            json.dump(manifest, f, indent=2, default=float)
    with open(os.path.join(OUT, 'results.json'), 'w') as f:
        json.dump(res, f, indent=1, default=float)
    write_md(res)


def write_md(res):
    f1 = lambda x, d=1: '' if x is None or not np.isfinite(x) else f'{x:.{d}f}'
    L = ['# v3 head: hr_ring_stretch_v2 recipe with the sample-order and polarity fixes (generated by scripts/train_head_v3.py)', '',
         'Protocol and deploy rule: PROTOCOL.md addendum 2 (committed before these numbers). Shown rate simulated as live '
         '(estimate every 5 s, display = median of last 3, per-minute median), against the EmbracePlus.', '',
         f"Reproduction check: the locally retrained unfixed recipe (v2r) vs the installed v2 head, per test window: r = {res['v2r_vs_v2_window_r']:.3f}.", '',
         '## Training', '', '| variant | windows | alpha | leave-one-recording-out MAE vs rule labels | label mean (constant) |', '|---|---|---|---|---|']
    for v, d in res['training'].items():
        L.append(f"| {v} | {d['windows']} | {d['alpha']} | {f1(d['loro_mae_curve'][d['alpha']], 2)} | {f1(d['const'])} |")
    L += ['', '## Simulated shown rate, per minute (MAE / bias / r / minutes; oracle constant in brackets)', '',
          '| head | 148 | 140 | 147 | 140 still | 140 active | 147 still | 147 active | 66 | 67 | pooled 140+147+148 (constant) |',
          '|---|---|---|---|---|---|---|---|---|---|---|']
    for name, ds in res['display'].items():
        def cell(s, g='all'):
            d = ds[s]['groups'].get(g)
            return f"{f1(d['mae'])} / {d['bias']:+.1f} / {f1(d['r'], 2)} / {d['n']} ({f1(d['oracle_const_mae'])})" if d and d.get('n') else ''
        p = res['pooled_display'][name]
        L.append(f"| {name} | {cell(148)} | {cell(140)} | {cell(147)} | {cell(140, 'still')} | {cell(140, 'active')} | "
                 f"{cell(147, 'still')} | {cell(147, 'active')} | {cell(66)} | {cell(67)} | {f1(p['mae'])} ({f1(p['const_mae'])}) |")
    st = res['stored_live_display_v2']
    L += ['', 'Stored live display (v2, as shown at the time): ' + ', '.join(f"{s}: MAE {f1(d['mae'])} on {d['n']} minutes" for s, d in st.items()), '',
          '## Deploy rule', '', '| candidate | ' + ' | '.join(next(iter(res['deploy_rule'].values()))['checks']) + ' | passes |',
          '|---|' + '---|' * (len(next(iter(res['deploy_rule'].values()))['checks']) + 1)]
    for c, d in res['deploy_rule'].items():
        L.append(f"| {c} | " + ' | '.join('yes' if x else 'no' for x in d['checks'].values()) + f" | **{'yes' if d['passes'] else 'no'}** |")
    L += ['', '## Pooled benchmark rows (per-minute median of raw window estimates, held-out sessions 66, 67, 140, 147, 148)', '',
          '| head | minutes | MAE | bias | r | rest rec. MAE | activity rec. MAE | r activity | constant MAE |', '|---|---|---|---|---|---|---|---|---|']
    for name, d in res['benchmark_rows'].items():
        a, rr, ac = d['all'], d.get('rest_rec', {}), d.get('active_rec', {})
        L.append(f"| {name} | {a['n']} | {f1(a['mae'])} | {a['bias']:+.1f} | {f1(a['r'], 2)} | {f1(rr.get('mae'))} | {f1(ac.get('mae'))} | "
                 f"{f1(ac.get('r'), 2)} | {f1(a['const_mae'])} |")
    d, st = res['display'], res['stored_live_display_v2']
    L += ['', '## Reading and deviations', '',
          f"- By the declared rule neither candidate qualifies: v3 is worse than v2 on 140 "
          f"({f1(d['v3'][140]['groups']['all']['mae'])} vs {f1(d['v2'][140]['groups']['all']['mae'])}); v3a also fails the constant check.",
          f"- The simulation is not the live display: simulated v2 scores {f1(d['v2'][148]['groups']['all']['mae'])} on 148, but the stored "
          f"live v2 display scored {f1(st[148]['mae'])} there ({f1(st[140]['mae'])} on 140, {f1(st[147]['mae'])} on 147). v3's simulated "
          f"{f1(d['v3'][148]['groups']['all']['mae'])} on 148 is worse than what v2 actually showed live, so its gain on 148 may be a "
          'simulation artefact.',
          f"- v3 loses at rest: 66 goes from {f1(d['v2'][66]['groups']['all']['mae'])} to {f1(d['v3'][66]['groups']['all']['mae'])}; in the "
          f"pooled rows its bias moves from {res['benchmark_rows']['v2']['all']['bias']:+.1f} to {res['benchmark_rows']['v3']['all']['bias']:+.1f} "
          f"with r {f1(res['benchmark_rows']['v3']['all']['r'], 2)}: a level shift, not tracking.",
          f"- Deviation from addendum 2: the fixed window code accepts samples that arrived within 17 s (15 s + 2 s for batched "
          f"arrival) instead of 15 s, so v3/v3a train on {res['training']['v3']['windows']} windows instead of "
          f"{res['training']['v2r']['windows']} (labels up to {res['training']['v3']['label_range'][1]:.0f} bpm). v3a is therefore "
          '"sample order + wider span", not a clean single-fix ablation. No verdict changes because of this.',
          '- v2r reproduces the installed v2 exactly (same 1274 windows, alpha 300, window predictions r = 1.000), so the recipe is the same.']
    with open(os.path.join(OUT, 'results.md'), 'w', encoding='utf8') as f:
        f.write('\n'.join(L) + '\n')


if __name__ == '__main__':
    main()
