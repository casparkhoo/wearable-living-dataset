"""Pure helpers for scripts/hr_benchmark.py (heart-rate benchmark of the ring, PaPaGei and classical methods).

No database, network or model access here; tested offline in scripts/tests/test_hr_bench_common.py.
"""
from __future__ import annotations

import numpy as np
from scipy.signal import butter, find_peaks, sosfiltfilt

BAND_BPM = (40.0, 200.0)
NFFT = 4096


# ---------------------------------------------------------------- ring timeline

def split_segments(index, reset_drop=100):
    """Split a sample-index sequence (in database insertion order) at counter resets: places where the index
    drops by more than reset_drop. Small drops are packets inserted slightly out of order inside a batch (seen
    in the October and early-November recordings) and are sorted out later. Returns (start, stop) slices."""
    index = np.asarray(index)
    if len(index) == 0:
        return []
    breaks = np.where(np.diff(index) < -reset_drop)[0] + 1
    edges = [0, *breaks.tolist(), len(index)]
    return [(a, b) for a, b in zip(edges[:-1], edges[1:]) if b > a]


def fit_clock(index, t_ms):
    """Least-squares t = t0 + index * dt; returns (t0, dt_ms). Robust to arrival batching because it fits a line."""
    index = np.asarray(index, float)
    t_ms = np.asarray(t_ms, float)
    dt, t0 = np.polyfit(index, t_ms, 1)
    return float(t0), float(dt)


def contiguous_windows(index, n=250, step=125):
    """Start positions p (into the arrays) of windows of n samples whose index runs p..p+n-1 without a gap.
    index must be sorted ascending and unique. Windows start on a global grid of index multiples of `step`."""
    index = np.asarray(index, dtype=np.int64)
    if len(index) < n:
        return []
    pos = {int(v): i for i, v in enumerate(index)}
    first = int(-(-index[0] // step) * step)
    out = []
    for s in range(first, int(index[-1]) - n + 2, step):
        p = pos.get(s)
        if p is None or p + n > len(index):
            continue
        if index[p + n - 1] - index[p] == n - 1:
            out.append(p)
    return out


# ---------------------------------------------------------------- spectra

def bandpass(x, fs, lo=0.5, hi=4.0, order=4):
    hi = min(hi, 0.45 * fs)
    sos = butter(order, [lo, hi], btype='band', fs=fs, output='sos')
    return sosfiltfilt(sos, np.asarray(x, float) - np.mean(x))


def spectrum(x, fs, nfft=NFFT):
    """One-sided power spectrum of a Hann-windowed, already band-passed window. Returns (bpm axis, power)."""
    x = np.asarray(x, float)
    x = (x - x.mean()) * np.hanning(len(x))
    p = np.abs(np.fft.rfft(x, nfft)) ** 2
    f = np.fft.rfftfreq(nfft, 1.0 / fs) * 60.0
    return f, p


def band_peaks(f, p, band=BAND_BPM, k=None):
    """Local maxima of p inside band, sorted by power (descending). Returns array of bpm values."""
    m = (f >= band[0]) & (f <= band[1])
    fb, pb = f[m], p[m]
    idx, _ = find_peaks(pb)
    if len(idx) == 0:
        idx = np.array([int(np.argmax(pb))])
    idx = idx[np.argsort(pb[idx])[::-1]]
    if k is not None:
        idx = idx[:k]
    return fb[idx], pb[idx]


def suppress_near(peaks_bpm, peaks_pow, motion_bpm, tol=6.0):
    """Drop PPG peaks within tol bpm of any motion peak, unless that would drop all of them."""
    keep = np.ones(len(peaks_bpm), bool)
    for mb in motion_bpm:
        keep &= np.abs(peaks_bpm - mb) > tol
    if not keep.any():
        return peaks_bpm, peaks_pow
    return peaks_bpm[keep], peaks_pow[keep]


def sqi(f, p, band=(30.0, 240.0), tol_bpm=6.0):
    """Spectral quality: power within tol of the top in-band peak and of its 2nd harmonic, over power in band."""
    m = (f >= band[0]) & (f <= band[1])
    tot = p[m].sum()
    if tot <= 0:
        return 0.0
    pk, _ = band_peaks(f, p, BAND_BPM, k=1)
    near = (np.abs(f - pk[0]) <= tol_bpm) | (np.abs(f - 2 * pk[0]) <= tol_bpm)
    return float(p[m & near].sum() / tot)


def snr_at(f, p, hr_bpm, band=(30.0, 240.0), tol_bpm=6.0):
    """dB ratio of power within tol of hr and 2*hr to the rest of the band."""
    m = (f >= band[0]) & (f <= band[1])
    near = (np.abs(f - hr_bpm) <= tol_bpm) | (np.abs(f - 2 * hr_bpm) <= tol_bpm)
    a, b = p[m & near].sum(), p[m & ~near].sum()
    if a <= 0 or b <= 0:
        return float('nan')
    return float(10 * np.log10(a / b))


def acf_hr(xf, fs, lag_s=(0.3, 1.5)):
    """Heart rate from the highest autocorrelation peak in the lag range (parabolic interpolation)."""
    x = np.asarray(xf, float) - np.mean(xf)
    n = len(x)
    r = np.correlate(x, x, 'full')[n - 1:]
    if r[0] <= 0:
        return float('nan')
    r = r / r[0]
    lo, hi = int(np.floor(lag_s[0] * fs)), min(int(np.ceil(lag_s[1] * fs)), n - 2)
    seg = r[lo:hi + 1]
    idx, _ = find_peaks(seg)
    if len(idx) == 0:
        return float('nan')
    i = idx[np.argmax(seg[idx])] + lo
    a, b, c = r[i - 1], r[i], r[i + 1]
    den = a - 2 * b + c
    off = 0.5 * (a - c) / den if den != 0 else 0.0
    return float(60.0 * fs / (i + off))


# ---------------------------------------------------------------- trackers over consecutive windows

def track_greedy(cands, tol=15.0):
    """cands: list per window of candidate bpm arrays sorted by power (None or empty = gap).
    Pick the candidate nearest the previous estimate when within tol, else the strongest. Causal."""
    out, prev = [], None
    for c in cands:
        if c is None or len(c) == 0:
            out.append(float('nan')); prev = None
            continue
        c = np.asarray(c, float)
        if prev is not None:
            j = int(np.argmin(np.abs(c - prev)))
            pick = c[j] if abs(c[j] - prev) <= tol else c[0]
        else:
            pick = c[0]
        out.append(float(pick)); prev = pick
    return np.array(out)


def track_viterbi(f, P, sigma=8.0, band=BAND_BPM, grid_step=1.0):
    """Offline Viterbi over a sequence of spectra (rows of P on axis f, bpm). Emission = log of normalised in-band
    power interpolated on a 1 bpm grid; transition = -(d/sigma)^2/2. NaN rows break the chain (each run decoded
    on its own). Returns one bpm per row (NaN for NaN rows)."""
    grid = np.arange(band[0], band[1] + grid_step, grid_step)
    P = np.asarray(P, float)
    out = np.full(len(P), np.nan)
    d = grid[:, None] - grid[None, :]
    trans = -0.5 * (d / sigma) ** 2
    valid = np.isfinite(P).all(1) if P.ndim == 2 else np.array([], bool)
    i = 0
    while i < len(P):
        if not valid[i]:
            i += 1
            continue
        j = i
        while j < len(P) and valid[j]:
            j += 1
        E = []
        for k in range(i, j):
            e = np.interp(grid, f, P[k])
            e = e / (e.sum() + 1e-300)
            E.append(np.log(e + 1e-12))
        E = np.array(E)
        score = E[0].copy()
        back = np.zeros((len(E), len(grid)), int)
        for k in range(1, len(E)):
            tot = score[None, :] + trans          # [to, from]
            back[k] = np.argmax(tot, 1)
            score = tot[np.arange(len(grid)), back[k]] + E[k]
        path = np.zeros(len(E), int)
        path[-1] = int(np.argmax(score))
        for k in range(len(E) - 1, 0, -1):
            path[k - 1] = back[k, path[k]]
        out[i:j] = grid[path]
        i = j
    return out


# ---------------------------------------------------------------- waveform shape

def rise_fall_ratio(xf, fs):
    """Rise fraction: share of samples where the band-passed window is increasing. An upright pleth (volume up)
    rises fast and falls slowly, so the share is below 0.5; an inverted one is above 0.5. (Name kept for the
    cache key; a beat-based rise/fall ratio was dropped before the first run because the dicrotic notch fools it.)"""
    x = np.asarray(xf, float)
    if len(x) < 3:
        return float('nan')
    return float(np.mean(np.diff(x) > 0))


# ---------------------------------------------------------------- synthetic pulses

def synthetic_ppg(bpm, fs, seconds=10.0, jitter=0.02, noise=0.05, rng=None):
    """Pulse wave with a systolic and a smaller, later diastolic Gaussian per beat; beat intervals jittered."""
    rng = np.random.default_rng(0) if rng is None else rng
    t = np.arange(int(round(seconds * fs))) / fs
    x = np.zeros_like(t)
    ibi = 60.0 / bpm
    beat = -rng.uniform(0, ibi)
    while beat < seconds + ibi:
        for amp, mu, sd in ((1.0, 0.15 * ibi ** 0.5, 0.06), (0.45, 0.42 * ibi ** 0.5, 0.09)):
            x += amp * np.exp(-0.5 * ((t - beat - mu) / sd) ** 2)
        beat += ibi * (1 + jitter * rng.standard_normal())
    return x + noise * rng.standard_normal(len(t))


# ---------------------------------------------------------------- folds and scoring

def dalia_fold(subject: int) -> int:
    """Fixed fold of a DaLiA subject number 1..15: S1-S3 -> 0, ..., S13-S15 -> 4."""
    return (int(subject) - 1) // 3


def minute_medians(minutes, values, min_n=3):
    """{minute: median of finite values} for minutes with at least min_n finite values."""
    by = {}
    for m, v in zip(minutes, values):
        if v is not None and np.isfinite(v):
            by.setdefault(int(m), []).append(float(v))
    return {m: float(np.median(v)) for m, v in by.items() if len(v) >= min_n}


def window_metrics(est, ref):
    """Metrics over paired arrays (NaN estimates dropped, counted in coverage)."""
    est, ref = np.asarray(est, float), np.asarray(ref, float)
    ok = np.isfinite(est) & np.isfinite(ref)
    n_all = int(np.isfinite(ref).sum())
    if ok.sum() == 0:
        return {'n': 0, 'coverage': 0.0}
    e, r = est[ok], ref[ok]
    err = e - r
    sd = float(err.std(ddof=1)) if len(err) > 1 else 0.0
    rr = float(np.corrcoef(e, r)[0, 1]) if len(e) > 2 and e.std() > 0 and r.std() > 0 else float('nan')
    return {'n': int(ok.sum()), 'coverage': float(ok.sum() / max(n_all, 1)), 'mae': float(np.abs(err).mean()),
            'bias': float(err.mean()), 'loa': [float(err.mean() - 1.96 * sd), float(err.mean() + 1.96 * sd)],
            'within5': float(np.mean(np.abs(err) <= 5) * 100), 'within10': float(np.mean(np.abs(err) <= 10) * 100),
            'r': rr, 'est_sd': float(e.std()), 'ref_sd': float(r.std())}
