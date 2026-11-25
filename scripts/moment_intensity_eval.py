#!/usr/bin/env python3
"""Movement-intensity evaluation of frozen MOMENT embeddings against handcrafted motion features.

Input: the .npz of scripts/moment_windows.py (version 2). Target: still / light / vigorous (the
mapping is declared in scripts/moment_windows.py and docs/IMU_MODEL.md before results were looked
at), and the 2-class variant still vs active (light + vigorous).

Two evaluation schemes, same head, same folds for every feature set:
  loro  leave-one-recording-out, pooled out-of-fold predictions;
  lobo  leave-one-bout-out inside one long recording (default: experiment 65, ring session 147).
        A bout is a run of abutting annotations of one intensity class. The held-out bout's windows
        are the test set; training is every window of every other recording plus the same
        recording's windows that do not overlap [bout start - 10 s, bout end + 10 s].

Head: class-balanced L2 multinomial logistic regression (scripts/train_activity_head.fit),
lambda = 10 fixed a priori. A second variant, `|inner`, chooses lambda from GRID by 3-fold
grouped (by bout) cross-validation INSIDE the training fold only. Nothing is tuned on a held-out
fold. Gate (docs/IMU_MODEL.md): MOMENT is kept only if its best variant beats `motion` by >= 0.05
macro-F1 in lobo AND beats the majority class on balanced accuracy in loro.

    python scripts/moment_intensity_eval.py --windows <scratch>/imu_windows_v2.npz \
        --json outputs/moment_v2/results.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import train_activity_head as th  # noqa: E402

LAMBDA = 10.0
GRID = (1.0, 10.0, 100.0, 1000.0)
GUARD_MS = 10_000
WINDOW_MS = 10_000
CLASSES3 = ['still', 'light', 'vigorous']
CLASSES2 = ['still', 'active']
GATE_MARGIN = 0.05
STATS = ('mag_mean', 'mag_std', 'dmag_std', 'x_std', 'y_std', 'z_std', 'gx', 'gy', 'gz')
EXTRA = ('sampen', 'dyn_std', 'mad_mag', 'band_lo', 'band_mid', 'band_hi', 'dom_freq')
INNER_SETS = ('motion', 'moment_mag', 'moment_dyn', 'moment_mag+motion', 'moment_mag+amp', 'moment_dyn+motion')


def feature_blocks(d):
    def st(name):
        return d['stats'][:, STATS.index(name)].astype(np.float64)

    def ex(name):
        return d['extra'][:, EXTRA.index(name)].astype(np.float64)

    def mom(c):
        return d['emb'][:, ('x', 'y', 'z', 'mag').index(c), :].astype(np.float64)

    basic = np.stack([np.log1p(st(n)) for n in ('mag_std', 'dmag_std', 'x_std', 'y_std', 'z_std')], axis=1)
    motion = np.concatenate([basic, np.stack([ex('sampen'), np.log1p(ex('dyn_std')), np.log1p(ex('mad_mag')),
                                              ex('band_lo'), ex('band_mid'), ex('band_hi'), ex('dom_freq')],
                                             axis=1)], axis=1)
    amp = np.stack([np.log1p(st('mag_std')), np.log1p(ex('dyn_std'))], axis=1)   # what instance norm removes
    dyn = d['emb_dyn'].astype(np.float64)
    return {
        'motion_basic': basic,
        'motion': motion,
        'moment_mag': mom('mag'),
        'moment_xyz': np.concatenate([mom('x'), mom('y'), mom('z')], axis=1),
        'moment_dyn': dyn,
        'moment_mag+amp': np.concatenate([mom('mag'), amp], axis=1),
        'moment_dyn+amp': np.concatenate([dyn, amp], axis=1),
        'moment_mag+motion': np.concatenate([mom('mag'), motion], axis=1),
        'moment_dyn+motion': np.concatenate([dyn, motion], axis=1),
    }


def inner_lambda(X, y, bouts, k, seed=0):
    """lambda from GRID by 3-fold CV grouped by bout, inside the training data only (macro-F1)."""
    ub = np.unique(bouts)
    if len(ub) < 3 or len(np.unique(y)) < 2:
        return LAMBDA
    rng = np.random.RandomState(seed)
    fold_of = dict(zip(ub, rng.permutation(len(ub)) % 3))
    f = np.array([fold_of[b] for b in bouts])
    best, best_f1 = LAMBDA, -1.0
    for lam in GRID:
        pred = np.full(len(y), -1)
        for i in range(3):
            tr, te = f != i, f == i
            if len(np.unique(y[tr])) < 2:
                pred[te] = np.bincount(y[tr], minlength=k).argmax()
                continue
            W, b = th.fit(X[tr], y[tr], k, lam)
            pred[te] = th.predict(X[te], W, b).argmax(axis=1)
        f1 = th.macro_f1(y, pred, k)
        if f1 > best_f1 + 1e-9:
            best, best_f1 = lam, f1
    return best


def fit_predict(Xtr, ytr, btr, Xte, k, lam):
    if lam == 'inner':
        lam = inner_lambda(Xtr, ytr, btr, k)
    if len(np.unique(ytr)) < 2:
        return np.full(len(Xte), np.bincount(ytr, minlength=k).argmax()), lam
    W, b = th.fit(Xtr, ytr, k, lam)
    return th.predict(Xte, W, b).argmax(axis=1), lam


def run_loro(X, y, groups, bouts, k, lam):
    pred = np.zeros(len(y), dtype=int)
    for g in np.unique(groups):
        te = groups == g
        pred[te], _ = fit_predict(X[~te], y[~te], bouts[~te], X[te], k, lam)
    return pred


def lobo_folds(groups, bouts, starts, ends, rec):
    """Yield (test mask, train mask) per bout of recording `rec`: train = every other recording plus the
    recording's windows outside [bout start - guard, bout end + guard]."""
    sel = groups == rec
    for b in np.unique(bouts[sel]):
        te = sel & (bouts == b)
        if te.sum() == 0:
            continue
        lo, hi = starts[te].min() - GUARD_MS, ends[te].max() + GUARD_MS
        near = sel & (ends > lo) & (starts < hi)
        yield te, ~near


def run_lobo(X, y, groups, bouts, starts, ends, k, lam, rec):
    pred = np.full(len(y), -1, dtype=int)
    chosen = []
    for te, tr in lobo_folds(groups, bouts, starts, ends, rec):
        pred[te], used = fit_predict(X[tr], y[tr], bouts[tr], X[te], k, lam)
        chosen.append(used)
    return pred, chosen


def metrics(y, pred, k):
    return {'accuracy': round(float((pred == y).mean()), 3),
            'balanced_accuracy': round(th.balanced_acc(y, pred, k), 3),
            'macro_f1': round(th.macro_f1(y, pred, k), 3),
            'confusion': th.confusion(y, pred, k).tolist(), 'n': int(len(y))}


def per_bout(y, pred, bouts, classes):
    out = {}
    for b in np.unique(bouts):
        m = bouts == b
        out[str(b)] = {'class': classes[int(np.bincount(y[m]).argmax())], 'windows': int(m.sum()),
                       'recall': round(float((pred[m] == y[m]).mean()), 3)}
    return out


def cluster_bootstrap_diff(y, p_a, p_b, bouts, k, n=1000, seed=0):
    """95% interval of macro-F1(a) - macro-F1(b), resampling whole bouts (windows of a bout are correlated)."""
    rng = np.random.RandomState(seed)
    ub = np.unique(bouts)
    idx = {b: np.flatnonzero(bouts == b) for b in ub}
    diffs = []
    for _ in range(n):
        take = np.concatenate([idx[b] for b in rng.choice(ub, len(ub))])
        diffs.append(th.macro_f1(y[take], p_a[take], k) - th.macro_f1(y[take], p_b[take], k))
    return [round(float(np.percentile(diffs, 2.5)), 3), round(float(np.percentile(diffs, 97.5)), 3)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--windows', required=True)
    ap.add_argument('--device', default='OpenRing')
    ap.add_argument('--lobo-recording', type=int, default=65)
    ap.add_argument('--json')
    ap.add_argument('--skip-inner', action='store_true')
    args = ap.parse_args()

    z = np.load(args.windows, allow_pickle=False)
    d = {k: z[k] for k in z.files if k != 'meta'}
    keep = d['device'] == args.device
    d = {k: v[keep] for k, v in d.items()}
    ends = d['end_ms'].astype(np.int64)
    starts = ends - WINDOW_MS
    groups = d['experiment'].astype(int)
    bouts = d['bout'].astype(str)
    blocks = feature_blocks(d)
    out = {'device': args.device, 'lambda': LAMBDA, 'grid': list(GRID), 'lobo_recording': args.lobo_recording}

    out['counts_per_recording'] = {
        str(g): {c: int(((groups == g) & (d['intensity'] == c)).sum()) for c in CLASSES3} for g in np.unique(groups)}
    out['activity_counts'] = {}
    for g in np.unique(groups):
        names, n = np.unique(d['activity'][groups == g], return_counts=True)
        out['activity_counts'][str(g)] = dict(zip(names.tolist(), map(int, n)))

    sel = groups == args.lobo_recording
    for task, classes in (('3class', CLASSES3), ('2class', CLASSES2)):
        k = len(classes)
        lab = d['intensity'].astype(str)
        if task == '2class':
            lab = np.where(lab == 'still', 'still', 'active')
        y = np.array([classes.index(s) for s in lab])
        res = {'classes': classes, 'windows': int(len(y)), 'loro': {}, 'lobo': {}, 'per_bout': {},
               'per_recording': {}, 'lambda_chosen': {}}
        preds = {'loro': {}, 'lobo': {}}
        variants = [(n, LAMBDA) for n in blocks]
        if not args.skip_inner:
            variants += [(n + '|inner', 'inner') for n in INNER_SETS]
        maj = np.zeros(len(y), dtype=int)
        for g in np.unique(groups):
            te = groups == g
            maj[te] = np.bincount(y[~te], minlength=k).argmax()
        majb = np.full(len(y), -1)
        for te, tr in lobo_folds(groups, bouts, starts, ends, args.lobo_recording):
            majb[te] = np.bincount(y[tr], minlength=k).argmax()
        preds['loro']['majority'], preds['lobo']['majority'] = maj, majb
        for name, lam in variants:
            X = blocks[name.split('|')[0]]
            preds['loro'][name] = run_loro(X, y, groups, bouts, k, lam)
            preds['lobo'][name], chosen = run_lobo(X, y, groups, bouts, starts, ends, k, lam, args.lobo_recording)
            if lam == 'inner':
                res['lambda_chosen'][name] = {str(v): chosen.count(v) for v in sorted(set(chosen))}
            print(task, name, 'loro F1 %.3f  lobo F1 %.3f' % (
                th.macro_f1(y, preds['loro'][name], k), th.macro_f1(y[sel], preds['lobo'][name][sel], k)), flush=True)
        for scheme in ('loro', 'lobo'):
            m = np.ones(len(y), dtype=bool) if scheme == 'loro' else sel
            res[scheme] = {n: metrics(y[m], p[m], k) for n, p in preds[scheme].items()}
        res['per_bout'] = {n: per_bout(y[sel], p[sel], bouts[sel], classes) for n, p in preds['lobo'].items()}
        for g in np.unique(groups):
            m = groups == g
            res['per_recording'][str(g)] = {
                'majority_class': classes[int(np.bincount(y[m]).argmax())], 'windows': int(m.sum()),
                'recall_by_features': {n: round(float((p[m] == y[m]).mean()), 3) for n, p in preds['loro'].items()}}
        cand = [n for n in preds['lobo'] if n.startswith('moment') and '|' not in n]
        f1s = {n: th.macro_f1(y[sel], preds['lobo'][n][sel], k) for n in cand}
        best = max(f1s, key=f1s.get)
        g = {'best_moment_variant_lobo': best, 'best_f1': round(f1s[best], 3),
             'motion_f1': round(th.macro_f1(y[sel], preds['lobo']['motion'][sel], k), 3),
             'diff_ci95_bout_bootstrap': cluster_bootstrap_diff(
                 y[sel], preds['lobo'][best][sel], preds['lobo']['motion'][sel], bouts[sel], k),
             'loro_balanced_accuracy_best': res['loro'][best]['balanced_accuracy'],
             'loro_balanced_accuracy_majority': res['loro']['majority']['balanced_accuracy']}
        g['passes'] = bool(g['best_f1'] - g['motion_f1'] >= GATE_MARGIN
                           and g['loro_balanced_accuracy_best'] > g['loro_balanced_accuracy_majority'])
        res['gate'] = g
        out[task] = res
        for scheme in ('loro', 'lobo'):
            print(f'\n== {task} {scheme}: acc / bal.acc / macro-F1')
            for n, r in res[scheme].items():
                print(f'  {n:26s} {r["accuracy"]:.3f} {r["balanced_accuracy"]:.3f} {r["macro_f1"]:.3f}  {r["confusion"]}')
        print('gate', g)
    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        with open(args.json, 'w', encoding='utf-8') as fh:
            json.dump(out, fh, indent=1)


if __name__ == '__main__':
    main()
