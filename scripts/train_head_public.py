#!/usr/bin/env python3
"""Train PaPaGei heart-rate heads on public PPG datasets with true ECG-derived heart rate,
and test them on a ring recording the heads never saw (scored against the EmbracePlus).

Why: the heads trained on the ring's own rule-labelled windows (train_head_from_rules.py)
did not track the 23 Nov exercise recording. Their labels come from the rules engine and
the ring mostly rests, so the head sees little range. Public datasets give a wide range of
true heart rates:

  PPG-DaLiA (UCI): 15 subjects, wrist BVP at 64 Hz, 8 activities (stairs, cycling, ...),
      ECG-derived HR every 2 s over 8 s windows.
  BIDMC (PhysioNet): 53 ICU recordings of 8 min, PPG (PLETH) at 125 Hz, HR (1 Hz numerics).

What is done about rate: the ring's green channel is 25 Hz, the public sources are 64 and
125 Hz. Each public signal is resampled to 25 Hz with an anti-aliasing polyphase filter, cut
into 250-sample (10 s) windows, and sent to the PaPaGei worker with sample_rate=25, exactly
as the ring's windows are. The worker then applies its own band-pass and 125 Hz resampling.
So the head sees public signals with the same bandwidth (< 12.5 Hz) as the ring's.
No time-stretch augmentation is used on public data (their labels are true).

Downloaded files are untrusted: the DaLiA pickles are read with a restricted unpickler that
only allows numpy array reconstruction; BIDMC is read from its CSV files.

    python -I scripts/train_head_public.py embed  --dalia-dir ... --bidmc-dir ...
    python scripts/train_head_public.py evaluate --test-session 140 --reference <pulse-rate.csv>
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import pickle
import statistics as st
import subprocess
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from fractions import Fraction

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
HEADS = os.path.join(ROOT, 'ml-inference-server', 'heads')
OUT = os.path.join(ROOT, 'outputs', 'headtrain_public')
CACHE = os.path.join(os.path.expanduser('~'), 'data_public', 'cache')
TARGET_HZ = 25.0
WINDOW_S = 10
STEP_S = 5
ACTIVITIES = {0: 'transient', 1: 'sitting', 2: 'stairs', 3: 'table soccer', 4: 'cycling',
              5: 'driving', 6: 'lunch break', 7: 'walking', 8: 'working'}


# ---------------------------------------------------------------- pure functions

def resample_signal(x, fs_in: float, fs_out: float = TARGET_HZ):
    """Anti-aliased polyphase resampling of a 1-D signal from fs_in to fs_out."""
    from scipy.signal import resample_poly
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    if abs(fs_in - fs_out) < 1e-9:
        return x
    f = Fraction(fs_out / fs_in).limit_denominator(1000)
    return resample_poly(x, f.numerator, f.denominator)


def window_starts(n_samples: int, fs: float, window_s: float = WINDOW_S, step_s: float = STEP_S):
    """Start indices of every complete window_s window, stepping step_s."""
    w, s = int(round(window_s * fs)), int(round(step_s * fs))
    if n_samples < w:
        return np.array([], dtype=int)
    return np.arange(0, n_samples - w + 1, s)


def cut_windows(x, fs: float, window_s: float = WINDOW_S, step_s: float = STEP_S):
    """(starts_in_seconds, windows[n, window_s*fs]) from a signal sampled at fs."""
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    w = int(round(window_s * fs))
    idx = window_starts(len(x), fs, window_s, step_s)
    if len(idx) == 0:
        return np.array([]), np.zeros((0, w))
    return idx / fs, np.stack([x[i:i + w] for i in idx])


def mean_reference(label_centres_s, labels, t0: float, t1: float):
    """Mean of the reference values whose time centre lies in [t0, t1); NaN if none.

    DaLiA labels are 8 s windows every 2 s, label i centred at 2 i + 4 s. BIDMC numerics are
    1 Hz, sample i at i s."""
    c = np.asarray(label_centres_s, dtype=float)
    v = np.asarray(labels, dtype=float)
    m = (c >= t0) & (c < t1) & np.isfinite(v)
    return float(v[m].mean()) if m.any() else float('nan')


def modal_value(times_s, values, t0: float, t1: float):
    """Most frequent value among samples with time in [t0, t1); None if none."""
    t = np.asarray(times_s, dtype=float)
    v = np.asarray(values)
    m = (t >= t0) & (t < t1)
    if not m.any():
        return None
    u, c = np.unique(v[m], return_counts=True)
    return u[np.argmax(c)]


def usable(window) -> bool:
    """A window the PaPaGei worker would accept: finite and not flat."""
    w = np.asarray(window, dtype=float)
    return bool(np.isfinite(w).all() and w.std() > 1e-6)


def ridge(X, y, alpha):
    """Ridge on standardised features, folded back into raw-embedding weights and bias
    (same as train_head_from_rules.ridge)."""
    mean, std = X.mean(0), X.std(0) + 1e-9
    Z = (X - mean) / std
    ym = y.mean()
    w = np.linalg.solve(Z.T @ Z + alpha * np.eye(Z.shape[1]), Z.T @ (y - ym))
    weights = w / std
    return weights, ym - mean @ weights


def mae_r(pred, true):
    pred, true = np.asarray(pred, float), np.asarray(true, float)
    r = float(np.corrcoef(pred, true)[0, 1]) if len(pred) > 2 and pred.std() > 0 and true.std() > 0 else float('nan')
    return float(np.abs(pred - true).mean()), r


# ---------------------------------------------------------------- safe loaders

class NumpyOnlyUnpickler(pickle.Unpickler):
    """Allows only what a plain dict/list/str/number/numpy-array pickle needs."""
    ALLOWED = {
        ('numpy.core.multiarray', '_reconstruct'), ('numpy._core.multiarray', '_reconstruct'),
        ('numpy.core.multiarray', 'scalar'), ('numpy._core.multiarray', 'scalar'),
        ('numpy', 'ndarray'), ('numpy', 'dtype'),
        ('collections', 'OrderedDict'),
        ('builtins', 'dict'), ('builtins', 'list'), ('builtins', 'tuple'), ('builtins', 'str'),
        ('builtins', 'int'), ('builtins', 'float'), ('builtins', 'set'),
        ('__builtin__', 'set'), ('_codecs', 'encode'),
    }

    def find_class(self, module, name):
        if (module, name) in self.ALLOWED:
            if (module, name) == ('_codecs', 'encode'):
                return _safe_encode
            return super().find_class(module, name)
        raise pickle.UnpicklingError(f'blocked global {module}.{name}')


def _safe_encode(s, enc='latin1'):
    if enc != 'latin1':
        raise pickle.UnpicklingError('only latin1 encode allowed')
    return s.encode('latin1')


def load_dalia_pickle(path):
    """One PPG-DaLiA subject: wrist BVP (64 Hz), HR labels (2 s), activity (4 Hz)."""
    with open(path, 'rb') as f:
        data = NumpyOnlyUnpickler(f, encoding='latin1').load()
    bvp = np.asarray(data['signal']['wrist']['BVP'], dtype=float).reshape(-1)
    return {'bvp': bvp, 'hr': np.asarray(data['label'], dtype=float).reshape(-1),
            'activity': np.asarray(data['activity']).reshape(-1)}


def dalia_windows(rec):
    """Windows for one DaLiA subject: (start_s, windows@25Hz, hr, activity)."""
    x = resample_signal(rec['bvp'], 64.0)
    starts, wins = cut_windows(x, TARGET_HZ)
    centres = np.arange(len(rec['hr'])) * 2.0 + 4.0
    act_t = np.arange(len(rec['activity'])) / 4.0
    keep, hr, act = [], [], []
    for i, s in enumerate(starts):
        h = mean_reference(centres, rec['hr'], s, s + WINDOW_S)
        a = modal_value(act_t, rec['activity'], s, s + WINDOW_S)
        if np.isfinite(h) and usable(wins[i]):
            keep.append(i); hr.append(h); act.append(-1 if a is None else int(a))
    return starts[keep], wins[keep], np.array(hr), np.array(act)


def load_bidmc_pair(signals_csv, numerics_csv):
    """PLETH (125 Hz) and HR (1 Hz) of one BIDMC recording from its CSV files."""
    def table(path):
        with open(path, newline='', encoding='utf8') as f:
            rows = list(csv.reader(f))
        head = [h.strip() for h in rows[0]]
        return head, np.array([[float(c) if c.strip() else np.nan for c in r] for r in rows[1:]])
    h, s = table(signals_csv)
    pleth = s[:, h.index('PLETH')]
    h2, n = table(numerics_csv)
    hr = n[:, h2.index('HR')]
    return pleth, hr


def bidmc_windows(pleth, hr):
    x = resample_signal(pleth, 125.0)
    starts, wins = cut_windows(x, TARGET_HZ)
    centres = np.arange(len(hr), dtype=float)
    keep, ys = [], []
    for i, s in enumerate(starts):
        h = mean_reference(centres, hr, s, s + WINDOW_S)
        if np.isfinite(h) and usable(wins[i]):
            keep.append(i); ys.append(h)
    return starts[keep], wins[keep], np.array(ys)


# ---------------------------------------------------------------- embeddings

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


def compose_base(env, compose_file):
    return ['docker', 'compose', '--env-file', env, '-f', compose_file]


def embed_windows(base, windows, procs=3, chunk=400):
    """PaPaGei-S embeddings for a (n, 250) array at 25 Hz via the worker container.
    Returns (n, 512) float32 with NaN rows where the worker refused a window."""
    n = len(windows)
    out = np.full((n, 512), np.nan, dtype=np.float32)

    def run(lo):
        hi = min(lo + chunk, n)
        payload = ''.join(json.dumps({'k': i, 'x': np.round(windows[i], 5).tolist()}) + '\n' for i in range(lo, hi))
        cmd = base + ['exec', '-T', 'papagei', 'python', '-c', EMBED_SCRIPT]
        res = subprocess.run(cmd, input=payload, capture_output=True, text=True, check=True).stdout
        got = []
        for line in res.splitlines():
            try:
                it = json.loads(line)
            except ValueError:
                continue
            if it.get('e'):
                got.append((it['k'], np.asarray(it['e'], dtype=np.float32)))
        return got

    with ThreadPoolExecutor(max_workers=procs) as ex:
        for got in ex.map(run, range(0, n, chunk)):
            for k, e in got:
                out[k] = e
    return out


def cached_embeddings(name, windows, base, procs):
    """Embeddings cached per source in CACHE (outside the repo)."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f'emb_{name}.npy')
    if os.path.exists(path):
        e = np.load(path)
        if len(e) == len(windows):
            return e
    e = embed_windows(base, windows, procs=procs)
    np.save(path, e)
    return e


# ---------------------------------------------------------------- stage 1: public data

def build_public(dalia_dir, bidmc_dir, base, procs, max_dalia_subjects=None):
    """Windows, labels, groups, activities and embeddings of public data, cached."""
    import time
    rows = {'X': [], 'y': [], 'group': [], 'src': [], 'act': []}
    timings = {}
    for sdir in sorted(d for d in os.listdir(dalia_dir) if d.startswith('S')):
        if max_dalia_subjects and len(rows['group']) and len({g for g in rows['group'] if g.startswith('dalia')}) >= max_dalia_subjects:
            break
        pkl = os.path.join(dalia_dir, sdir, f'{sdir}.pkl')
        if not os.path.exists(pkl):
            continue
        t = time.time()
        _, w, hr, act = dalia_windows(load_dalia_pickle(pkl))
        e = cached_embeddings(f'dalia_{sdir}', w, base, procs)
        ok = np.isfinite(e).all(1)
        timings[f'dalia_{sdir}'] = {'windows': int(len(w)), 'embedded': int(ok.sum()), 'seconds': round(time.time() - t, 1)}
        print('dalia', sdir, timings[f'dalia_{sdir}'], flush=True)
        rows['X'].append(e[ok]); rows['y'].append(hr[ok]); rows['act'].append(act[ok])
        rows['group'] += [f'dalia_{sdir}'] * int(ok.sum()); rows['src'] += ['dalia'] * int(ok.sum())
    if bidmc_dir and os.path.isdir(bidmc_dir):
        for fn in sorted(os.listdir(bidmc_dir)):
            if not fn.endswith('_Signals.csv'):
                continue
            stem = fn[:-len('_Signals.csv')]
            num = os.path.join(bidmc_dir, stem + '_Numerics.csv')
            if not os.path.exists(num):
                continue
            t = time.time()
            pleth, hr = load_bidmc_pair(os.path.join(bidmc_dir, fn), num)
            _, w, y = bidmc_windows(pleth, hr)
            e = cached_embeddings(f'bidmc_{stem}', w, base, procs)
            ok = np.isfinite(e).all(1)
            timings[f'bidmc_{stem}'] = {'windows': int(len(w)), 'embedded': int(ok.sum()), 'seconds': round(time.time() - t, 1)}
            print('bidmc', stem, timings[f'bidmc_{stem}'], flush=True)
            rows['X'].append(e[ok]); rows['y'].append(y[ok]); rows['act'].append(np.full(int(ok.sum()), -2))
            rows['group'] += [f'bidmc_{stem}'] * int(ok.sum()); rows['src'] += ['bidmc'] * int(ok.sum())
    return (np.concatenate(rows['X']), np.concatenate(rows['y']), np.array(rows['group']),
            np.array(rows['src']), np.concatenate(rows['act']), timings)


# ---------------------------------------------------------------- own ring data

def own_data(base_cmd, test_session, procs):
    """Rule-labelled ring windows from the local database (unstretched), embedded and cached,
    plus the held-out session's windows. Reuses train_head_from_rules."""
    sys.path.insert(0, HERE)
    import train_head_from_rules as r
    xs, ys, gs, keys = [], [], [], []
    for sid in r.ring_sessions(base_cmd):
        if sid == test_session:
            continue
        labels = r.rule_windows(base_cmd, sid)
        if not labels:
            continue
        series = r.green_series(base_cmd, sid)
        for end, hr, _ in labels:
            seg = r.stretched(series, end)
            for k, x in seg:
                if k == 1.0:
                    xs.append(x); ys.append(hr); gs.append(f'own_{sid}'); keys.append(f'{sid}:{end}')
    W = np.array(xs, dtype=float)
    e = embed_windows(base_cmd, W, procs=procs) if len(W) else np.zeros((0, 512), np.float32)
    ok = np.isfinite(e).all(1) if len(W) else np.zeros(0, bool)
    test_series = r.green_series(base_cmd, test_session)
    t0, t1 = min(q[0] for q in test_series), max(q[0] for q in test_series)
    tw, tt = [], []
    for end in range(t0 + r.WINDOW_MS, t1, r.STEP_MS):
        x = r.cut(test_series, end)
        if x:
            tw.append(x[-250:] if len(x) >= 250 else x); tt.append(end)
    tW = [w for w in tw if len(w) == 250]
    tT = [t for w, t in zip(tw, tt) if len(w) == 250]
    te = embed_windows(base_cmd, np.array(tW, dtype=float), procs=procs)
    tok = np.isfinite(te).all(1)
    return {'X': e[ok], 'y': np.array(ys)[ok], 'group': np.array(gs)[ok],
            'test_X': te[tok], 'test_t': np.array(tT)[tok], 't0': t0, 't1': t1, 'r': r}


# ---------------------------------------------------------------- evaluation

def loso(X, y, g, alphas, eval_mask=None):
    """Leave-one-group-out predictions for each alpha; returns {alpha: pred array}.
    Groups that are not in eval_mask are always kept in training and never held out."""
    out = {a: np.full(len(y), np.nan) for a in alphas}
    held_groups = np.unique(g[eval_mask] if eval_mask is not None else g)
    for h in held_groups:
        tr, te = g != h, g == h
        for a in alphas:
            w, b = ridge(X[tr], y[tr], a)
            out[a][te] = X[te] @ w + b
    return out


def constant_loso(y, g, eval_mask):
    pred = np.full(len(y), np.nan)
    for h in np.unique(g[eval_mask]):
        pred[g == h] = y[g != h].mean()
    return pred


def evaluate(args):
    base = compose_base(args.env, args.compose)
    X, y, g, src, act, timings = build_public(args.dalia_dir, args.bidmc_dir, base, args.procs, args.max_dalia_subjects)
    print(f'public windows: {len(y)}; dalia {int((src=="dalia").sum())}, bidmc {int((src=="bidmc").sum())}; '
          f'HR {y.min():.0f}-{y.max():.0f}', flush=True)
    own = own_data(base, args.test_session, args.procs)
    r = own['r']
    ref = r.reference_minutes(args.reference, own['t0'] - 60000, own['t1'] + 60000)
    alphas = (1, 3, 10, 30, 100, 300, 1000, 3000, 10000)
    is_dalia, is_bidmc = src == 'dalia', src == 'bidmc'

    variants = {'dalia': is_dalia, 'dalia+bidmc': is_dalia | is_bidmc}
    results = {'timings': timings, 'variants': {}, 'n_public': {'dalia': int(is_dalia.sum()), 'bidmc': int(is_bidmc.sum())},
               'n_own': int(len(own['y']))}
    heads = {}
    for name, mask in variants.items():
        Xv, yv, gv, av, sv = X[mask], y[mask], g[mask], act[mask], src[mask]
        res = {'alphas': {}}
        preds = loso(Xv, yv, gv, alphas, eval_mask=np.ones(len(yv), bool))
        # alpha chosen on DaLiA-subject LOSO MAE (the target-like domain) for both variants
        for a in alphas:
            m = sv == 'dalia'
            res['alphas'][a] = mae_r(preds[a][m], yv[m])
        best_a = min(alphas, key=lambda a: res['alphas'][a][0])
        res['alpha'] = best_a
        p = preds[best_a]
        cpred = constant_loso(yv, gv, np.ones(len(yv), bool))
        for dom in ('dalia', 'bidmc'):
            m = sv == dom
            if m.any():
                res[dom] = {'mae': mae_r(p[m], yv[m])[0], 'r': mae_r(p[m], yv[m])[1],
                            'constant_mae': float(np.abs(cpred[m] - yv[m]).mean()), 'n': int(m.sum())}
        res['per_activity'] = {}
        m0 = sv == 'dalia'
        for a_id in sorted(set(av[m0])):
            m = m0 & (av == a_id)
            res['per_activity'][ACTIVITIES.get(int(a_id), str(a_id))] = {
                'n': int(m.sum()), 'hr_mean': float(yv[m].mean()), 'mae': float(np.abs(p[m] - yv[m]).mean()),
                'constant_mae': float(np.abs(cpred[m] - yv[m]).mean()), 'r': mae_r(p[m], yv[m])[1]}
        heads[name] = (Xv, yv, best_a, gv)
        results['variants'][name] = res

    # variant with the user's own rule-labelled windows (unstretched), LOSO over public+own recordings
    mask = is_dalia | is_bidmc
    X3 = np.concatenate([X[mask], own['X']]); y3 = np.concatenate([y[mask], own['y']])
    g3 = np.concatenate([g[mask], own['group']]); s3 = np.concatenate([src[mask], np.full(len(own['y']), 'own')])
    a3 = np.concatenate([act[mask], np.full(len(own['y']), -3)])
    res = {'alphas': {}}
    preds = loso(X3, y3, g3, alphas, eval_mask=(s3 == 'dalia'))
    for a in alphas:
        m = s3 == 'dalia'
        res['alphas'][a] = mae_r(preds[a][m], y3[m])
    best_a = min(alphas, key=lambda a: res['alphas'][a][0])
    res['alpha'] = best_a
    p = preds[best_a]; cpred = constant_loso(y3, g3, s3 == 'dalia')
    m = s3 == 'dalia'
    res['dalia'] = {'mae': mae_r(p[m], y3[m])[0], 'r': mae_r(p[m], y3[m])[1],
                    'constant_mae': float(np.abs(cpred[m] - y3[m]).mean()), 'n': int(m.sum())}
    res['per_activity'] = {}
    for a_id in sorted(set(a3[m])):
        mm = m & (a3 == a_id)
        res['per_activity'][ACTIVITIES.get(int(a_id), str(a_id))] = {
            'n': int(mm.sum()), 'hr_mean': float(y3[mm].mean()), 'mae': float(np.abs(p[mm] - y3[mm]).mean()),
            'constant_mae': float(np.abs(cpred[mm] - y3[mm]).mean()), 'r': mae_r(p[mm], y3[mm])[1]}
    heads['dalia+bidmc+own'] = (X3, y3, best_a, g3)
    results['variants']['dalia+bidmc+own'] = res

    # held-out test on the ring recording vs EmbracePlus
    tX, tt = own['test_X'], [int(t) for t in own['test_t']]
    results['heldout'] = {}
    wb = {}
    for name, (Xv, yv, a, gv) in heads.items():
        w, b = ridge(Xv, yv, a)
        pred = tX @ w + b
        s = r.score(list(zip(tt, pred)), ref)
        s['alpha'] = a
        results['heldout'][name] = s
        wb[name] = (w, b)
        print(f'held-out {name:<18} {json.dumps(s)}', flush=True)
    results['heldout_context'] = {'test_session': args.test_session, 'n_test_windows': int(len(tt)),
                                  'ref_range': [float(min(ref.values())), float(max(ref.values()))]}

    os.makedirs(OUT, exist_ok=True)
    clean = json.loads(json.dumps(results, default=lambda o: float(o)))
    with open(os.path.join(OUT, 'results.json'), 'w') as f:
        json.dump(clean, f, indent=2)

    if args.save:
        best = min((n for n in results['heldout'] if results['heldout'][n]), key=lambda n: results['heldout'][n]['mae'])
        s = results['heldout'][best]
        passes = s['mae'] < s['best_constant_mae'] and s['correlation'] > 0.5
        print(f'gate: best variant {best}: MAE {s["mae"]:.2f} vs constant {s["best_constant_mae"]:.2f}, r {s["correlation"]:.2f} -> '
              f'{"PASS" if passes else "FAIL, nothing saved"}')
        if passes:
            save_head(args.save, best, s, results, heads[best], wb[best], args.test_session)
    return 0


def save_head(head_id, variant, s, results, head_data, wb, test_session):
    Xv, yv, a, gv = head_data
    w, b = wb
    np.savez(os.path.join(HEADS, f'{head_id}.npz'), weights=np.asarray(w).reshape(-1, 1).astype(np.float32),
             bias=np.array([b], dtype=np.float32))
    manifest = {
        'head_id': head_id, 'head_version': '2026-11-23',
        'display_name': f'Heart rate (PaPaGei-S + ridge, public PPG: {variant})',
        'base_model_id': 'papagei_s', 'input_dimension': 512, 'output_type': 'regression', 'unit': 'bpm',
        'weights_file': f'{head_id}.npz',
        'task_description': 'Heart rate from a 10 s PPG window at 25 Hz, trained on PPG-DaLiA wrist BVP and '
                            'BIDMC PPG with ECG-derived heart-rate labels. Public signals were resampled to 25 Hz.',
        'training': {'variant': variant, 'labelled_windows': int(len(yv)), 'groups': int(len(set(gv))),
                     'label_range_bpm': [float(yv.min()), float(yv.max())], 'ridge_alpha': int(a),
                     'labels': 'ECG-derived heart rate, mean over the window', 'augmentation': 'none',
                     'sources': ['PPG-DaLiA (UCI) wrist BVP 64 Hz', 'BIDMC (PhysioNet) PLETH 125 Hz']},
        'validation': {'leave_one_subject_out_dalia': results['variants'][variant].get('dalia'),
                       'heldout': f'session {test_session} vs EmbracePlus per-minute median',
                       **{k: v for k, v in s.items() if k != 'range'}},
        'validation_status': 'pilot: one held-out ring recording, 25 per-minute reference points',
    }
    with open(os.path.join(HEADS, f'{head_id}.json'), 'w', encoding='utf8') as f:
        json.dump(manifest, f, indent=2)
    print('saved head', head_id)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--dalia-dir', required=True, help='directory holding S1..S15 folders with S*.pkl')
    ap.add_argument('--bidmc-dir', help='directory holding bidmc_NN_Signals.csv / _Numerics.csv')
    ap.add_argument('--test-session', type=int, default=140)
    ap.add_argument('--reference', required=True)
    ap.add_argument('--procs', type=int, default=3)
    ap.add_argument('--max-dalia-subjects', type=int)
    ap.add_argument('--save', help='head id; saved only if it beats the best constant with r > 0.5')
    args = ap.parse_args()
    return evaluate(args)


if __name__ == '__main__':
    sys.exit(main())
