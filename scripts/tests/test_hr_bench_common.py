"""Offline tests for scripts/hr_bench_common.py (no database, model or network)."""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import hr_bench_common as hb  # noqa: E402


def sine(bpm, fs, seconds=10.0, phase=0.0):
    t = np.arange(int(seconds * fs)) / fs
    return np.sin(2 * np.pi * bpm / 60.0 * t + phase)


def test_split_segments_resets_only():
    idx = [0, 2, 1, 3, 4, 5, 0, 1, 2]           # small drop 2->1 is reordering, 5->0 is a reset
    assert hb.split_segments(idx, reset_drop=3) == [(0, 6), (6, 9)]


def test_fit_clock_recovers_rate():
    idx = np.arange(1000)
    t = 1000 + idx * 39.96 + np.repeat(np.arange(200), 5) % 3   # batched arrival jitter
    t0, dt = hb.fit_clock(idx, t)
    assert abs(dt - 39.96) < 0.01


def test_contiguous_windows_skip_gaps():
    idx = np.concatenate([np.arange(0, 400), np.arange(410, 1000)])
    starts = hb.contiguous_windows(idx, n=250, step=125)
    got = [int(idx[p]) for p in starts]
    assert 250 not in got and 375 not in got   # those would span the gap at 400-409
    assert 0 in got and 125 in got and 500 in got


def test_welch_peak_finds_rate_at_25hz():
    for bpm in (48, 72, 113, 160):
        x = hb.bandpass(sine(bpm, 25.0) + 0.1 * np.random.default_rng(0).standard_normal(250), 25.0)
        f, p = hb.spectrum(x, 25.0)
        assert abs(hb.band_peaks(f, p, k=1)[0][0] - bpm) < 2


def test_motion_suppression_keeps_pulse():
    pk, pw = np.array([120.0, 75.0, 150.0]), np.array([3.0, 2.0, 1.0])
    kept, _ = hb.suppress_near(pk, pw, motion_bpm=[121.0])
    assert kept[0] == 75.0
    kept, _ = hb.suppress_near(np.array([120.0]), np.array([1.0]), motion_bpm=[120.0])
    assert kept[0] == 120.0                    # never removes every peak


def test_acf_rate():
    x = hb.bandpass(sine(66, 25.0), 25.0)
    assert abs(hb.acf_hr(x, 25.0) - 66) < 2


def test_snr_and_sqi_high_for_clean_pulse():
    x = hb.bandpass(sine(70, 25.0), 25.0)
    f, p = hb.spectrum(x, 25.0)
    assert hb.sqi(f, p) > 0.8
    assert hb.snr_at(f, p, 70) > hb.snr_at(f, p, 120)


def test_track_greedy_follows_previous():
    c = [np.array([70.0]), np.array([140.0, 72.0]), np.array([75.0, 150.0]), None, np.array([150.0, 80.0])]
    out = hb.track_greedy(c, tol=15)
    assert list(out[:3]) == [70.0, 72.0, 75.0] and np.isnan(out[3]) and out[4] == 150.0


def test_track_viterbi_prefers_smooth_path():
    f = np.arange(30, 241, 1.0)
    rows = []
    for k, true in enumerate([70, 72, 74, 76, 78]):
        p = np.exp(-0.5 * ((f - true) / 2) ** 2)
        if k == 2:   # a stronger spurious peak in one window
            p = p + 1.5 * np.exp(-0.5 * ((f - 150) / 2) ** 2)
        rows.append(p)
    out = hb.track_viterbi(f, np.array(rows), sigma=5)
    assert abs(out[2] - 74) <= 2


def test_rise_fall_ratio_detects_inversion():
    x = hb.synthetic_ppg(70, 125.0, noise=0.0)
    xf = hb.bandpass(x, 125.0)
    a, b = hb.rise_fall_ratio(xf, 125.0), hb.rise_fall_ratio(-xf, 125.0)
    assert a < 0.5 < b


def test_dalia_fold_and_minute_medians():
    assert [hb.dalia_fold(s) for s in (1, 3, 4, 15)] == [0, 0, 1, 4]
    med = hb.minute_medians([1, 1, 1, 2, 2], [60, 62, np.nan, 70, 71], min_n=2)
    assert med == {1: 61.0, 2: 70.5}


def test_window_metrics():
    d = hb.window_metrics([60, 70, np.nan, 90], [62, 70, 80, 85])
    assert d['n'] == 3 and abs(d['coverage'] - 0.75) < 1e-9
    assert abs(d['mae'] - (2 + 0 + 5) / 3) < 1e-9 and abs(d['bias'] - 1.0) < 1e-9
