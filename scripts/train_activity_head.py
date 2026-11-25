#!/usr/bin/env python3
"""Train and honestly evaluate an activity head on frozen MOMENT embeddings of accelerometer windows.

Input: the .npz written by scripts/moment_windows.py (labelled 10 s windows, their MOMENT
embeddings, handcrafted statistics, recording ids). Output: a leave-one-recording-out report,
and with --save a head in ml-inference-server/heads_imu/ (<id>.json + <id>.npz) in the same
two-file format as the PaPaGei heads.

The head is multinomial logistic regression with an L2 penalty, fitted by L-BFGS in numpy/scipy
(no scikit-learn: Windows Application Control blocks some of its estimators here), class-balanced,
on standardised features; the standardisation is folded into the saved weights so the worker
applies `features @ weights + bias` to raw features, as for the PaPaGei heads.

Feature sets compared on the same folds, same head, same penalty:
  majority     the training fold's most common class (what "learning nothing" scores)
  motion       handcrafted: log1p of std(|a|), std(diff |a|), per-axis std (variance / jerk-like)
  motion+grav  motion plus the unit gravity direction (mean acceleration vector)
  moment_mag   MOMENT embedding of the magnitude channel (512)
  moment_xyz   MOMENT embeddings of x, y, z concatenated (1536)
  moment+grav  moment_mag plus the gravity direction

Folds are whole *experiments* (a recording): windows of one recording are strongly correlated,
and the two earbuds / ring of one recording share its labels and clock. Scores are computed on
the pooled out-of-fold predictions, because a held-out recording usually contains one class only.

    python scripts/train_activity_head.py --windows <scratch>/imu_windows.npz \
        --device OpenRing --classes sitting "lying down"
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
from scipy.optimize import minimize

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
HEADS_IMU = os.path.join(ROOT, 'ml-inference-server', 'heads_imu')
sys.path.insert(0, os.path.join(ROOT, 'ml-inference-server'))
import moment_encoder as me  # noqa: E402

LAMBDA = 10.0          # fixed a priori (see docs/IMU_MODEL.md); the grid below is a sensitivity check only
GRID = (0.1, 1.0, 10.0, 100.0, 1000.0)
MOTION = ('mag_std', 'dmag_std', 'x_std', 'y_std', 'z_std')
GRAV = ('gx', 'gy', 'gz')

FEATURE_SETS = {
    'motion': [f'logstat:{n}' for n in MOTION],
    'motion+grav': [f'logstat:{n}' for n in MOTION] + [f'stat:{n}' for n in GRAV],
    'moment_mag': ['moment:mag'],
    'moment_xyz': ['moment:x', 'moment:y', 'moment:z'],
    'moment+grav': ['moment:mag'] + [f'stat:{n}' for n in GRAV],
}


def build_features(data, names):
    """Feature matrix for the named features, via the same function the worker uses."""
    return np.stack([me.feature_vector(names, e, st) for e, st in zip(data['emb'], data['stats'])]).astype(np.float64)


def fit(X, y, n_classes, lam):
    """Class-balanced L2 multinomial logistic regression on standardised features.
    Returns (weights, bias) for the RAW features (standardisation folded in)."""
    mu, sd = X.mean(axis=0), X.std(axis=0) + 1e-8
    Z = (X - mu) / sd
    counts = np.bincount(y, minlength=n_classes).astype(float)
    sample_w = (len(y) / (n_classes * np.maximum(counts, 1)))[y]
    sample_w = sample_w / sample_w.mean()
    onehot = np.eye(n_classes)[y]
    d = Z.shape[1]

    def objective(p):
        W, b = p[:d * n_classes].reshape(d, n_classes), p[d * n_classes:]
        logits = Z @ W + b
        logits -= logits.max(axis=1, keepdims=True)
        probs = np.exp(logits)
        probs /= probs.sum(axis=1, keepdims=True)
        loss = -(sample_w * np.log(probs[np.arange(len(y)), y] + 1e-12)).mean() + 0.5 * lam / len(y) * (W ** 2).sum()
        grad_logits = sample_w[:, None] * (probs - onehot) / len(y)
        gW = Z.T @ grad_logits + lam / len(y) * W
        gb = grad_logits.sum(axis=0)
        return loss, np.concatenate([gW.ravel(), gb])

    res = minimize(objective, np.zeros(d * n_classes + n_classes), jac=True, method='L-BFGS-B',
                   options={'maxiter': 500})
    W, b = res.x[:d * n_classes].reshape(d, n_classes), res.x[d * n_classes:]
    W_raw = W / sd[:, None]
    b_raw = b - (mu / sd) @ W
    return W_raw.astype(np.float32), b_raw.astype(np.float32)


def predict(X, W, b):
    logits = X @ W + b
    logits -= logits.max(axis=1, keepdims=True)
    p = np.exp(logits)
    return p / p.sum(axis=1, keepdims=True)


def macro_f1(y, pred, k):
    f = []
    for c in range(k):
        tp = np.sum((y == c) & (pred == c))
        fp = np.sum((y != c) & (pred == c))
        fn = np.sum((y == c) & (pred != c))
        f.append(0.0 if tp == 0 else 2 * tp / (2 * tp + fp + fn))
    return float(np.mean(f))


def balanced_acc(y, pred, k):
    rec = [np.mean(pred[y == c] == c) for c in range(k) if np.any(y == c)]
    return float(np.mean(rec))


def confusion(y, pred, k):
    m = np.zeros((k, k), dtype=int)
    for a, b in zip(y, pred):
        m[a, b] += 1
    return m


def loro(X, y, groups, k, lam, majority=False):
    """Pooled out-of-fold predictions, holding out one group (recording) at a time."""
    pred = np.zeros(len(y), dtype=int)
    prob = np.zeros((len(y), k))
    for g in np.unique(groups):
        test = groups == g
        train = ~test
        if len(np.unique(y[train])) < 2:
            pred[test] = np.bincount(y[train], minlength=k).argmax()
            continue
        if majority:
            pred[test] = np.bincount(y[train], minlength=k).argmax()
            continue
        W, b = fit(X[train], y[train], k, lam)
        prob[test] = predict(X[test], W, b)
        pred[test] = prob[test].argmax(axis=1)
    return pred, prob


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--windows', required=True)
    ap.add_argument('--device', default='OpenRing', choices=['OpenRing', 'OpenEarable'])
    ap.add_argument('--classes', nargs='+', default=['sitting', 'lying down'])
    ap.add_argument('--min-recordings', type=int, default=2,
                    help='a class needs windows from at least this many recordings to be evaluated')
    ap.add_argument('--save', help='head id to write into ml-inference-server/heads_imu/ (trains on ALL windows)')
    ap.add_argument('--feature-set', default='moment_mag', choices=sorted(FEATURE_SETS))
    ap.add_argument('--json', help='write the results as JSON here')
    args = ap.parse_args()

    z = np.load(args.windows, allow_pickle=False)
    data = {k: z[k] for k in z.files if k != 'meta'}
    keep = (data['device'] == args.device) & np.isin(data['label'], args.classes)
    data = {k: v[keep] for k, v in data.items()}
    classes = list(args.classes)
    y = np.array([classes.index(s) for s in data['label']])
    groups = data['experiment']

    print(f'device {args.device}: {len(y)} windows')
    ok = True
    for i, c in enumerate(classes):
        recs = np.unique(groups[y == i])
        print(f'  {c!r}: {int((y == i).sum())} windows from {len(recs)} recordings {recs.tolist()}')
        if len(recs) < args.min_recordings:
            ok = False
            print(f'    -> fewer than {args.min_recordings} recordings: cannot be held out and still learned')
    if not ok:
        print('NOT EVALUABLE: leave-one-recording-out needs every class in at least two recordings.')
        return 2

    results = {}
    pred, _ = loro(np.zeros((len(y), 1)), y, groups, len(classes), LAMBDA, majority=True)
    results['majority'] = pred
    for name, feats in FEATURE_SETS.items():
        X = build_features(data, feats)
        results[name], _ = loro(X, y, groups, len(classes), LAMBDA)

    print(f'\nleave-one-recording-out, pooled out-of-fold, lambda={LAMBDA}')
    print(f'{"features":14s} {"acc":>6s} {"bal.acc":>8s} {"macroF1":>8s}')
    summary = {}
    for name, pred in results.items():
        acc, bal, f1 = float((pred == y).mean()), balanced_acc(y, pred, len(classes)), macro_f1(y, pred, len(classes))
        summary[name] = {'accuracy': round(acc, 3), 'balanced_accuracy': round(bal, 3), 'macro_f1': round(f1, 3),
                         'confusion': confusion(y, pred, len(classes)).tolist()}
        print(f'{name:14s} {acc:6.3f} {bal:8.3f} {f1:8.3f}')
    print('\nconfusion matrices (rows = true, columns = predicted), classes:', classes)
    for name in results:
        print(f'  {name}: {summary[name]["confusion"]}')

    print('\nper held-out recording, recall of its own label (windows):')
    for g in np.unique(groups):
        m = groups == g
        label = classes[int(np.bincount(y[m]).argmax())]
        row = ', '.join(f'{n} {np.mean(results[n][m] == y[m]):.2f}' for n in results)
        print(f'  recording {g:3d} {label:12s} n={int(m.sum()):4d}: {row}')

    print('\npenalty sensitivity (macro-F1, not used to choose lambda):')
    sens = {}
    for name in ('motion', 'motion+grav', 'moment_mag', 'moment_xyz', 'moment+grav'):
        X = build_features(data, FEATURE_SETS[name])
        vals = []
        for lam in GRID:
            p, _ = loro(X, y, groups, len(classes), lam)
            vals.append(round(macro_f1(y, p, len(classes)), 3))
        sens[name] = dict(zip(map(str, GRID), vals))
        print(f'  {name:12s}', dict(zip(GRID, vals)))

    if args.json:
        with open(args.json, 'w', encoding='utf-8') as fh:
            json.dump({'device': args.device, 'classes': classes, 'windows': int(len(y)), 'lambda': LAMBDA,
                       'results': summary, 'sensitivity': sens}, fh, indent=1)

    if args.save:
        feats = FEATURE_SETS[args.feature_set]
        X = build_features(data, feats)
        W, b = fit(X, y, len(classes), LAMBDA)
        os.makedirs(HEADS_IMU, exist_ok=True)
        np.savez(os.path.join(HEADS_IMU, f'{args.save}.npz'), weights=W, bias=b)
        s = summary[args.feature_set]
        manifest = {
            'head_id': args.save,
            'head_version': '2026-11-19',
            'display_name': 'Activity (MOMENT-1-small + logistic) - pilot',
            'base_model_id': 'moment_1_small',
            'input_dimension': int(W.shape[0]),
            'output_type': 'classification',
            'labels': classes,
            'weights_file': f'{args.save}.npz',
            'features': feats,
            'device_types': [args.device],
            'task_description': f'{" vs ".join(classes)} from a 10 s {args.device} accelerometer window, '
                                f'resampled to 512 samples and embedded by frozen MOMENT-1-small.',
            'training': {
                'windows': int(len(y)),
                'windows_per_label': {c: int((y == i).sum()) for i, c in enumerate(classes)},
                'recordings': {c: sorted(int(r) for r in np.unique(groups[y == i])) for i, c in enumerate(classes)},
                'l2_lambda': LAMBDA, 'class_balanced': True,
                'labels': 'manual_app activity annotations; windows kept when one label covers >= 90% and no other label overlaps',
            },
            'validation': {
                'method': 'leave-one-recording-out, pooled out-of-fold, same folds for every feature set',
                'feature_set': args.feature_set,
                'accuracy': s['accuracy'], 'balanced_accuracy': s['balanced_accuracy'], 'macro_f1': s['macro_f1'],
                'confusion_rows_true_cols_pred': s['confusion'],
                'majority_baseline': summary['majority'],
                'handcrafted_baselines': {k: summary[k] for k in ('motion', 'motion+grav')},
            },
            'validation_status': 'pilot: two lying-down recordings from one wearer; not shown to generalise; see docs/IMU_MODEL.md',
        }
        with open(os.path.join(HEADS_IMU, f'{args.save}.json'), 'w', encoding='utf-8') as fh:
            json.dump(manifest, fh, indent=2)
        print(f'\nsaved {args.save} ({W.shape[0]} features) to {HEADS_IMU}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
