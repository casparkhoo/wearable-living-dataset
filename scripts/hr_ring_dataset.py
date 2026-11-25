#!/usr/bin/env python3
"""HealthRing public ring dataset (Zenodo 18426864, CC BY 4.0): our heart-rate methods against its oximeter HR.
Protocol: outputs/hr_benchmark/PROTOCOL.md, addendum 3 (declared before the data was opened).

The archive is untrusted data. Run with `python -I`, pass the extracted directory as an argument, and nothing from
the archive is executed: pickles are first listed opcode by opcode (pickletools, no execution) and then loaded by a
restricted unpickler that only resolves the numpy/pandas data constructors the listing showed. RingTool code is
not used.

    python -I scripts/hr_ring_dataset.py inspect <extracted dir>
    python -I scripts/hr_ring_dataset.py prepare <extracted dir>     # windows, classical methods, embeddings (cache)
    python -I scripts/hr_ring_dataset.py score                       # heads, tables -> outputs/hr_benchmark/healthring/
"""
from __future__ import annotations

import argparse
import collections
import glob
import json
import os
import pickle
import pickletools
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def pickle_globals(path):
    """Every (module, name) a pickle would import, read from its opcodes without executing it."""
    found = collections.Counter()
    strings = []
    with open(path, 'rb') as f:
        for op, arg, _ in pickletools.genops(f):
            if op.name in ('SHORT_BINUNICODE', 'BINUNICODE', 'UNICODE', 'BINUNICODE8'):
                strings.append(arg)
            if op.name == 'GLOBAL':
                found[tuple(arg.split(' ', 1))] += 1
            elif op.name == 'STACK_GLOBAL' and len(strings) >= 2:
                found[(strings[-2], strings[-1])] += 1
    return found


class DataOnlyUnpickler(pickle.Unpickler):
    """Resolves only the pandas/numpy constructors that inspect() found in the release's pickles."""
    ALLOWED = {
        ('pandas.core.frame', 'DataFrame'), ('pandas.core.internals.managers', 'BlockManager'),
        ('pandas._libs.internals', '_unpickle_block'), ('pandas.core.indexes.base', '_new_Index'),
        ('pandas.core.indexes.base', 'Index'), ('pandas.core.indexes.range', 'RangeIndex'),
        ('numpy.core.numeric', '_frombuffer'), ('numpy._core.numeric', '_frombuffer'),
        ('numpy.core.multiarray', '_reconstruct'), ('numpy._core.multiarray', '_reconstruct'),
        ('numpy.core.multiarray', 'scalar'), ('numpy._core.multiarray', 'scalar'),
        ('numpy', 'ndarray'), ('numpy', 'dtype'), ('builtins', 'slice'),
    }

    def find_class(self, module, name):
        if (module, name) in self.ALLOWED:
            if module.startswith('numpy.core.'):
                module = 'numpy._core.' + module[len('numpy.core.'):]
            return super().find_class(module, name)
        raise pickle.UnpicklingError(f'blocked global {module}.{name}')


def load_pickle(path):
    with open(path, 'rb') as f:
        return DataOnlyUnpickler(f).load()


def inspect(root):
    files = sorted(glob.glob(os.path.join(root, '**', '*'), recursive=True))
    kinds = collections.Counter(os.path.splitext(f)[1].lower() for f in files if os.path.isfile(f))
    print('file types:', dict(kinds))
    for f in files[:15]:
        print('  ', os.path.relpath(f, root), os.path.getsize(f) if os.path.isfile(f) else 'dir')
    pk = [f for f in files if f.lower().endswith(('.pkl', '.pickle', '.p'))]
    allg = collections.Counter()
    for f in pk:
        allg.update(pickle_globals(f))
    print('globals referenced by', len(pk), 'pickles:')
    for g, n in allg.most_common():
        print('  ', g, n)


def safe_extract(zip_path, dest):
    """Extract into a new, empty directory, refusing absolute paths, drive letters and '..' components."""
    import zipfile
    os.makedirs(dest, exist_ok=True)
    if os.listdir(dest):
        raise SystemExit(f'{dest} is not empty')
    with zipfile.ZipFile(zip_path) as z:
        infos = z.infolist()
        for i in infos:
            parts = i.filename.replace('\\', '/').split('/')
            if i.filename.startswith(('/', '\\')) or ':' in i.filename or '..' in parts:
                raise SystemExit(f'unsafe path in archive: {i.filename!r}')
        print(len(infos), 'entries,', round(sum(i.file_size for i in infos) / 1e9, 2), 'GB uncompressed')
        print(collections.Counter(os.path.splitext(i.filename)[1].lower() for i in infos))
        for i in infos[:30]:
            print('  ', i.filename, i.file_size)
        z.extractall(dest)


def stream_extract(zip_path, dest):
    """The published archive (md5 6d96016e45a311b1cfc9108d3df23195, matching Zenodo) has local file headers but no
    central directory, so zipfile cannot open it. Read it entry by entry from the local headers instead
    (deflate or stored; sizes from the header or, with flag bit 3, from the data descriptor). Same path checks.
    An entry cut off at the end of the file is reported and skipped."""
    import struct
    import zlib
    os.makedirs(dest, exist_ok=True)
    if os.listdir(dest):
        raise SystemExit(f'{dest} is not empty')
    report = []
    with open(zip_path, 'rb') as f:
        while True:
            head = f.read(30)
            if len(head) < 30 or head[:4] != b'PK\x03\x04':
                break
            _, _, flags, method, _, _, crc, csize, usize, nlen, xlen = struct.unpack('<IHHHHHIIIHH', head)
            name = f.read(nlen).decode('utf-8', 'replace')
            extra = f.read(xlen)
            if csize == 0xFFFFFFFF or usize == 0xFFFFFFFF:          # zip64 sizes in the extra field
                k = 0
                while k + 4 <= len(extra):
                    hid, hlen = struct.unpack('<HH', extra[k:k + 4])
                    if hid == 1:
                        vals = struct.unpack('<' + 'Q' * (hlen // 8), extra[k + 4:k + 4 + hlen - hlen % 8])
                        if usize == 0xFFFFFFFF:
                            usize, vals = vals[0], vals[1:]
                        if csize == 0xFFFFFFFF and vals:
                            csize = vals[0]
                    k += 4 + hlen
            parts = name.replace('\\', '/').split('/')
            if name.startswith(('/', '\\')) or ':' in name or '..' in parts:
                raise SystemExit(f'unsafe path in archive: {name!r}')
            out_path = os.path.join(dest, *[p for p in parts if p])
            is_dir = name.endswith('/')
            if is_dir:
                os.makedirs(out_path, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(out_path), exist_ok=True)
            ok, written = True, 0
            if method == 8 and (flags & 8 or csize == 0):
                d = zlib.decompressobj(-15)
                with open(out_path, 'wb') if not is_dir else open(os.devnull, 'wb') as o:
                    while not d.eof:
                        chunk = f.read(1 << 20)
                        if not chunk:
                            ok = False
                            break
                        o.write(d.decompress(chunk)); written += 0
                    back = len(d.unused_data)
                f.seek(-back, 1)
                if flags & 8 and ok:
                    sig = f.read(4)
                    if sig == b'PK\x07\x08':
                        f.read(20 if usize == 0xFFFFFFFF else 12)
                    else:
                        f.read(8)   # descriptor without signature (crc, csize, usize); zip64 not handled here
            else:
                remaining = csize
                d = zlib.decompressobj(-15) if method == 8 else None
                with open(out_path, 'wb') if not is_dir else open(os.devnull, 'wb') as o:
                    while remaining > 0:
                        chunk = f.read(min(1 << 20, remaining))
                        if not chunk:
                            ok = False
                            break
                        remaining -= len(chunk)
                        o.write(d.decompress(chunk) if d else chunk)
            report.append((name, ok, os.path.getsize(out_path) if not is_dir and os.path.exists(out_path) else 0))
            if not ok:
                os.remove(out_path)
                break
    for n, ok, sz in report:
        print(('ok  ' if ok else 'CUT ') + n, sz)
    with open(os.path.join(dest, '_extract_report.json'), 'w') as fo:
        json.dump(report, fo, indent=1)


# ---------------------------------------------------------------- prepare

import numpy as np   # noqa: E402

CACHE = os.path.join(os.path.expanduser('~'), 'data_public', 'cache_healthring')
OUT = os.path.join(HERE, '..', 'outputs', 'hr_benchmark', 'healthring')
SEG_S = 30.0
FS_LO, FS_HI = 90.0, 110.0          # segments whose stored rate is far from the nominal 100 Hz are left out
CHANNELS = ('ir', 'red')
RATES = (100, 25)
CLASSICAL = ('welch', 'welch_acc', 'acf', 'nk2', 'heartpy')
MOTION_WORDS = ('strid', 'walk', 'run', 'squat', 'head', 'exercise')


def motion_group(label):
    """Addendum 3: motion = a label naming walking, running, squats, head movement or exercise; stationary = the rest."""
    lab = str(label).lower()
    return 'motion' if any(w in lab for w in MOTION_WORDS) else 'stationary'


def to_uniform(x, fs, fs_out, seconds=SEG_S):
    """Treat the stored samples as evenly spaced over the segment and put them on an fs_out grid. 100 Hz from a
    ~100 Hz segment is linear interpolation; 25 Hz is then an anti-aliased polyphase decimation by 4."""
    from scipy.signal import resample_poly
    x = np.asarray(x, float)
    n100 = int(round(100 * seconds))
    u = np.interp(np.linspace(0, len(x) - 1, n100), np.arange(len(x)), x)
    # padtype='line': the raw counts sit on a large offset (about 6e5); the default zero padding would put a step at
    # both ends of the segment and its filter ringing would dominate the 25 Hz signal (found in the first run).
    return u if fs_out == 100 else resample_poly(u, 1, 4, padtype='line')


def segment_features(ppg, acc_mag, fs):
    """Classical estimates over the whole segment, and the rise fraction, for one channel at one rate."""
    import warnings
    import hr_bench_common as hb
    out = {}
    xf = hb.bandpass(ppg, fs)
    f, p = hb.spectrum(xf, fs)
    pk, pw = hb.band_peaks(f, p)
    fa, pa = hb.spectrum(hb.bandpass(acc_mag, fs), fs)
    pk2, _ = hb.suppress_near(pk, pw, hb.band_peaks(fa, pa, k=2)[0])
    out['welch'], out['welch_acc'] = float(pk[0]), float(pk2[0])
    out['acf'] = hb.acf_hr(xf, fs)
    out['sqi'] = hb.sqi(f, p)
    out['rise'] = hb.rise_fall_ratio(xf, fs)
    out['nk2'] = out['heartpy'] = float('nan')
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        try:
            import neurokit2 as nk
            sig, _ = nk.ppg_process(ppg, sampling_rate=int(fs))
            idx = np.where(sig['PPG_Peaks'].values == 1)[0]
            if len(idx) >= 3:
                out['nk2'] = float(60.0 * fs / np.median(np.diff(idx)))
        except Exception:
            pass
        try:
            import heartpy as hp
            _, mm = hp.process(xf, fs)
            if np.isfinite(mm['bpm']) and 30 <= mm['bpm'] <= 240:
                out['heartpy'] = float(mm['bpm'])
        except Exception:
            pass
    return out


def prepare_file(path):
    """All usable segments of one subject/ring file: metadata, classical estimates, 10 s sub-windows."""
    name = os.path.basename(path)
    subject, ring = int(name.split('_')[0]), name.split('_')[1]
    df = load_pickle(path)
    rows = []
    for _, r in df.iterrows():
        try:
            hr, fs = float(r['hr']), float(r['fs'])
        except (TypeError, ValueError):
            continue
        if not np.isfinite(hr) or not (FS_LO <= fs <= FS_HI):
            continue
        n = len(r['ir-raw'])
        if n < 0.95 * fs * SEG_S or any(len(r[c]) != n for c in ('red-raw', 'ax-raw', 'ay-raw', 'az-raw')):
            continue
        acc = np.sqrt(sum(np.asarray(r[c], float) ** 2 for c in ('ax-raw', 'ay-raw', 'az-raw')))
        rec = {'subject': subject, 'ring': ring, 'label': str(r['Label']), 'experiment': str(r['Experiment']),
               'hr': hr, 'fs': fs, 'wins': {}}
        for rate in RATES:
            a = to_uniform(acc, fs, rate)
            for ch in CHANNELS:
                x = to_uniform(r[f'{ch}-raw'], fs, rate)
                for k, v in segment_features(x, a, rate).items():
                    rec[f'{k}_{ch}_{rate}'] = v
                w = int(10 * rate)
                rec['wins'][(ch, rate)] = np.stack([x[i * w:(i + 1) * w] for i in range(3)]).astype(np.float32)
        rows.append(rec)
    return name, rows


def prepare(root):
    from concurrent.futures import ProcessPoolExecutor
    import hr_benchmark as B
    os.makedirs(CACHE, exist_ok=True)
    files = sorted(glob.glob(os.path.join(root, '*_processed.pkl')))
    rows, per_file = [], {}
    with ProcessPoolExecutor(max_workers=12) as ex:
        for name, rr in ex.map(prepare_file, files):
            per_file[name] = len(rr)
            rows += rr
            print(name, len(rr), flush=True)
    meta = {k: np.array([r[k] for r in rows]) for k in rows[0] if k != 'wins'}
    for ch in CHANNELS:
        for rate in RATES:
            W = np.concatenate([r['wins'][(ch, rate)] for r in rows])        # 3 sub-windows per segment
            for sign, s in (('pos', 1), ('neg', -1)):
                meta[f'emb_{ch}_{rate}_{sign}'] = B.embed(s * W.astype(float), float(rate))
            print('embedded', ch, rate, flush=True)
    np.savez_compressed(os.path.join(CACHE, 'segments.npz'), **meta)
    with open(os.path.join(CACHE, 'per_file.json'), 'w') as f:
        json.dump(per_file, f, indent=1)


# ---------------------------------------------------------------- score

def score():
    import hr_bench_common as hb
    import hr_benchmark as B
    import hr_benchmark_score as S
    z = dict(np.load(os.path.join(CACHE, 'segments.npz'), allow_pickle=False))
    subj, ring, y = z['subject'], z['ring'], z['hr'].astype(float)
    group = np.array([motion_group(l) for l in z['label']])
    subjects = sorted(set(subj.tolist()))
    fold = np.array([subjects.index(s) % 5 for s in subj])
    out = {'segments': {r: int((ring == r).sum()) for r in ('ring1', 'ring2')},
           'subjects': {r: int(len(set(subj[ring == r]))) for r in ('ring1', 'ring2')},
           'labels': {f'{r}|{l}': int(((ring == r) & (z['label'] == l)).sum()) for r in ('ring1', 'ring2') for l in sorted(set(z['label']))},
           'rows': {}, 'signs': {}}

    def metrics(est, m):
        d = {}
        for g, gm in (('all', np.ones(len(y), bool)), ('stationary', group == 'stationary'), ('motion', group == 'motion')):
            k = m & gm
            d[g] = hb.window_metrics(est[k], y[k])
        return d

    def per_subject_mae(est, m):
        return {int(s): float(np.nanmean(np.abs(est[m & (subj == s)] - y[m & (subj == s)])))
                for s in subjects if (m & (subj == s) & np.isfinite(est)).any()}

    # constant: training-fold mean
    const = np.full(len(y), np.nan)
    for k in range(5):
        const[fold == k] = y[fold != k].mean()
    for r in ('ring1', 'ring2'):
        m = ring == r
        out['rows'][f'{r}|constant'] = metrics(const, m)
        for ch in CHANNELS:
            for rate in RATES:
                for meth in CLASSICAL:
                    e = z[f'{meth}_{ch}_{rate}']
                    out['rows'][f'{r}|{ch}|{rate}|{meth}'] = metrics(e, m)
                    out['rows'][f'{r}|{ch}|{rate}|{meth}']['per_subject_motion'] = per_subject_mae(e, m & (group == 'motion'))
    # PaPaGei heads, subject-held-out; sign per fold from the training folds' median rise fraction (label-free)
    dz = np.load(os.path.join(B.CACHE, 'dalia_windows.npz'))
    dalia = {}
    for rate, key in ((100, 'emb_D64'), (25, 'emb_D25')):
        X = dz[key]; ok = np.isfinite(X).all(1)
        dalia[rate] = S.fit_head('ridge', X[ok], dz['label'][ok], dz['subject'][ok], np.random.default_rng(0))
    v2 = np.load(os.path.join(B.HEADS, 'hr_ring_stretch_v2.npz'))
    for r in ('ring1', 'ring2'):
        m = ring == r
        for ch in CHANNELS:
            for rate in RATES:
                rise = z[f'rise_{ch}_100']
                preds = {h: np.full(len(y), np.nan) for h in S.HEADS}
                preds['dalia_ridge'] = np.full(len(y), np.nan)
                signs = {}
                for k in range(5):
                    tr, te = m & (fold != k), m & (fold == k)
                    if te.sum() == 0:
                        continue
                    sign = 'neg' if np.nanmedian(rise[tr]) > 0.5 else 'pos'
                    signs[k] = sign
                    E = z[f'emb_{ch}_{rate}_{sign}'].reshape(len(y), 3, 512)
                    trs = np.where(tr)[0]
                    Xtr = E[trs].reshape(-1, 512); ytr = np.repeat(y[trs], 3); gtr = np.repeat(subj[trs], 3)
                    okr = np.isfinite(Xtr).all(1)
                    tes = np.where(te)[0]
                    Xte = E[tes].reshape(-1, 512)
                    okt = np.isfinite(Xte).all(1)
                    for h in S.HEADS:
                        mdl = S.fit_head(h, Xtr[okr], ytr[okr], gtr[okr], np.random.default_rng(k))
                        p = np.full(len(Xte), np.nan); p[okt] = mdl.predict(Xte[okt])
                        preds[h][tes] = np.nanmedian(p.reshape(-1, 3), 1)
                    p = np.full(len(Xte), np.nan); p[okt] = dalia[rate].predict(Xte[okt])
                    preds['dalia_ridge'][tes] = np.nanmedian(p.reshape(-1, 3), 1)
                out['signs'][f'{r}|{ch}|{rate}'] = signs
                for h, e in preds.items():
                    key = f'{r}|{ch}|{rate}|papagei_{h}'
                    out['rows'][key] = metrics(e, m)
                    out['rows'][key]['per_subject_motion'] = per_subject_mae(e, m & (group == 'motion'))
                if rate == 25:   # the live head, as trained (raw ring counts), as a transfer check
                    E = z[f'emb_{ch}_25_pos'].reshape(len(y), 3, 512).astype(np.float64)
                    e = np.nanmedian(E @ v2['weights'].reshape(-1) + float(np.asarray(v2['bias']).reshape(-1)[0]), 1)
                    out['rows'][f'{r}|{ch}|25|hr_ring_stretch_v2'] = metrics(np.where(m, e, np.nan), m)
                print(r, ch, rate, 'heads done', flush=True)
    # per-label table for the best classical and best PaPaGei rows at both rates
    out['per_label'] = {}
    for key in list(out['rows']):
        if key.endswith(('|welch_acc', '|nk2', '|papagei_ridge', '|papagei_bins', '|constant')):
            r = key.split('|')[0]
            parts = key.split('|')
            if parts[-1] == 'constant':
                e = const
            elif parts[-1].startswith('papagei'):
                continue
            else:
                e = z[f'{parts[3]}_{parts[1]}_{parts[2]}']
            out['per_label'][key] = {l: hb.window_metrics(e[(ring == r) & (z['label'] == l)], y[(ring == r) & (z['label'] == l)])
                                     for l in sorted(set(z['label']))}
    # Declared test: does 25 Hz cost accuracy in motion? Best method at 100 Hz in motion per ring and channel,
    # the same method at 25 Hz, and the per-subject paired difference.
    out['rate_cost_motion'] = {}
    for r in ('ring1', 'ring2'):
        for ch in CHANNELS:
            cands = [(d['motion'].get('mae', np.inf), k) for k, d in out['rows'].items()
                     if k.startswith(f'{r}|{ch}|100|') and d['motion'].get('n', 0) and d['all'].get('coverage', 0) >= 0.9]
            if not cands:
                continue
            mae100, k100 = min(cands)
            k25 = k100.replace('|100|', '|25|')
            a, b = out['rows'][k100]['per_subject_motion'], out['rows'][k25].get('per_subject_motion', {})
            common = [s for s in a if s in b and np.isfinite(a[s]) and np.isfinite(b[s])]
            diff = [b[s] - a[s] for s in common]
            out['rate_cost_motion'][f'{r}|{ch}'] = {
                'method': k100.split('|')[-1], 'mae_motion_100': mae100, 'mae_motion_25': out['rows'][k25]['motion'].get('mae'),
                'coverage_25': out['rows'][k25]['all'].get('coverage'), 'subjects': len(common),
                'subjects_worse_at_25': int(sum(d > 0 for d in diff)), 'median_subject_diff': float(np.median(diff)) if diff else None,
                'claim_25hz_costs': bool(out['rows'][k25]['motion'].get('mae', 0) - mae100 > 1 and sum(d > 0 for d in diff) > len(diff) / 2)}
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'results.json'), 'w') as f:
        json.dump(out, f, separators=(',', ':'), default=float)
    write_tables(out)


def write_tables(out):
    f1 = lambda x, d=1: '' if x is None or not np.isfinite(x) else f'{x:.{d}f}'
    L = ['# HealthRing ring dataset: heart rate vs the oximeter (generated by scripts/hr_ring_dataset.py)', '',
         'Protocol: PROTOCOL.md addendum 3. Per 30 s segment; PaPaGei = median of three 10 s sub-windows; heads trained '
         'with subjects held out (5 folds). Motion = striding, deep squat, shaking head; stationary = every other label.', '',
         f"Segments used: {out['segments']}; subjects: {out['subjects']}.", '',
         '| ring | channel | rate (Hz) | method | segments (coverage) | MAE all | MAE stationary | MAE motion | bias | r | within 10 (%) |',
         '|---|---|---|---|---|---|---|---|---|---|---|']
    for k, d in out['rows'].items():
        p = k.split('|')
        if len(p) == 2:
            p = [p[0], '', '', p[1]]
        a = d['all']
        L.append(f"| {p[0]} | {p[1]} | {p[2]} | {p[3]} | {a.get('n', 0)} ({100 * a.get('coverage', 0):.0f}%) | {f1(a.get('mae'))} | "
                 f"{f1(d['stationary'].get('mae'))} | {f1(d['motion'].get('mae'))} | {f1(a.get('bias'))} | {f1(a.get('r'), 2)} | {f1(a.get('within10'), 0)} |")
    L += ['', '## Per label (MAE, segments)', '']
    labels = sorted({l for v in out['per_label'].values() for l in v})
    L += ['| row | ' + ' | '.join(labels) + ' |', '|---|' + '---|' * len(labels)]
    for k, v in out['per_label'].items():
        L.append(f'| {k} | ' + ' | '.join(f"{f1(v[l].get('mae'))} ({v[l].get('n', 0)})" for l in labels) + ' |')
    L += ['', '## Does 25 Hz cost accuracy in motion? (declared test)', '',
          '| ring / channel | best method at 100 Hz (motion) | MAE motion 100 Hz | MAE motion 25 Hz | constant MAE motion | 25 Hz coverage | subjects worse at 25 Hz | median per-subject change | 25 Hz costs? |',
          '|---|---|---|---|---|---|---|---|---|']
    for k, d in out.get('rate_cost_motion', {}).items():
        cm = out['rows'][f"{k.split('|')[0]}|constant"]['motion'].get('mae')
        L.append(f"| {k} | {d['method']} | {f1(d['mae_motion_100'])} | {f1(d['mae_motion_25'])} | {f1(cm)} | {100 * (d['coverage_25'] or 0):.0f}% | "
                 f"{d['subjects_worse_at_25']} of {d['subjects']} | {f1(d['median_subject_diff'])} | {'yes' if d['claim_25hz_costs'] else 'no'} |")
    L += ['', 'In motion nothing beats the constant by much at either rate, so this test cannot show a rate cost there.', '',
          '## Post hoc (not declared): stationary segments, 100 vs 25 Hz', '',
          '| ring / channel | method | MAE stationary 100 Hz | MAE stationary 25 Hz | constant MAE stationary |', '|---|---|---|---|---|']
    for r in ('ring1', 'ring2'):
        cs = out['rows'][f'{r}|constant']['stationary'].get('mae')
        for ch in CHANNELS:
            for meth in ('welch', 'nk2', 'heartpy', 'papagei_svr', 'papagei_ridge'):
                a, b = out['rows'].get(f'{r}|{ch}|100|{meth}'), out['rows'].get(f'{r}|{ch}|25|{meth}')
                if a and b:
                    L.append(f"| {r} / {ch} | {meth} | {f1(a['stationary'].get('mae'))} | {f1(b['stationary'].get('mae'))} | {f1(cs)} |")
    L += ['', 'Sign chosen per fold (training folds\' median rise fraction): ' + json.dumps(out['signs'])]
    with open(os.path.join(OUT, 'tables.md'), 'w', encoding='utf8') as f:
        f.write('\n'.join(L) + '\n')


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('stage', choices=['extract', 'inspect', 'prepare', 'score'])
    ap.add_argument('root', nargs='?')
    ap.add_argument('--zip')
    args = ap.parse_args()
    if args.stage == 'extract':
        import zipfile
        try:
            zipfile.ZipFile(args.zip).close()
            safe_extract(args.zip, args.root)
        except zipfile.BadZipFile:
            print('no central directory; reading local headers instead')
            stream_extract(args.zip, args.root)
    elif args.stage == 'inspect':
        inspect(args.root)
    elif args.stage == 'prepare':
        prepare(args.root)
    else:
        score()


if __name__ == '__main__':
    main()
