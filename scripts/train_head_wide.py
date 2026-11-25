#!/usr/bin/env python3
"""Heart-rate heads trained on a WIDE range of the wearer's own heart rates (EmbracePlus minute
labels of the 23 Nov recording, 51-113 bpm) and evaluated without leakage as far as one recording
allows.

Own data: ring session 140 (green, ~25 Hz) and earbud sessions 139 and 141 (infrared, ~50 Hz) record
the same 09:19-09:44 UTC period of the same wearer, so they share one heart-rate trajectory. Windows
(10 s, 5 s step, ends on a global 5 s grid so the three devices share window centres) are labelled
with the EmbracePlus pulse_rate_bpm of the minute holding the window centre ('minute') or linearly
interpolated between minute values placed at minute start + 30 s ('interp'; never across a gap longer
than 60 s). A minute value is an aggregate over that minute, so a label is uncertain by about +-30 s.

Evaluation is leave-one-time-block-out inside the recording: rest (09:19-09:27), exercise
(09:27-09:32), recovery and lying (09:32-09:44). A held-out block removes ALL devices' windows of that
time range from training (the earbuds and the ring have the same heart rate and the same motion), and
a guard band of 30 s around the block is removed too. Windows that straddle a block boundary are in
neither train nor test. Also: 'ex_only' trains on the exercise block only and tests on rest and recovery.
Ridge strength is chosen inside the training part only (leave-one-2-minute-chunk-out over the own
windows, 30 s guard). Scores are per minute (median of >= 3 window estimates), as in
train_head_from_rules.py.

Other training data: older ring recordings with rules-engine labels, time-stretched as in stretch_v2
('stretch'), and the cached PPG-DaLiA / BIDMC windows ('public'). Each added source gets a TOTAL weight
equal to the own windows' total weight (weighted ridge), otherwise 31k public windows swamp ~500 own ones.

Embeddings come from the PaPaGei worker through `docker compose exec` (read-only, 3 parallel processes).

    python -I scripts/train_head_wide.py prepare --dalia-dir ... --bidmc-dir ...
    python scripts/train_head_wide.py evaluate --reference tmp/empatica_nov23c/<..>_pulse-rate.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import statistics as st
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
HEADS = os.path.join(ROOT, 'ml-inference-server', 'heads')
OUT = os.path.join(ROOT, 'outputs', 'headtrain_wide')
CACHE = os.path.join(os.path.expanduser('~'), 'data_public', 'cache_wide')
WINDOW_MS, STEP_MS = 10_000, 5_000
DAY_MS = 1795392000000            # 2026-11-23T00:00:00Z
GUARD_MS = 30_000
CHUNK_MS = 120_000
ALPHAS = (0.1, 1, 10, 100, 1000, 10_000, 100_000)


def at(h, m):
    return DAY_MS + h * 3_600_000 + m * 60_000


BLOCKS = {'rest': (at(9, 19), at(9, 27)), 'exercise': (at(9, 27), at(9, 32)), 'recovery': (at(9, 32), at(9, 44))}
# (name, session, channel, min samples in a 10 s window, expected rate)
DEVICES = {'ring': (140, 'green', 200), 'ear141': (141, 'ir', 300), 'ear139': (139, 'ir', 300)}
MIXES = ('own', 'own+stretch', 'own+public', 'own+both')
TRAIN_DEVS = {'ring': ('ring',), 'ear': ('ear141', 'ear139'), 'ring+ear': ('ring', 'ear141', 'ear139')}
LABELS = ('minute', 'interp')
# Chosen BEFORE any exercise-block number was seen: the widest variant. Only this one can trigger a save.
CANDIDATE = {'mix': 'own+both', 'train': 'ring+ear', 'labels': 'interp'}
HEAD_ID = 'hr_wide_v1'


# ---------------------------------------------------------------- pure functions

def window_ends(t_first, t_last, window_ms=WINDOW_MS, step_ms=STEP_MS):
    """Window end times on a global grid of step_ms, each with a full window inside [t_first, t_last]."""
    first = int(-(-(int(t_first) + window_ms) // step_ms) * step_ms)
    return list(range(first, int(t_last) + 1, step_ms))


def to_n_samples(x, n=250):
    """Squeeze or stretch the samples of a 10 s window to n evenly spaced samples. When there are more
    samples than n (earbud 50 Hz -> 25 Hz) a boxcar average of width len/n is applied first (anti-alias)."""
    x = np.asarray(x, dtype=float).reshape(-1)
    if len(x) == n:
        return x
    if len(x) > n:
        width = max(1, int(round(len(x) / n)))
        if width > 1:
            x = np.convolve(x, np.ones(width) / width, mode='same')
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x)


def cut_window(t, v, end_ms, min_samples, window_ms=WINDOW_MS):
    """The 250-sample window of samples that arrived in (end-10 s, end], or None if fewer than min_samples."""
    sel = (t > end_ms - window_ms) & (t <= end_ms)
    if sel.sum() < min_samples:
        return None
    return to_n_samples(v[sel])


def label_minute(centre_ms, ref):
    """Reference of the minute holding the centre; ref is {minute index (ms // 60000): bpm}."""
    return ref.get(int(centre_ms // 60_000))


def label_interp(centre_ms, ref, max_gap_ms=60_000):
    """Linear interpolation between minute values placed at minute start + 30 s. None outside the
    anchors or when the two bracketing anchors are more than max_gap_ms apart (a missing minute)."""
    pts = sorted((m * 60_000 + 30_000, v) for m, v in ref.items())
    lo = hi = None
    for p in pts:
        if p[0] <= centre_ms:
            lo = p
        elif hi is None:
            hi = p
    if lo is None or hi is None or hi[0] - lo[0] > max_gap_ms:
        return None
    f = (centre_ms - lo[0]) / (hi[0] - lo[0])
    return lo[1] + f * (hi[1] - lo[1])


def block_of(start_ms, end_ms, blocks=BLOCKS):
    """Name of the block that contains the whole window, or None (straddles a boundary or outside)."""
    for name, (a, b) in blocks.items():
        if start_ms >= a and end_ms <= b:
            return name
    return None


def split_blocks(starts, ends, test_blocks, train_blocks, blocks=BLOCKS, guard_ms=GUARD_MS):
    """(train_mask, test_mask). Test: windows wholly inside a test block. Train: windows wholly inside a
    train block and at least guard_ms away from every test block. Same split for every device."""
    starts, ends = np.asarray(starts), np.asarray(ends)
    names = [block_of(s, e, blocks) for s, e in zip(starts, ends)]
    test = np.array([n in test_blocks for n in names])
    train = np.array([n in train_blocks and n not in test_blocks for n in names])
    for tb in test_blocks:
        a, b = blocks[tb]
        gap = np.maximum(np.maximum(a - ends, starts - b), 0)
        train &= gap >= guard_ms
    return train, test


def chunk_ids(centres_ms, chunk_ms=CHUNK_MS):
    return ((np.asarray(centres_ms) - DAY_MS) // chunk_ms).astype(int)


def weighted_stats(X, w):
    sw = w.sum()
    mean = (w[:, None] * X).sum(0) / sw
    std = np.sqrt((w[:, None] * (X - mean) ** 2).sum(0) / sw) + 1e-9
    return mean, std


def ridge_grid(X, y, sw, alphas):
    """Weighted ridge on standardised features for several alphas at once. Returns {alpha: (weights, bias)}
    with the standardisation folded into raw-embedding weights (the form heads are stored in)."""
    mean, std = weighted_stats(X, sw)
    Z = (X - mean) / std
    ym = float((sw * y).sum() / sw.sum())
    Zw = Z * sw[:, None]
    G = Zw.T @ Z
    b = Zw.T @ (y - ym)
    out = {}
    for a in alphas:
        wz = np.linalg.solve(G + a * np.eye(G.shape[0]), b)
        out[a] = fold_in(wz, mean, std, ym)
    return out


def fold_in(wz, mean, std, ym):
    """Weights on standardised features -> raw weights and bias: x @ w + b == ((x-mean)/std) @ wz + ym."""
    w = wz / std
    return w, float(ym - mean @ w)


def source_weights(kinds, n_own):
    """Per-row weights: own rows 1; every other source gets a total weight equal to n_own."""
    kinds = np.asarray(kinds)
    w = np.ones(len(kinds))
    for k in set(kinds) - {'own'}:
        m = kinds == k
        w[m] = n_own / m.sum()
    return w


def choose_alpha(X, y, kinds, centres, alphas=ALPHAS, guard_ms=GUARD_MS):
    """Alpha by leave-one-chunk-out over the OWN rows only: each 2-minute chunk of own windows is
    validated on, with own rows within the guard excluded from training; other sources stay in training."""
    kinds = np.asarray(kinds)
    own = np.where(kinds == 'own')[0]
    ch = chunk_ids(centres[own])
    errs = {a: [] for a in alphas}
    for c in np.unique(ch):
        val = own[ch == c]
        lo, hi = centres[val].min() - guard_ms, centres[val].max() + guard_ms
        drop = (kinds == 'own') & (centres >= lo - WINDOW_MS // 2) & (centres <= hi + WINDOW_MS // 2)
        tr = ~drop
        if (kinds[tr] == 'own').sum() < 10:
            continue
        w = source_weights(kinds[tr], int((kinds[tr] == 'own').sum()))
        fits = ridge_grid(X[tr], y[tr], w, alphas)
        for a, (wt, bt) in fits.items():
            errs[a].extend(np.abs(X[val] @ wt + bt - y[val]))
    mae = {a: float(np.mean(e)) if e else float('inf') for a, e in errs.items()}
    return min(mae, key=mae.get), mae


def score_minutes(centres, pred, ref, min_windows=3):
    """Per-minute median of window estimates against the minute reference. Returns (summary, pairs)."""
    by = {}
    for c, p in zip(centres, pred):
        by.setdefault(int(c // 60_000), []).append(p)
    pairs = [(m, float(st.median(v)), ref[m]) for m, v in sorted(by.items()) if len(v) >= min_windows and m in ref]
    return summarise(pairs), pairs


def summarise(pairs):
    if not pairs:
        return None
    est = np.array([p[1] for p in pairs]); tru = np.array([p[2] for p in pairs])
    err = est - tru
    r = float(np.corrcoef(est, tru)[0, 1]) if len(pairs) > 2 and est.std() > 0 and tru.std() > 0 else float('nan')
    return {'minutes': len(pairs), 'mae': float(np.abs(err).mean()), 'bias': float(err.mean()), 'r': r,
            'est_min': float(est.min()), 'est_max': float(est.max()), 'sd_est': float(est.std()),
            'sd_ref': float(tru.std()), 'oracle_const_mae': float(np.abs(tru - np.median(tru)).mean()),
            'ref_min': float(tru.min()), 'ref_max': float(tru.max())}


def gate(s, min_r=0.5):
    """Save gate on the held-out exercise block: beat the best (oracle) constant and r above min_r."""
    if not s or not np.isfinite(s['r']):
        return False
    return bool(s['mae'] < s['oracle_const_mae'] and s['r'] > min_r)


def read_reference(path):
    ref = {}
    with open(path, newline='') as f:
        for row in csv.DictReader(f):
            if row['pulse_rate_bpm'] not in ('', None):
                ref[int(float(row['timestamp_unix']) // 60_000)] = float(row['pulse_rate_bpm'])
    return ref


# ---------------------------------------------------------------- stage 1: prepare (database, embeddings)

def series(base, r, session, channel):
    if channel == 'green':
        s = r.green_series(base, session)
        return np.array([x[0] for x in s], float), np.array([x[2] for x in s], float)
    rows = r.sql(base, f"SELECT backend_received_timestamp_ms, sample_index, JSON_EXTRACT(channel_values, '$.{channel}') "
                       f"FROM stream_samples WHERE session_id={session} AND stream_name='ppg' ORDER BY id;")
    # Sample order, not insertion order (fix of 25 Nov 2026, see train_head_from_rules.sample_order).
    s = r.sample_order([(int(t), int(i), float(v)) for t, i, v in rows
                        if t != 'NULL' and i != 'NULL' and v not in ('NULL', 'null')])
    return np.array([x[0] for x in s], float), np.array([x[2] for x in s], float)


def prepare(args):
    sys.path.insert(0, HERE)
    import train_head_from_rules as r
    import train_head_public as tp
    base = tp.compose_base(args.env, args.compose)
    os.makedirs(CACHE, exist_ok=True)
    timings = {}

    # own windows of the three devices
    rows = {k: [] for k in ('dev', 'start', 'end', 'raw')}
    for dev, (sid, ch, min_n) in DEVICES.items():
        t, v = series(base, r, sid, ch)
        n = 0
        for end in window_ends(t.min(), t.max()):
            x = cut_window(t, v, end, min_n)
            if x is not None and np.isfinite(x).all() and x.std() > 1e-6:
                rows['dev'].append(dev); rows['start'].append(end - WINDOW_MS); rows['end'].append(end)
                rows['raw'].append(x); n += 1
        print(f'{dev}: session {sid} {ch}, {len(t)} samples, effective {len(t) / ((t.max() - t.min()) / 1000):.1f} Hz, '
              f'{n} windows', flush=True)
    raw = np.array(rows['raw'])
    t0 = time.time()
    emb = tp.embed_windows(base, raw, procs=args.procs)
    timings['own_embed'] = {'windows': len(raw), 'seconds': round(time.time() - t0, 1)}
    print('own embeddings', timings['own_embed'], flush=True)
    ok = np.isfinite(emb).all(1)
    # accelerometer activity per window (ring), for the "is it just movement" check
    acc = r.sql(base, "SELECT backend_received_timestamp_ms, JSON_EXTRACT(channel_values,'$.x'), "
                      "JSON_EXTRACT(channel_values,'$.y'), JSON_EXTRACT(channel_values,'$.z') FROM stream_samples "
                      "WHERE session_id=140 AND stream_name='accel' ORDER BY id;")
    at_ = np.array([float(a[0]) for a in acc]); mag = np.sqrt(sum(np.array([float(a[i]) for a in acc]) ** 2 for i in (1, 2, 3)))
    accel = {}
    for m in np.unique((at_ // 60_000).astype(int)):
        s = mag[(at_ // 60_000).astype(int) == m]
        accel[int(m)] = float(s.std())
    np.savez_compressed(os.path.join(CACHE, 'own.npz'), X=emb[ok].astype(np.float32), dev=np.array(rows['dev'])[ok],
                        start=np.array(rows['start'])[ok], end=np.array(rows['end'])[ok])
    with open(os.path.join(CACHE, 'accel_minutes.json'), 'w') as f:
        json.dump(accel, f)

    # older ring recordings with rule labels, time-stretched (as stretch_v2); session 140 is excluded
    Xs, ys, gs = [], [], []
    wins, meta = [], []
    for sid in r.ring_sessions(base):
        if sid == 140:
            continue
        labels = r.rule_windows(base, sid)
        if not labels:
            continue
        ser = r.green_series(base, sid)
        for end, hr, _ in labels:
            for k, x in r.stretched(ser, end):
                wins.append(x); meta.append((sid, hr * k, end))
    W = np.array(wins, float)
    t0 = time.time()
    e = tp.embed_windows(base, W, procs=args.procs)
    timings['stretch_embed'] = {'windows': len(W), 'seconds': round(time.time() - t0, 1)}
    print('stretch embeddings', timings['stretch_embed'], flush=True)
    ok = np.isfinite(e).all(1)
    np.savez_compressed(os.path.join(CACHE, 'stretch.npz'), X=e[ok].astype(np.float32),
                        y=np.array([m[1] for m in meta])[ok], group=np.array([m[0] for m in meta])[ok])

    t0 = time.time()
    X, y, g, src, act, pt = tp.build_public(args.dalia_dir, args.bidmc_dir, base, args.procs)
    timings['public_build'] = {'windows': int(len(y)), 'seconds': round(time.time() - t0, 1),
                               'note': 'cached embeddings reused when present'}
    np.savez_compressed(os.path.join(CACHE, 'public.npz'), X=X.astype(np.float32), y=y, group=g, src=src)
    with open(os.path.join(CACHE, 'timings.json'), 'w') as f:
        json.dump(timings, f, indent=2)
    print('prepared', timings)


# ---------------------------------------------------------------- stage 2: evaluate

def load_cache():
    own = np.load(os.path.join(CACHE, 'own.npz'))
    stretch = np.load(os.path.join(CACHE, 'stretch.npz'))
    public = np.load(os.path.join(CACHE, 'public.npz'))
    with open(os.path.join(CACHE, 'accel_minutes.json')) as f:
        accel = {int(k): v for k, v in json.load(f).items()}
    with open(os.path.join(CACHE, 'timings.json')) as f:
        timings = json.load(f)
    return own, stretch, public, accel, timings


def assemble(own, stretch, public, mix, devs, labels, ref, train_mask_own):
    """Training matrix for one fit: own rows (devices in devs, in train_mask_own, with a label) plus the
    other sources of the mix. Returns X, y, kinds, centres (centres only meaningful for own rows)."""
    centres_all = (own['start'] + own['end']) // 2
    fn = label_minute if labels == 'minute' else label_interp
    idx, ys = [], []
    for i in np.where(train_mask_own & np.isin(own['dev'], devs))[0]:
        v = fn(centres_all[i], ref)
        if v is not None:
            idx.append(i); ys.append(v)
    idx = np.array(idx, int)
    Xs, yv, kinds, cent = [own['X'][idx]], [np.array(ys)], ['own'] * len(idx), [centres_all[idx]]
    if mix in ('own+stretch', 'own+both'):
        Xs.append(stretch['X']); yv.append(stretch['y']); kinds += ['stretch'] * len(stretch['y'])
        cent.append(np.zeros(len(stretch['y']), int))
    if mix in ('own+public', 'own+both'):
        Xs.append(public['X']); yv.append(public['y']); kinds += ['public'] * len(public['y'])
        cent.append(np.zeros(len(public['y']), int))
    return (np.concatenate(Xs).astype(np.float64), np.concatenate(yv).astype(float), np.array(kinds),
            np.concatenate(cent))


def fit_head(X, y, kinds, cent):
    """Alpha by chunk CV inside the training part, then the final ridge on all of it."""
    n_own = int((kinds == 'own').sum())
    alpha, cv = choose_alpha(X, y, kinds, cent)
    w = source_weights(kinds, n_own)
    wt, b = ridge_grid(X, y, w, [alpha])[alpha]
    return wt, b, alpha, cv


FOLDS = {'rest': (['rest'], ['exercise', 'recovery']), 'exercise': (['exercise'], ['rest', 'recovery']),
         'recovery': (['recovery'], ['rest', 'exercise']), 'ex_only->rest': (['rest'], ['exercise']),
         'ex_only->recovery': (['recovery'], ['exercise'])}


def evaluate(args):
    own, stretch, public, accel, timings = load_cache()
    ref = read_reference(args.reference)
    starts, ends = own['start'], own['end']
    centres = (starts + ends) // 2
    results = {'timings': timings, 'n_own_windows': {d: int((own['dev'] == d).sum()) for d in DEVICES},
               'n_stretch': int(len(stretch['y'])), 'n_public': int(len(public['y'])),
               'ref_range': [min(ref[m] for m in ref if BLOCKS['rest'][0] // 60000 <= m < BLOCKS['recovery'][1] // 60000),
                             max(ref[m] for m in ref if BLOCKS['rest'][0] // 60000 <= m < BLOCKS['recovery'][1] // 60000)],
               'candidate': CANDIDATE, 'variants': {}}
    pooled_pairs, cand_pairs = {}, {}
    for mix in MIXES:
        for tname, tdevs in TRAIN_DEVS.items():
            for labels in LABELS:
                key = f'{mix}|{tname}|{labels}'
                res = {'folds': {}, 'alpha': {}}
                pairs_pool = {d: [] for d in DEVICES}
                for fold, (tb, trb) in FOLDS.items():
                    tr, te = split_blocks(starts, ends, tb, trb)
                    X, y, kinds, cent = assemble(own, stretch, public, mix, tdevs, labels, ref, tr)
                    if (kinds == 'own').sum() < 20:
                        continue
                    wt, b, alpha, _ = fit_head(X, y, kinds, cent)
                    res['alpha'][fold] = alpha
                    train_const = float(y[kinds == 'own'].mean())
                    for dev in DEVICES:
                        if dev not in tdevs:
                            continue
                        m = te & (own['dev'] == dev)
                        if not m.any():
                            continue
                        pred = own['X'][m].astype(np.float64) @ wt + b
                        s, pairs = score_minutes(centres[m], pred, ref)
                        if s:
                            s['train_mean_const_mae'] = float(np.mean([abs(train_const - p[2]) for p in pairs]))
                            s['win_mae'] = float(np.mean([abs(p - (label_minute(c, ref) or np.nan)) for c, p in zip(centres[m], pred)
                                                          if label_minute(c, ref) is not None]))
                            res['folds'].setdefault(dev, {})[fold] = s
                        if fold in ('rest', 'exercise', 'recovery'):
                            pairs_pool[dev] += pairs
                        if key == '|'.join((CANDIDATE['mix'], CANDIDATE['train'], CANDIDATE['labels'])) and dev == 'ring' \
                                and fold in ('rest', 'exercise', 'recovery'):
                            cand_pairs[fold] = pairs
                res['pooled'] = {}
                for dev, pp in pairs_pool.items():
                    s = summarise(pp)
                    if s:
                        res['pooled'][dev] = s
                results['variants'][key] = res
                ex = res['folds'].get('ring', {}).get('exercise')
                print(f'{key:<34} ring exercise: ' + (f"MAE {ex['mae']:.1f} (const {ex['oracle_const_mae']:.1f}) r {ex['r']:.2f} "
                      f"range {ex['est_min']:.0f}-{ex['est_max']:.0f}" if ex else 'n/a'), flush=True)

    # is the candidate following the pulse or the movement? per-minute r with accelerometer activity
    cp = [p for pairs in cand_pairs.values() for p in pairs]
    if len(cp) > 3:
        est = np.array([p[1] for p in cp]); tru = np.array([p[2] for p in cp])
        acc = np.array([accel.get(p[0], np.nan) for p in cp])
        ok = np.isfinite(acc)
        results['candidate_accel_check'] = {
            'minutes': int(ok.sum()),
            'r_estimate_vs_accel_sd': float(np.corrcoef(est[ok], acc[ok])[0, 1]),
            'r_reference_vs_accel_sd': float(np.corrcoef(tru[ok], acc[ok])[0, 1]),
            'r_estimate_vs_reference': float(np.corrcoef(est, tru)[0, 1])}
    ckey = '|'.join((CANDIDATE['mix'], CANDIDATE['train'], CANDIDATE['labels']))
    cex = results['variants'][ckey]['folds'].get('ring', {}).get('exercise')
    results['gate'] = {'candidate': ckey, 'exercise_block': cex, 'passes': gate(cex),
                       'rule': 'held-out exercise block, per-minute: MAE < best constant (oracle median of the 5 reference '
                               'minutes) and r > 0.5'}
    others = [k for k, v in results['variants'].items() if k != ckey and gate(v['folds'].get('ring', {}).get('exercise'))]
    results['gate']['other_variants_passing_not_eligible'] = others
    print('gate:', 'PASS' if results['gate']['passes'] else 'FAIL', '| other variants that would pass (selection-biased, not eligible):', others)

    if results['gate']['passes'] and args.save:
        tr_all = np.ones(len(starts), bool)
        X, y, kinds, cent = assemble(own, stretch, public, CANDIDATE['mix'], TRAIN_DEVS[CANDIDATE['train']], CANDIDATE['labels'], ref, tr_all)
        wt, b, alpha, _ = fit_head(X, y, kinds, cent)
        save_head(HEAD_ID, wt, b, alpha, X, y, kinds, results)
        results['saved'] = HEAD_ID
    else:
        results['saved'] = None
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'results.json'), 'w') as f:
        json.dump(results, f, indent=2, default=float)
    write_tables(results)
    return 0


def save_head(head_id, w, b, alpha, X, y, kinds, results):
    np.savez(os.path.join(HEADS, f'{head_id}.npz'), weights=np.asarray(w).reshape(-1, 1).astype(np.float32),
             bias=np.array([b], dtype=np.float32))
    cv = results['gate']['exercise_block']
    ckey = results['gate']['candidate']
    manifest = {
        'head_id': head_id, 'head_version': '2026-11-24',
        'display_name': 'Heart rate (PaPaGei-S + ridge, wide own range + stretch + public)',
        'base_model_id': 'papagei_s', 'input_dimension': 512, 'output_type': 'regression', 'unit': 'bpm',
        'weights_file': f'{head_id}.npz',
        'task_description': 'Heart rate from a 10 s PPG window at 25 Hz. Trained on one wearer\'s ring and earbud windows '
                            'of the 23 Nov recording (EmbracePlus minute labels, 51-113 bpm), rule-labelled time-stretched '
                            'older ring windows, and PPG-DaLiA / BIDMC windows, with equal total weight per source.',
        'training': {'labelled_windows': int(len(y)), 'own_windows': int((kinds == 'own').sum()),
                     'label_range_bpm': [float(y.min()), float(y.max())], 'ridge_alpha': alpha,
                     'sources': ['own ring 140 + earbuds 139/141 (EmbracePlus minute labels, interpolated)',
                                 'older ring recordings, rules-engine labels, time-stretch', 'PPG-DaLiA', 'BIDMC'],
                     'weighting': 'each added source has total weight equal to the own windows',
                     'final_fit': 'all blocks of the recording (no held-out data left)'},
        'validation': {'method': 'leave-one-time-block-out inside ONE recording (rest / exercise / recovery), '
                                 '30 s guard, all devices removed from training for the held-out block; per-minute median',
                       'candidate_variant': ckey, 'heldout_exercise_block': cv,
                       'pooled_three_blocks': results['variants'][ckey]['pooled'].get('ring'),
                       'leakage_caveat': 'one wearer, one recording, one exercise bout; adjacent blocks are correlated and '
                                         'the earbuds share the ring\'s heart-rate trajectory; the exercise test has 5 minute labels'},
        'validation_status': 'pilot: single recording, 5 held-out exercise minutes; the scores are cross-validated, not the training fit',
    }
    with open(os.path.join(HEADS, f'{head_id}.json'), 'w', encoding='utf8') as f:
        json.dump(manifest, f, indent=2)
    print('saved head', head_id)


def fmt(s):
    if not s:
        return 'n/a | | | | |'
    r = 'nan' if not np.isfinite(s['r']) else f"{s['r']:.2f}"
    return f"{s['mae']:.1f} | {s['bias']:+.1f} | {r} | {s['est_min']:.0f}-{s['est_max']:.0f} | {s['oracle_const_mae']:.1f}"


def write_tables(res):
    L = ['# Wide-range own-data heads (generated by scripts/train_head_wide.py)', '',
         f"Reference range in the recording: {res['ref_range'][0]:.0f}-{res['ref_range'][1]:.0f} bpm. "
         f"Windows: {res['n_own_windows']}; stretched older windows {res['n_stretch']}; public windows {res['n_public']}.", '',
         'Columns: MAE (bpm) | bias | r | estimate range | best-constant MAE (oracle median of the held-out reference minutes). '
         'Per minute, >= 3 windows per minute.', '']
    for dev in DEVICES:
        L += [f'## Test device: {dev}', '']
        for fold in ('rest', 'exercise', 'recovery', 'pooled', 'ex_only->rest', 'ex_only->recovery'):
            L += [f'### {fold}', '', '| mix | training devices | labels | n min | MAE | bias | r | est range | best const |',
                  '|---|---|---|---|---|---|---|---|---|']
            for key, v in res['variants'].items():
                mix, tr, lab = key.split('|')
                s = v['pooled'].get(dev) if fold == 'pooled' else v['folds'].get(dev, {}).get(fold)
                if s:
                    L.append(f"| {mix} | {tr} | {lab} | {s['minutes']} | {fmt(s)} |")
            L.append('')
    with open(os.path.join(OUT, 'tables.md'), 'w', encoding='utf8') as f:
        f.write('\n'.join(L))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('prepare')
    p.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    p.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    p.add_argument('--dalia-dir', required=True)
    p.add_argument('--bidmc-dir', required=True)
    p.add_argument('--procs', type=int, default=3)
    e = sub.add_parser('evaluate')
    e.add_argument('--reference', required=True)
    e.add_argument('--save', action='store_true', help=f'write {HEAD_ID} if the gate passes')
    args = ap.parse_args()
    return prepare(args) if args.cmd == 'prepare' else evaluate(args)


if __name__ == '__main__':
    sys.exit(main())
