"""Handcrafted motion features for 10 s accelerometer windows (the baseline MOMENT must beat).

Pure numpy, no weights. `extra_features(raw, hz)` takes the (n, 3) samples of one window and returns
the values named in `EXTRA_NAMES`:

  sampen        sample entropy of the magnitude (m=2, r=0.2 std), 0 when the signal is flat
  dyn_std       std of the dynamic magnitude |a - mean(a)| (gravity direction removed)
  mad_mag       mean absolute deviation of the magnitude
  band_lo/mid/hi  log10 energy of the magnitude's spectrum in 0.3-1.5, 1.5-4 and 4-12 Hz
                (12 Hz is cut to the Nyquist frequency of the 25 Hz ring)
  dom_freq      frequency of the strongest spectral peak in 0.3-Nyquist Hz

`window_stats` in ml-inference-server/moment_encoder.py supplies the other baseline inputs
(std of magnitude, std of its first difference, per-axis std, gravity direction).
"""
from __future__ import annotations

import numpy as np

EXTRA_NAMES = ('sampen', 'dyn_std', 'mad_mag', 'band_lo', 'band_mid', 'band_hi', 'dom_freq')


def sample_entropy(x, m: int = 2, r_frac: float = 0.2) -> float:
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    sd = x.std()
    if n < m + 3 or sd < 1e-9:
        return 0.0
    r = r_frac * sd

    def count(mm):
        t = np.stack([x[i:n - m + i] for i in range(mm)], axis=1)       # same number of templates for m and m+1
        d = np.abs(t[:, None, :] - t[None, :, :]).max(axis=2)
        return (np.sum(d <= r) - len(t)) / 2.0                           # exclude self-matches

    b, a = count(m), count(m + 1)
    if a <= 0 or b <= 0:
        return float(np.log(max(n - m, 2)) + np.log(max(n - m - 1, 1)) - np.log(2))   # conventional upper bound
    return float(-np.log(a / b))


def extra_features(raw, hz: float) -> np.ndarray:
    arr = np.asarray(raw, dtype=np.float64)
    mag = np.linalg.norm(arr, axis=1)
    dyn = np.linalg.norm(arr - arr.mean(axis=0), axis=1)
    sig = mag - mag.mean()
    spec = np.abs(np.fft.rfft(sig * np.hanning(len(sig)))) ** 2
    freq = np.fft.rfftfreq(len(sig), d=1.0 / hz)
    nyq = hz / 2.0

    def band(lo, hi):
        sel = (freq >= lo) & (freq < min(hi, nyq + 1e-9))
        return float(np.log10(spec[sel].sum() + 1e-9)) if sel.any() else -9.0

    sel = freq >= 0.3
    dom = float(freq[sel][np.argmax(spec[sel])]) if sel.any() and spec[sel].max() > 1e-9 else 0.0
    return np.array([sample_entropy(mag), dyn.std(), np.mean(np.abs(mag - mag.mean())),
                     band(0.3, 1.5), band(1.5, 4.0), band(4.0, 12.0), dom], dtype=np.float32)
