"""Scoring stage of scripts/hr_benchmark.py (run as `python scripts/hr_benchmark.py score`).

Reads the caches in ~/data_public/cache_bench and writes outputs/hr_benchmark/{tables.md, results.json, figures/}.
Protocol: outputs/hr_benchmark/PROTOCOL.md.
"""
from __future__ import annotations

import glob
import json
import os
import time
import warnings

import numpy as np

import hr_bench_common as hb
import hr_benchmark as B
from hr_common import label_interp, day_of, minute_of
from hr_evaluate import activity_group, read_ref

warnings.filterwarnings('ignore')
HEADS = ('ridge', 'knn', 'svr', 'mlp', 'hgb', 'bins')
CLASSICAL = ('welch', 'welch_acc', 'acf', 'nk2', 'heartpy', 'track_greedy', 'track_viterbi')
RING_CH = ('green', 'red', 'ir', 'best')


# ---------------------------------------------------------------- heads

def make_head(name, groups=None):
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    from sklearn.linear_model import Ridge
    from sklearn.neighbors import KNeighborsRegressor
    from sklearn.svm import SVR
    from sklearn.neural_network import MLPRegressor
    from sklearn.ensemble import HistGradientBoostingRegressor
    from sklearn.compose import TransformedTargetRegressor
    if name == 'ridge':
        return make_pipeline(StandardScaler(), Ridge())
    if name == 'knn':
        return make_pipeline(StandardScaler(), KNeighborsRegressor(15, weights='distance'))
    if name == 'svr':
        return make_pipeline(StandardScaler(), SVR(C=10, epsilon=1))
    if name == 'mlp':
        return TransformedTargetRegressor(make_pipeline(StandardScaler(), MLPRegressor(
            hidden_layer_sizes=(128,), early_stopping=True, random_state=B.SEED, max_iter=500)), transformer=StandardScaler())
    if name == 'hgb':
        return HistGradientBoostingRegressor(random_state=B.SEED)
    raise ValueError(name)


class BinHead:
    """Multinomial logistic regression over 5 bpm bins; prediction = expected bin centre."""
    def fit(self, X, y):
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler
        from sklearn.linear_model import LogisticRegression
        b = np.floor(np.asarray(y) / 5).astype(int)
        self.m = make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000, C=1.0))
        self.m.fit(X, b)
        self.centres = self.m.classes_ * 5 + 2.5
        return self

    def predict(self, X):
        return self.m.predict_proba(X) @ self.centres


def fit_head(name, X, y, groups, rng):
    """Fit one head; ridge alpha by inner group CV; svr on at most 6000 rows."""
    if name == 'bins':
        return BinHead().fit(X, y)
    if name == 'svr' and len(y) > 6000:
        sel = rng.choice(len(y), 6000, replace=False)
        X, y, groups = X[sel], y[sel], groups[sel]
    m = make_head(name)
    if name == 'ridge':
        from sklearn.model_selection import GridSearchCV, GroupKFold
        k = min(5, len(np.unique(groups)))
        gs = GridSearchCV(m, {'ridge__alpha': [1, 10, 100, 1000, 10000]}, cv=GroupKFold(k),
                          scoring='neg_mean_absolute_error')
        gs.fit(X, y, groups=groups)
        return gs.best_estimator_
    return m.fit(X, y)


# ---------------------------------------------------------------- ring data

def ref_files(data_root):
    one = lambda pat: sorted(glob.glob(os.path.join(data_root, pat)))[0]
    oct23 = os.path.join(data_root, 'tmp', 'embrace', 'pulse-rate.csv')
    nov03 = os.path.join(data_root, 'tmp', 'embrace_1103', 'pulse-rate.csv')
    nov23 = one('tmp/empatica_nov23/*pulse-rate.csv')
    nov24 = one('tmp/empatica_nov24c/*pulse-rate.csv')
    return {55: oct23, 60: oct23, 65: oct23, 66: nov03, 67: nov03, 140: nov23, 147: nov24, 148: nov24}


def load_ring(data_root):
    z = dict(np.load(os.path.join(B.CACHE, 'ring_windows.npz')))
    with open(os.path.join(B.CACHE, 'db.json')) as f:
        db = json.load(f)
    files = ref_files(data_root)
    sess = z['session'].astype(int)
    minute = np.array([minute_of(c) for c in z['centre']])
    ref, notes = {}, {}
    for s in B.SESSIONS:
        vals, _ = read_ref(files[s])
        ms = set(minute[sess == s].tolist())
        ref[s] = {m: v for m, v in vals.items() if m in ms}
        notes[s] = [tuple(x) for x in db['annotations'][str(s)]]
    day = {s: day_of(db['bounds'][str(s)][0]) for s in B.SESSIONS}
    # "best" channel per window: highest spectral SQI
    sq = np.stack([z[f'sqi_{c}'] for c in B.CHANNELS], 1)
    pick = np.argmax(sq, 1)
    z['sqi_best'] = sq[np.arange(len(pick)), pick]
    z['best_pick'] = pick
    for m in list(CLASSICAL):
        z[f'{m}_best'] = np.stack([z[f'{m}_{c}'] for c in B.CHANNELS], 1)[np.arange(len(pick)), pick]
    for sign in ('pos', 'neg'):
        E = np.stack([z[f'emb_{c}_{sign}'] for c in B.CHANNELS], 1)
        z[f'emb_best_{sign}'] = E[np.arange(len(pick)), pick]
    label = np.array([label_interp(c, ref[s]) if ref[s] else None for c, s in zip(z['centre'], sess)], dtype=float)
    return z, db, ref, notes, day, sess, minute, label


def score_ring_rows(est, sess, minute, ref, notes, day, live_minutes, sessions=None, keep=None):
    """Per-minute medians of window estimates -> metrics pooled and split. est NaN = no estimate."""
    sessions = sessions or list(B.SESSIONS)
    out = {}
    rows = []       # (session, minute, est, ref, train_const, group)
    for s in sessions:
        sel = (sess == s) & np.isfinite(est)
        if keep is not None:
            sel &= keep
        med = hb.minute_medians(minute[sel], est[sel])
        const = float(np.mean([v for o in B.SESSIONS if day[o] != day[s] for v in ref[o].values()]))
        for m, r in ref[s].items():
            g, _ = activity_group(m, notes[s]) if s in B.ACTIVE_REC else ('still', None)
            rows.append((s, m, med.get(m, np.nan), r, const, g))
    if not rows:
        return None
    S = np.array([r[0] for r in rows]); E = np.array([r[2] for r in rows], float); R = np.array([r[3] for r in rows], float)
    C = np.array([r[4] for r in rows], float); G = np.array([r[5] or 'none' for r in rows])
    M = np.array([r[1] for r in rows])
    groups = {'all': np.ones(len(S), bool), 'rest_rec': np.isin(S, B.REST_REC), 'active_rec': np.isin(S, B.ACTIVE_REC),
              'active_rec_still': np.isin(S, B.ACTIVE_REC) & (G == 'still'),
              'active_rec_active': np.isin(S, B.ACTIVE_REC) & (G == 'active')}
    for s in sessions:
        groups[f's{s}'] = S == s
    for g, msk in groups.items():
        if msk.sum() == 0:
            continue
        d = hb.window_metrics(E[msk], R[msk])
        ok = msk & np.isfinite(E)
        if d['n']:
            d['const_mae'] = float(np.abs(C[ok] - R[ok]).mean())
            d['oracle_const_mae'] = float(np.abs(R[ok] - np.median(R[ok])).mean())
            lm = np.array([(s, m) in live_minutes for s, m in zip(S[ok], M[ok])])
            if lm.any():
                d['n_live_minutes'] = int(lm.sum())
                d['mae_on_live_minutes'] = float(np.abs(E[ok][lm] - R[ok][lm]).mean())
        out[g] = d
    out['_minutes'] = [[int(s), int(m), (None if not np.isfinite(e) else round(float(e), 2)), float(r)]
                       for s, m, e, r in zip(S, M, E, R)]
    return out


def ring_benchmark(data_root, log):
    z, db, ref, notes, day, sess, minute, label = load_ring(data_root)
    n = len(sess)
    rng = np.random.default_rng(B.SEED)
    results = {}
    # live stored output and shown value
    live = np.full(0, np.nan)
    stored_est, stored_sess, stored_min = [], [], []
    for s in B.SESSIONS:
        for e, h in db['stored_v2'][str(s)]:
            stored_sess.append(s); stored_min.append(minute_of(e - 5000)); stored_est.append(h)
    stored_sess, stored_min, stored_est = np.array(stored_sess), np.array(stored_min), np.array(stored_est, float)
    live_minutes = set()
    for s in B.SESSIONS:
        sel = stored_sess == s
        for m in hb.minute_medians(stored_min[sel], stored_est[sel]):
            if m in ref[s]:
                live_minutes.add((s, m))
    results['live_stored_v2'] = score_ring_rows(stored_est, stored_sess, stored_min, ref, notes, day, live_minutes)
    sh_s, sh_m, sh_v = [], [], []
    for s in B.SESSIONS:
        for t, q, h, d, mv in db['summaries'][str(s)]:
            if d is not None and np.isfinite(d):
                sh_s.append(s); sh_m.append(minute_of(t)); sh_v.append(d)
    results['live_shown'] = score_ring_rows(np.array(sh_v, float), np.array(sh_s), np.array(sh_m), ref, notes, day, live_minutes)
    # training-mean constant
    const = np.array([np.mean([v for o in B.SESSIONS if day[o] != day[s] for v in ref[o].values()]) for s in sess])
    results['constant_training_mean'] = score_ring_rows(const, sess, minute, ref, notes, day, live_minutes)
    # classical
    for m in CLASSICAL:
        for ch in RING_CH:
            est = z[f'{m}_{ch}']
            thr = np.median(z[f'sqi_{ch}'])
            results[f'{m}|{ch}|none'] = score_ring_rows(est, sess, minute, ref, notes, day, live_minutes)
            results[f'{m}|{ch}|sqi'] = score_ring_rows(est, sess, minute, ref, notes, day, live_minutes, keep=z[f'sqi_{ch}'] >= thr)
    # existing heads recomputed offline on index-based windows (green, positive)
    held = [66, 67, 140, 147, 148]
    for hid in ('hr_ring_stretch_v2', 'hr_ring_embraceplus'):
        w = np.load(os.path.join(B.HEADS, f'{hid}.npz'))
        X = z['emb_green_pos'].astype(np.float64)
        est = X @ w['weights'].reshape(-1) + float(np.asarray(w['bias']).reshape(-1)[0])
        results[f'existing:{hid}|green|none'] = score_ring_rows(est, sess, minute, ref, notes, day, live_minutes, sessions=held)
    log('ring: baselines, classical and existing heads scored')
    # DaLiA-trained heads (source D), all DaLiA subjects; and ring-trained (R) / combined (D+R), leave-one-day-out
    dz = np.load(os.path.join(B.CACHE, 'dalia_windows.npz'))
    days = sorted(set(day.values()))
    for sign in ('pos', 'neg'):
        dkey = 'emb_D25' if sign == 'pos' else 'emb_D25neg'
        DX, Dy, Dg = dz[dkey], dz['label'], dz['subject']
        okd = np.isfinite(DX).all(1)
        DX, Dy, Dg = DX[okd], Dy[okd], Dg[okd]
        sub = rng.choice(len(Dy), 4000, replace=False)
        for head in HEADS:
            t0 = time.time()
            mD = None
            for ch in RING_CH:
                X = z[f'emb_{ch}_{sign}']
                ok = np.isfinite(X).all(1)
                cache = os.path.join(B.CACHE, f'pred_ring_{head}_{sign}_{ch}.npz')
                if os.path.exists(cache):
                    c = np.load(cache)
                    pD, pR, pDR = c['pD'], c['pR'], c['pDR']
                    days_todo = []
                else:
                    if mD is None:
                        mD = fit_head(head, DX, Dy, Dg, np.random.default_rng(B.SEED))
                    pD = np.full(n, np.nan); pR = np.full(n, np.nan); pDR = np.full(n, np.nan)
                    pD[ok] = mD.predict(X[ok])
                    days_todo = days
                for dtest in days_todo:
                    tr = ok & np.isfinite(label) & np.array([day[s] != dtest for s in sess])
                    te = ok & np.array([day[s] == dtest for s in sess])
                    if tr.sum() < 30 or te.sum() == 0:
                        continue
                    gtr = np.array([day[s] for s in sess[tr]])
                    mR = fit_head(head, X[tr], label[tr], gtr, rng)
                    pR[te] = mR.predict(X[te])
                    XDR = np.vstack([X[tr], DX[sub]]); yDR = np.concatenate([label[tr], Dy[sub]])
                    gDR = np.concatenate([gtr, 1000 + Dg[sub]])
                    mDR = fit_head(head, XDR, yDR, gDR, rng)
                    pDR[te] = mDR.predict(X[te])
                if days_todo:
                    np.savez(cache, pD=pD, pR=pR, pDR=pDR)
                thr = np.median(z[f'sqi_{ch}'])
                for src, p in (('D', pD), ('R', pR), ('D+R', pDR)):
                    key = f'papagei:{head}:{src}:{sign}|{ch}'
                    results[f'{key}|none'] = score_ring_rows(p, sess, minute, ref, notes, day, live_minutes)
                    results[f'{key}|sqi'] = score_ring_rows(p, sess, minute, ref, notes, day, live_minutes, keep=z[f'sqi_{ch}'] >= thr)
            log(f'ring heads {head} {sign} done ({time.time() - t0:.0f} s)')
    return results, z, ref, sess, minute, day, notes


# ---------------------------------------------------------------- ring signal quality

def ring_signal(z, ref, sess, minute, notes):
    """Spectral SNR at the EmbracePlus HR and hit rate, per channel, rest vs active recordings and annotation."""
    f = z['spec_f']
    out = {}
    refw = np.array([ref[s].get(m, np.nan) for s, m in zip(sess, minute)])
    grp = np.array(['rest_rec' if s in B.REST_REC else 'active_rec' for s in sess])
    ann = np.array([(activity_group(m, notes[s])[0] if s in B.ACTIVE_REC else 'still') or 'none' for s, m in zip(sess, minute)])
    for ch in B.CHANNELS:
        P = z[f'spec_{ch}']
        snr = np.array([hb.snr_at(f, P[i], refw[i]) if np.isfinite(refw[i]) else np.nan for i in range(len(sess))])
        hit = np.abs(z[f'welch_{ch}'] - refw) <= 10
        for g, msk in (('rest_rec', grp == 'rest_rec'), ('active_rec', grp == 'active_rec'),
                       ('active_rec_still', (grp == 'active_rec') & (ann == 'still')),
                       ('active_rec_active', (grp == 'active_rec') & (ann == 'active')),
                       *((f's{s}', sess == s) for s in B.SESSIONS)):
            k = msk & np.isfinite(refw)
            if k.sum() == 0:
                continue
            out[f'{ch}|{g}'] = {'windows': int(k.sum()), 'snr_db_median': float(np.nanmedian(snr[k])),
                                'hit10_pct': float(np.mean(hit[k]) * 100), 'sqi_median': float(np.median(z[f'sqi_{ch}'][k])),
                                'rise_fraction_median': float(np.nanmedian(z[f'rf_ratio_{ch}'][k])),
                                'skew_median': float(np.nanmedian(z[f'skew_{ch}'][k])),
                                'acc_sd_median': float(np.nanmedian(z['acc_sd'][k]))}
    return out, refw


def clean_window_agreement(z, sess, refw):
    """Added after the first run (diagnostic, not a method): windows where the ring is still (accelerometer SD < 15),
    the green spectrum is clean (SQI >= 0.6) and the spectral peaks of green, red and ir agree within 6 bpm. In such
    windows the ring's own pulse rate is unambiguous, so disagreement with the EmbracePlus there is not motion noise
    in the ring; it is the reference, a timing offset, or a non-cardiac rhythm under a still ring."""
    W = np.stack([z[f'welch_{c}'] for c in B.CHANNELS], 1)
    clean = (z['acc_sd'] < 15) & (z['sqi_green'] >= 0.6) & (W.max(1) - W.min(1) <= 6) & np.isfinite(refw)
    est = np.median(W, 1)
    out = {}
    for g, m in (('rest_rec', np.isin(sess, B.REST_REC)), ('active_rec', np.isin(sess, B.ACTIVE_REC)),
                 ('active_rec_ref_gt_85', np.isin(sess, B.ACTIVE_REC) & (refw > 85)),
                 *((f's{s}', sess == s) for s in B.SESSIONS)):
        k = clean & m
        out[g] = {'clean_windows': int(k.sum()), 'all_windows': int((m & np.isfinite(refw)).sum())}
        if k.sum():
            out[g].update(hb.window_metrics(est[k], refw[k]))
            out[g]['ring_range'] = [float(est[k].min()), float(est[k].max())]
            out[g]['ref_range'] = [float(refw[k].min()), float(refw[k].max())]
    return out


# ---------------------------------------------------------------- DaLiA

def dalia_pred(z, cond, head):
    """Held-out (5 subject folds) predictions of one PaPaGei head on one DaLiA condition, cached."""
    cache = os.path.join(B.CACHE, f'pred_dalia_{cond}_{head}.npy')
    if os.path.exists(cache):
        return np.load(cache)
    y, subj = z['label'], z['subject']
    fold = np.array([hb.dalia_fold(s) for s in subj])
    X = z[f'emb_{cond}']
    ok = np.isfinite(X).all(1)
    p = np.full(len(y), np.nan)
    for k in range(5):
        tr, te = ok & (fold != k), ok & (fold == k)
        m = fit_head(head, X[tr], y[tr], subj[tr], np.random.default_rng(B.SEED + k))
        p[te] = m.predict(X[te])
    np.save(cache, p)
    return p


def dalia_benchmark(log):
    z = np.load(os.path.join(B.CACHE, 'dalia_windows.npz'))
    y, subj, act = z['label'], z['subject'], z['activity']
    fold = np.array([hb.dalia_fold(s) for s in subj])
    rng = np.random.default_rng(B.SEED)
    groups = {'all': np.ones(len(y), bool), 'rest': np.isin(act, list(B.DALIA_REST)), 'active': np.isin(act, list(B.DALIA_ACTIVE))}
    res = {}

    def score(p):
        d = {}
        const = np.full(len(y), np.nan)
        for k in range(5):
            const[fold == k] = y[fold != k].mean()
        for g, m in groups.items():
            d[g] = hb.window_metrics(p[m], y[m])
            ok = m & np.isfinite(p)
            d[g]['const_mae'] = float(np.abs(const[ok] - y[ok]).mean()) if ok.any() else None
        return d

    const = np.full(len(y), np.nan)
    for k in range(5):
        const[fold == k] = y[fold != k].mean()
    res['constant_training_mean'] = score(const)
    for cond in ('D64', 'D25'):
        for m in CLASSICAL:
            res[f'{m}|{cond}'] = score(z[f'{m}_{cond}'])
        thr = np.median(z[f'sqi_{cond}'])
        res[f'welch_acc|{cond}|sqi'] = score(np.where(z[f'sqi_{cond}'] >= thr, z[f'welch_acc_{cond}'], np.nan))
        res[f'track_viterbi|{cond}|sqi'] = score(np.where(z[f'sqi_{cond}'] >= thr, z[f'track_viterbi_{cond}'], np.nan))
    log('dalia classical scored')
    for cond in ('D64', 'D25', 'D25poly', 'D25neg'):
        X = z[f'emb_{cond}']
        ok = np.isfinite(X).all(1)
        for head in HEADS:
            t0 = time.time()
            p = dalia_pred(z, cond, head)
            res[f'papagei:{head}|{cond}'] = score(p)
            log(f'dalia {cond} {head}: MAE {res[f"papagei:{head}|{cond}"]["all"]["mae"]:.2f} ({time.time() - t0:.0f} s)')
    sig = {}
    for cond in ('D64', 'D25'):
        for g, m in groups.items():
            sig[f'{cond}|{g}'] = {'windows': int(m.sum()), 'snr_db_median': float(np.nanmedian(z[f'snr_{cond}'][m])),
                                  'hit10_pct': float(np.mean(np.abs(z[f'welch_{cond}'][m] - y[m]) <= 10) * 100),
                                  'sqi_median': float(np.median(z[f'sqi_{cond}'][m])),
                                  'rise_fraction_median': float(np.nanmedian(z[f'rf_ratio_{cond}'][m]))}
    return res, sig


# ---------------------------------------------------------------- synthetic

def synthetic_check():
    from sklearn.model_selection import KFold
    z = np.load(os.path.join(B.CACHE, 'synthetic.npz'))
    y = z['bpm']
    rng = np.random.default_rng(B.SEED)
    out = {'welch_25hz': hb.window_metrics(z['welch25'], y)}
    for key in ('emb125', 'emb25', 'emb25neg'):
        X = z[key]
        for head in ('ridge', 'knn'):
            p = np.full(len(y), np.nan)
            for tr, te in KFold(5, shuffle=True, random_state=B.SEED).split(X):
                m = fit_head(head, X[tr], y[tr], np.arange(len(tr)) % 5, rng)
                p[te] = m.predict(X[te])
            out[f'{head}|{key}'] = hb.window_metrics(p, y)
    return out


# ---------------------------------------------------------------- main

def main(args):
    os.makedirs(B.OUT, exist_ok=True)
    t_start = time.time()
    logf = open(os.path.join(B.CACHE, 'score.log'), 'w')

    def log(msg):
        line = f'[{time.time() - t_start:6.0f} s] {msg}'
        print(line, flush=True); logf.write(line + '\n'); logf.flush()

    with open(os.path.join(B.CACHE, 'ring_checks.json')) as f:
        checks = json.load(f)
    with open(os.path.join(B.CACHE, 'worker_check.json')) as f:
        worker = json.load(f)
    with open(os.path.join(B.CACHE, 'db.json')) as f:
        order_check = json.load(f).get('order_check', {})
    syn = synthetic_check()
    log('synthetic scored')
    dres, dsig = dalia_benchmark(log)
    rres, z, ref, sess, minute, day, notes = ring_benchmark(args.data_root, log)
    rsig, refw = ring_signal(z, ref, sess, minute, notes)
    clean = clean_window_agreement(z, sess, refw)
    jumps = []
    for s in B.SESSIONS:
        ms = sorted(ref[s])
        jumps += [abs(ref[s][b] - ref[s][a]) for a, b in zip(ms, ms[1:]) if b - a == 1]
    out = {'ring_checks': checks, 'worker_check': worker, 'order_check': order_check, 'synthetic': syn, 'dalia': dres, 'dalia_signal': dsig,
           'ring': rres, 'ring_signal': rsig, 'clean_window_agreement': clean, 'best_channel_share': {c: float(np.mean(z['best_pick'] == i)) for i, c in enumerate(B.CHANNELS)},
           'reference_jumps': {'n': len(jumps), 'median': float(np.median(jumps)), 'p95': float(np.percentile(jumps, 95)), 'max': float(max(jumps))},
           'ring_windows': int(len(sess)), 'runtime_s': round(time.time() - t_start)}
    with open(os.path.join(B.OUT, 'results.json'), 'w') as f:
        json.dump(out, f, separators=(',', ':'), default=float)
    np.savez_compressed(os.path.join(B.CACHE, 'ring_refw.npz'), refw=refw)
    log('results.json written')
    import hr_benchmark_report
    hr_benchmark_report.write(out)
    log('tables and figures written')
