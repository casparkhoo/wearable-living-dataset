#!/usr/bin/env python3
"""Heart-rate benchmark: ring (tau-Ring) vs EmbracePlus, and PPG-DaLiA vs ECG, for PaPaGei-S heads and
classical pulse-rate methods. Protocol: outputs/hr_benchmark/PROTOCOL.md (declared before the first run).

Stages (each caches into ~/data_public/cache_bench, outside the repository):
    python scripts/hr_benchmark.py extract        # ring samples, live outputs, annotations from the local MySQL (read-only)
    python scripts/hr_benchmark.py check-worker   # local ONNX embedding == running worker container on sample windows
    python scripts/hr_benchmark.py ring           # ring windows, signal checks, classical methods, embeddings
    python scripts/hr_benchmark.py dalia          # DaLiA windows, classical methods, embeddings (3 conditions)
    python scripts/hr_benchmark.py synthetic      # synthetic pulse-rate sanity check of PaPaGei + probe
    python scripts/hr_benchmark.py score          # every head and method, writes outputs/hr_benchmark/*
    python scripts/hr_benchmark.py warm-ring      # optional: ring head predictions in a second process (cache only)

--data-root points at the checkout that holds tmp/ (EmbracePlus files) and deployment/local (database).
"""
from __future__ import annotations

import argparse
import csv
import glob
import json
import os
import pickle
import subprocess
import sys
import time
import warnings

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, 'ml-inference-server'))

import hr_bench_common as hb                                   # noqa: E402
from hr_common import label_interp, day_of                     # noqa: E402

CACHE = os.path.join(os.path.expanduser('~'), 'data_public', 'cache_bench')
DALIA_DIR = os.path.join(os.path.expanduser('~'), 'data_public', 'extracted', 'dalia_pkl')
OUT = os.path.join(ROOT, 'outputs', 'hr_benchmark')
ONNX = os.path.join(ROOT, 'ml-inference-server', 'weights', 'papagei_s.onnx')
HEADS = os.path.join(ROOT, 'ml-inference-server', 'heads')
SESSIONS = {55: None, 60: None, 65: None, 66: None, 67: 1793675220000, 140: None, 147: None, 148: None}
REST_REC = (55, 60, 65, 66, 67, 148)
ACTIVE_REC = (140, 147)
CHANNELS = ('green', 'red', 'ir')
FS_RING = 25.0
WIN_N, STEP_N = 250, 125
SEED = 0
DALIA_REST = {1, 5, 8}        # sitting, driving, working
DALIA_ACTIVE = {2, 3, 4, 7}   # stairs, table soccer, cycling, walking


def default_data_root():
    """The main checkout (this file may run from a git worktree)."""
    p = ROOT
    while os.path.basename(os.path.dirname(p)) == 'worktrees':
        p = os.path.abspath(os.path.join(p, '..', '..', '..'))
    return p if os.path.isdir(os.path.join(p, 'tmp')) else ROOT


# ---------------------------------------------------------------- database (read-only)

def compose(data_root):
    d = os.path.join(data_root, 'deployment', 'local')
    return ['docker', 'compose', '--env-file', os.path.join(d, '.env'), '-f', os.path.join(d, 'docker-compose.yml')]


def sql(base, query):
    cmd = base + ['exec', '-T', 'mysql', 'sh', '-c', 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def num(x):
    return np.nan if x in ('NULL', 'null', '', None) else float(x)


def extract(args):
    base = compose(args.data_root)
    os.makedirs(CACHE, exist_ok=True)
    bounds = {int(a): (int(b), int(float(c))) for a, b, c in sql(
        base, "SELECT s.id, s.started_at_ms, s.ended_at_ms FROM sessions s JOIN devices d ON d.id=s.device_id "
              "WHERE d.device_type='OpenRing' AND s.ended_at_ms IS NOT NULL;")}
    db = {'bounds': {}, 'summaries': {}, 'stored_v2': {}, 'annotations': {}}
    for sid, cap in SESSIONS.items():
        lo, hi = bounds[sid]
        if cap:
            hi = min(hi, cap)
        t0 = time.time()
        rows = sql(base, (
            "SELECT p.id, p.sample_index, p.android_received_timestamp_ms, p.backend_received_timestamp_ms, "
            "JSON_EXTRACT(p.channel_values,'$.green'), JSON_EXTRACT(p.channel_values,'$.red'), "
            "JSON_EXTRACT(p.channel_values,'$.ir'), JSON_EXTRACT(a.channel_values,'$.x'), "
            "JSON_EXTRACT(a.channel_values,'$.y'), JSON_EXTRACT(a.channel_values,'$.z'), p.sample_rate_hz "
            "FROM stream_samples p LEFT JOIN stream_samples a ON a.raw_packet_id=p.raw_packet_id "
            "AND a.stream_name='accel' AND a.sample_index<=>p.sample_index "
            f"WHERE p.session_id={sid} AND p.stream_name='ppg' AND p.backend_received_timestamp_ms BETWEEN {lo} AND {hi} "
            "ORDER BY p.id;"))
        arr = np.array([[num(c) for c in r] for r in rows], dtype=np.float64)
        np.save(os.path.join(CACHE, f'ring_{sid}.npy'), arr)
        db['bounds'][sid] = [lo, hi]
        db['summaries'][sid] = [[int(a), q if q not in ('NULL', 'null') else None, num(h), num(d), m if m not in ('NULL', 'null') else None]
                                for a, q, h, d, m in sql(base, (
                                    "SELECT detected_at_ms, JSON_UNQUOTE(JSON_EXTRACT(details,'$.ppg.quality')), "
                                    "JSON_EXTRACT(details,'$.ppg.hr_bpm'), JSON_EXTRACT(details,'$.ppg.display_hr_bpm'), "
                                    "JSON_UNQUOTE(JSON_EXTRACT(details,'$.movement.state')) FROM live_events "
                                    f"WHERE session_id={sid} AND event_type='summary' AND detected_at_ms BETWEEN {lo} AND {hi};"))]
        db['stored_v2'][sid] = [[int(e), float(h)] for e, h in sql(base, (
            f"SELECT window_end_ms, hr_bpm FROM derived_windows WHERE session_id={sid} "
            "AND method_id='papagei_s+hr_ring_stretch_v2' AND hr_bpm IS NOT NULL;"))]
        db['annotations'][sid] = [[lab, int(a), int(float(b))] for lab, a, b in sql(base, (
            "SELECT LOWER(label_value), started_at_ms, IFNULL(ended_at_ms, started_at_ms) FROM annotations "
            f"WHERE label_name='activity' AND started_at_ms BETWEEN {lo - 60000} AND {hi + 60000};"))]
        print(f'session {sid}: {len(arr)} ppg rows, {len(db["summaries"][sid])} summaries, '
              f'{len(db["stored_v2"][sid])} stored v2 windows ({time.time() - t0:.0f} s)', flush=True)
    # Added after the first run: stored row order vs sample order, and dates, for these sessions and for the
    # recordings the existing head hr_ring_stretch_v2 was trained on (ml-inference-server/heads/hr_ring_stretch_v2.json).
    ids = sorted(set(SESSIONS) | {68, 70, 76, 94, 105, 108, 110, 118})
    idl = ','.join(str(i) for i in ids)
    dates = {int(a): b for a, b in sql(base, f"SELECT id, DATE(FROM_UNIXTIME(started_at_ms/1000)) FROM sessions WHERE id IN ({idl});")}
    db['order_check'] = {int(s): {'utc_date': dates.get(int(s)), 'pairs': int(n), 'out_of_order': int(k)} for s, n, k in sql(base, (
        "SELECT session_id, COUNT(*), SUM(d < 0) FROM (SELECT session_id, sample_index - LAG(sample_index) OVER "
        f"(PARTITION BY session_id ORDER BY id) AS d FROM stream_samples WHERE stream_name='ppg' AND session_id IN ({idl})) x "
        "WHERE d IS NOT NULL AND d > -100 GROUP BY session_id;"))}
    print('order check', db['order_check'], flush=True)
    with open(os.path.join(CACHE, 'db.json'), 'w') as f:
        json.dump(db, f)


# ---------------------------------------------------------------- PaPaGei embedding (local ONNX)

_SESSION = None


def onnx_session():
    global _SESSION
    if _SESSION is None:
        import onnxruntime as ort
        o = ort.SessionOptions()
        o.intra_op_num_threads = 8
        _SESSION = ort.InferenceSession(ONNX, o, providers=['CPUExecutionProvider'])
    return _SESSION


def prepare(window, fs, mode='live'):
    """Live preprocessing (ml-inference-server/preprocessing.py). mode 'poly' swaps the linear interpolation to
    125 Hz for scipy resample_poly. Returns None if the live code would refuse the window."""
    from preprocessing import PpgWindowConfig, prepare_ppg_window
    cfg = PpgWindowConfig(source_sample_rate_hz=float(fs))
    try:
        x, _ = prepare_ppg_window(window, cfg)
    except ValueError:
        return None
    if mode == 'live':
        return x
    from fractions import Fraction
    from scipy.signal import cheby2, sosfiltfilt, resample_poly
    w = np.asarray(window, float)[-cfg.source_sample_count:]
    hi = min(cfg.high_cut_hz, fs / 2 * 0.9)
    sos = cheby2(4, 20, [0.5, hi], btype='bandpass', fs=fs, output='sos')
    y = sosfiltfilt(sos, w)
    fr = Fraction(125.0 / fs).limit_denominator(1000)
    y = resample_poly(y, fr.numerator, fr.denominator)[:1250]
    return ((y - y.mean()) / (y.std() + 1e-12)).astype(np.float32)


def embed(windows, fs, mode='live', batch=256):
    """(n, 512) PaPaGei-S embeddings; NaN rows for windows the preprocessing refuses."""
    s = onnx_session()
    n = len(windows)
    out = np.full((n, 512), np.nan, np.float32)
    prepped, idx = [], []
    for i, w in enumerate(windows):
        x = prepare(w, fs, mode)
        if x is not None:
            prepped.append(x); idx.append(i)
    for a in range(0, len(prepped), batch):
        X = np.stack(prepped[a:a + batch]).reshape(-1, 1, 1250).astype(np.float32)
        out[idx[a:a + batch]] = s.run(None, {'ppg': X})[0]
    return out


# ---------------------------------------------------------------- ring windows

def ring_series(sid):
    """Per segment (index never decreasing): dict with sorted unique index, values per channel, accel, time."""
    arr = np.load(os.path.join(CACHE, f'ring_{sid}.npy'))
    # columns: id, idx, android_ms, backend_ms, green, red, ir, ax, ay, az, rate
    arr = arr[np.isfinite(arr[:, 1])]
    segs = []
    for a, b in hb.split_segments(arr[:, 1]):
        s = arr[a:b]
        _, first = np.unique(s[:, 1], return_index=True)      # duplicates (re-sent packets): keep first arrival
        s = s[np.sort(first)]
        s = s[np.argsort(s[:, 1], kind='stable')]
        if len(s) < WIN_N:
            continue
        tsrc = s[:, 2] if np.isfinite(s[:, 2]).all() else s[:, 3]
        t0, dt = hb.fit_clock(s[:, 1], tsrc)
        segs.append({'idx': s[:, 1].astype(np.int64), 'green': s[:, 4], 'red': s[:, 5], 'ir': s[:, 6],
                     'acc': s[:, 7:10], 'arrival': tsrc, 'backend': s[:, 3], 't0': t0, 'dt': dt,
                     'rate_field': float(np.nanmedian(s[:, 10]))})
    return segs


def ring(args):
    """Ring windows + signal checks + classical estimates + embeddings, cached as ring_windows.npz."""
    import neurokit2 as nk
    import heartpy as hp
    with open(os.path.join(CACHE, 'db.json')) as f:
        db = json.load(f)
    rec = {k: [] for k in ('session', 'centre', 'seg', 'acc_sd')}
    raw = {c: [] for c in CHANNELS}
    acc_spec_peaks = []
    checks = {}
    for sid in SESSIONS:
        segs = ring_series(sid)
        arr = np.load(os.path.join(CACHE, f'ring_{sid}.npy'))
        c = {'rows': int(len(arr)), 'segments': len(segs), 'duplicate_or_reset_rows': int(len(arr) - sum(len(s['idx']) for s in segs)),
             'seg_rate_hz': [round(1000.0 / s['dt'], 4) for s in segs], 'seg_samples': [int(len(s['idx'])) for s in segs],
             'index_gaps': int(sum((np.diff(s['idx']) > 1).sum() for s in segs)),
             'missing_samples_in_gaps': int(sum((np.diff(s['idx'])[np.diff(s['idx']) > 1] - 1).sum() for s in segs)),
             'rate_field': [s['rate_field'] for s in segs]}
        # arrival-time windowing (what hr_prepare.py did): samples arriving in 10 s of backend time
        counts = []
        for s in segs:
            tb = s['backend']
            for e in np.arange(tb.min() + 10_000, tb.max(), 5_000):
                counts.append(int(((tb > e - 10_000) & (tb <= e)).sum()))
        counts = np.array(counts)
        c['arrival_window_counts'] = {'n': int(len(counts)), 'p5': float(np.percentile(counts, 5)), 'median': float(np.median(counts)),
                                      'p95': float(np.percentile(counts, 95)), 'share_outside_240_260': float(np.mean((counts < 240) | (counts > 260)))}
        for ch in CHANNELS:
            v = np.concatenate([s[ch] for s in segs])
            c[f'{ch}_range'] = [float(np.nanmin(v)), float(np.nanmax(v))]
            c[f'{ch}_at_24bit_limits'] = float(np.mean((v <= 0) | (v >= 2 ** 24 - 1)))
            c[f'{ch}_share_equal_max'] = float(np.mean(v == np.nanmax(v)))
        checks[sid] = c
        print(sid, json.dumps(c), flush=True)
        for k, s in enumerate(segs):
            for p in hb.contiguous_windows(s['idx'], WIN_N, STEP_N):
                sl = slice(p, p + WIN_N)
                vals = {ch: s[ch][sl] for ch in CHANNELS}
                if not all(np.isfinite(v).all() and v.std() > 1e-6 for v in vals.values()):
                    continue
                centre = s['t0'] + s['dt'] * (s['idx'][p] + WIN_N / 2)
                acc = s['acc'][sl]
                mag = np.sqrt((acc ** 2).sum(1)) if np.isfinite(acc).all() else None
                rec['session'].append(sid); rec['centre'].append(centre); rec['seg'].append(k)
                rec['acc_sd'].append(float(mag.std()) if mag is not None else np.nan)
                for ch in CHANNELS:
                    raw[ch].append(vals[ch])
                if mag is not None:
                    f, pa = hb.spectrum(hb.bandpass(mag, FS_RING), FS_RING)
                    acc_spec_peaks.append(hb.band_peaks(f, pa, k=2)[0])
                else:
                    acc_spec_peaks.append(np.array([]))
    n = len(rec['session'])
    print(f'{n} ring windows', flush=True)
    out = {k: np.array(v) for k, v in rec.items()}
    for ch in CHANNELS:
        W = np.array(raw[ch])
        out[f'raw_{ch}'] = W.astype(np.float32)
        res = {m: np.full(n, np.nan) for m in ('welch', 'welch_acc', 'acf', 'nk2', 'heartpy', 'sqi', 'rf_ratio', 'skew')}
        spec, cands = [], []
        for i in range(n):
            xf = hb.bandpass(W[i], FS_RING)
            f, p = hb.spectrum(xf, FS_RING)
            pk, pw = hb.band_peaks(f, p)
            res['welch'][i] = pk[0]
            pk2, _ = hb.suppress_near(pk, pw, acc_spec_peaks[i])
            res['welch_acc'][i] = pk2[0]
            cands.append(pk2[:3])
            res['acf'][i] = hb.acf_hr(xf, FS_RING)
            res['sqi'][i] = hb.sqi(f, p)
            res['rf_ratio'][i] = hb.rise_fall_ratio(xf, FS_RING)
            res['skew'][i] = float(((xf - xf.mean()) ** 3).mean() / (xf.std() ** 3 + 1e-12))
            m = (f >= 30) & (f <= 240)
            spec.append(p[m] / (p[m].sum() + 1e-300))
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                try:
                    sig, _ = nk.ppg_process(W[i], sampling_rate=int(FS_RING))
                    pk_i = np.where(sig['PPG_Peaks'].values == 1)[0]
                    if len(pk_i) >= 3:
                        res['nk2'][i] = 60.0 * FS_RING / np.median(np.diff(pk_i))
                except Exception:
                    pass
                try:
                    _, mm = hp.process(xf, FS_RING)
                    if np.isfinite(mm['bpm']) and 30 <= mm['bpm'] <= 240:
                        res['heartpy'][i] = mm['bpm']
                except Exception:
                    pass
        out[f'spec_{ch}'] = np.array(spec, np.float32)
        for m, v in res.items():
            out[f'{m}_{ch}'] = v
        # trackers run per contiguous run of windows (same session and segment, 5 s apart)
        g = np.full(n, np.nan)
        vt = np.full(n, np.nan)
        fgrid = np.fft.rfftfreq(hb.NFFT, 1 / FS_RING) * 60
        fgrid = fgrid[(fgrid >= 30) & (fgrid <= 240)]
        for run in runs(out['session'], out['seg'], out['centre'], 5_000):
            g[run] = hb.track_greedy([cands[i] for i in run])
            vt[run] = hb.track_viterbi(fgrid, out[f'spec_{ch}'][run], sigma=8.0)
        out[f'track_greedy_{ch}'] = g
        out[f'track_viterbi_{ch}'] = vt
        for sign in (1, -1):
            out[f'emb_{ch}_{"pos" if sign > 0 else "neg"}'] = embed(sign * W, FS_RING)
        print(f'channel {ch} done', flush=True)
    out['spec_f'] = fgrid
    np.savez_compressed(os.path.join(CACHE, 'ring_windows.npz'), **out)
    with open(os.path.join(CACHE, 'ring_checks.json'), 'w') as f:
        json.dump(checks, f, indent=1)


def runs(session, seg, centre, step_ms):
    """Index lists of consecutive windows (same session and segment, centres step_ms apart)."""
    order = np.lexsort((centre, seg, session))
    out, cur = [], [order[0]]
    for a, b in zip(order[:-1], order[1:]):
        if session[a] == session[b] and seg[a] == seg[b] and abs(centre[b] - centre[a] - step_ms) < 0.25 * step_ms:
            cur.append(b)
        else:
            out.append(np.array(cur)); cur = [b]
    out.append(np.array(cur))
    return out


def check_worker(args):
    """Compare local ONNX embeddings with the running worker container on 30 ring windows."""
    import train_head_public as tp
    z = np.load(os.path.join(CACHE, 'ring_windows.npz'))
    rng = np.random.default_rng(SEED)
    pick = rng.choice(len(z['session']), 30, replace=False)
    W = z['raw_green'][pick].astype(np.float64)
    local = embed(W, FS_RING)
    remote = tp.embed_windows(compose(args.data_root), W, procs=1, chunk=30)
    ok = np.isfinite(remote).all(1)
    diff = np.abs(local[ok] - remote[ok]).max()
    cos = np.mean([np.dot(a, b) / np.linalg.norm(a) / np.linalg.norm(b) for a, b in zip(local[ok], remote[ok])])
    res = {'windows': int(ok.sum()), 'max_abs_diff': float(diff), 'mean_cosine': float(cos)}
    print(res)
    with open(os.path.join(CACHE, 'worker_check.json'), 'w') as f:
        json.dump(res, f)


# ---------------------------------------------------------------- DaLiA

def load_dalia(path):
    import train_head_public as tp
    with open(path, 'rb') as f:
        d = tp.NumpyOnlyUnpickler(f, encoding='latin1').load()
    return {'bvp': np.asarray(d['signal']['wrist']['BVP'], float).reshape(-1),
            'acc': np.asarray(d['signal']['wrist']['ACC'], float).reshape(-1, 3),
            'hr': np.asarray(d['label'], float).reshape(-1), 'activity': np.asarray(d['activity']).reshape(-1)}


def dalia(args):
    import neurokit2 as nk
    import heartpy as hp
    from scipy.signal import resample_poly
    out = {k: [] for k in ('subject', 'label', 'activity', 'tsec')}
    per = {}
    for sub in range(1, 16):
        d = load_dalia(os.path.join(DALIA_DIR, f'S{sub}', f'S{sub}.pkl'))
        b64 = d['bvp']
        b25 = resample_poly(b64, 25, 64)
        acc = np.sqrt((d['acc'] ** 2).sum(1))
        idx = [i for i in range(0, len(d['hr']), 2) if 2 * i - 1 >= 0 and (2 * i + 9) * 64 <= len(b64)]
        for i in idx:
            a = 2 * i - 1
            t = a + 5
            act = d['activity'][min(int(t * 4), len(d['activity']) - 1)]
            out['subject'].append(sub); out['label'].append(d['hr'][i]); out['activity'].append(int(act)); out['tsec'].append(a)
            per.setdefault('w64', []).append(b64[a * 64:(a + 10) * 64])
            per.setdefault('w25', []).append(b25[a * 25:(a + 10) * 25])
            per.setdefault('acc', []).append(acc[a * 32:(a + 10) * 32])
        print(f'S{sub}: {len(idx)} windows', flush=True)
    z = {k: np.array(v) for k, v in out.items()}
    W64, W25, A = np.array(per['w64']), np.array(per['w25']), np.array(per['acc'])
    n = len(z['label'])
    t0 = time.time()
    z['emb_D64'] = embed(W64, 64.0)
    z['emb_D25'] = embed(W25, 25.0)
    z['emb_D25poly'] = embed(W25, 25.0, mode='poly')
    z['emb_D25neg'] = embed(-W25, 25.0)
    print(f'embedded 4 x {n} windows in {time.time() - t0:.0f} s', flush=True)
    for cond, W, fs in (('D64', W64, 64.0), ('D25', W25, 25.0)):
        res = {m: np.full(n, np.nan) for m in ('welch', 'welch_acc', 'acf', 'nk2', 'heartpy', 'sqi', 'rf_ratio', 'snr')}
        cands, spec = [], []
        for i in range(n):
            xf = hb.bandpass(W[i], fs)
            f, p = hb.spectrum(xf, fs)
            pk, pw = hb.band_peaks(f, p)
            fa, pa = hb.spectrum(hb.bandpass(A[i], 32.0), 32.0)
            mpk = hb.band_peaks(fa, pa, k=2)[0]
            pk2, _ = hb.suppress_near(pk, pw, mpk)
            res['welch'][i], res['welch_acc'][i] = pk[0], pk2[0]
            cands.append(pk2[:3])
            res['acf'][i] = hb.acf_hr(xf, fs)
            res['sqi'][i] = hb.sqi(f, p)
            res['snr'][i] = hb.snr_at(f, p, z['label'][i])
            res['rf_ratio'][i] = hb.rise_fall_ratio(xf, fs)
            m = (f >= 30) & (f <= 240)
            spec.append(p[m] / (p[m].sum() + 1e-300))
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                try:
                    sig, _ = nk.ppg_process(W[i], sampling_rate=int(fs))
                    pk_i = np.where(sig['PPG_Peaks'].values == 1)[0]
                    if len(pk_i) >= 3:
                        res['nk2'][i] = 60.0 * fs / np.median(np.diff(pk_i))
                except Exception:
                    pass
                try:
                    _, mm = hp.process(xf, fs)
                    if np.isfinite(mm['bpm']) and 30 <= mm['bpm'] <= 240:
                        res['heartpy'][i] = mm['bpm']
                except Exception:
                    pass
        fgrid = f[(f >= 30) & (f <= 240)]
        spec = np.array(spec, np.float32)
        g, vt = np.full(n, np.nan), np.full(n, np.nan)
        for sub in range(1, 16):
            sel = np.where(z['subject'] == sub)[0]
            g[sel] = hb.track_greedy([cands[i] for i in sel])
            vt[sel] = hb.track_viterbi(fgrid, spec[sel], sigma=8.0 * 4 / 5)
        res['track_greedy'], res['track_viterbi'] = g, vt
        for m, v in res.items():
            z[f'{m}_{cond}'] = v
        print(f'{cond} classical done ({time.time() - t0:.0f} s)', flush=True)
    np.savez_compressed(os.path.join(CACHE, 'dalia_windows.npz'), **z)


# ---------------------------------------------------------------- synthetic

def synthetic(args):
    rng = np.random.default_rng(SEED)
    n = 1500
    bpm = rng.uniform(40, 180, n)
    w125 = np.array([hb.synthetic_ppg(b, 125.0, rng=np.random.default_rng(i)) for i, b in enumerate(bpm)])
    w25 = np.array([hb.synthetic_ppg(b, 25.0, rng=np.random.default_rng(i)) for i, b in enumerate(bpm)])
    z = {'bpm': bpm, 'emb125': embed(w125, 125.0), 'emb25': embed(w25, 25.0), 'emb25neg': embed(-w25, 25.0)}
    z['welch25'] = np.array([hb.band_peaks(*hb.spectrum(hb.bandpass(w, 25.0), 25.0), k=1)[0][0] for w in w25])
    np.savez_compressed(os.path.join(CACHE, 'synthetic.npz'), **z)
    print('synthetic done')


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('stage', choices=['extract', 'check-worker', 'ring', 'dalia', 'synthetic', 'score', 'warm-ring', 'warm-dalia'])
    ap.add_argument('--data-root', default=default_data_root())
    ap.add_argument('--cond', help='warm-dalia: DaLiA condition (D64, D25, D25poly, D25neg)')
    ap.add_argument('--head', help='warm-dalia: head name')
    args = ap.parse_args()
    os.makedirs(CACHE, exist_ok=True)
    if args.stage in ('score', 'warm-ring', 'warm-dalia'):
        import hr_benchmark_score
        if args.stage == 'warm-dalia':     # one DaLiA head/condition into the prediction cache (parallel runs)
            hr_benchmark_score.dalia_pred(np.load(os.path.join(CACHE, 'dalia_windows.npz')), args.cond, args.head)
        elif args.stage == 'warm-ring':      # fills the per-head ring prediction cache in parallel with `score`
            hr_benchmark_score.ring_benchmark(args.data_root, print)
        else:
            hr_benchmark_score.main(args)
        return
    {'extract': extract, 'check-worker': check_worker, 'ring': ring, 'dalia': dalia, 'synthetic': synthetic}[args.stage](args)


if __name__ == '__main__':
    main()
