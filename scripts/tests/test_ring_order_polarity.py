"""Offline tests for the 25 Nov 2026 fixes in scripts/train_head_from_rules.py: sample order and polarity."""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import hr_bench_common as hb          # noqa: E402
import train_head_from_rules as r     # noqa: E402


def test_sample_order_sorts_within_batches():
    # insertion order as stored for session 55: indices shuffled inside batches sharing one arrival time
    rows = [(0, 1, 1.0), (0, 0, 0.0), (0, 3, 3.0), (0, 2, 2.0), (0, 4, 4.0), (240, 5, 5.0), (240, 7, 7.0), (240, 6, 6.0)]
    out = r.sample_order(rows)
    assert [q[1] for q in out] == list(range(8))
    assert [q[2] for q in out] == [float(i) for i in range(8)]
    assert {q[3] for q in out} == {0}


def test_sample_order_splits_at_reset_and_drops_repeats():
    rows = [(0, 500 + k, float(k)) for k in range(5)] + [(10, 502, 2.0)] + [(20, k, 10.0 + k) for k in range(3)]
    out = r.sample_order(rows)
    assert [q[1] for q in out] == [500, 501, 502, 503, 504, 0, 1, 2]     # repeat of 502 dropped, reset kept apart
    assert [q[3] for q in out] == [0] * 5 + [1] * 3


def test_window_ending_needs_consecutive_samples_of_one_segment():
    series = [(1000 + 40 * i, i, float(i), 0) for i in range(300)]
    w = r.window_ending(series, 1000 + 40 * 299, 250, 12_000)
    assert w == [float(i) for i in range(50, 300)]
    gappy = [q for q in series if q[1] != 200]
    assert r.window_ending(gappy, 1000 + 40 * 299, 250, 12_000) is None
    two = series[:150] + [(1000 + 40 * i, i - 150, float(i), 1) for i in range(150, 300)]
    assert r.window_ending(two, 1000 + 40 * 299, 250, 12_000) is None


def test_old_insertion_order_would_have_shuffled_a_window():
    rng = np.random.default_rng(0)
    idx = np.arange(250)
    shuffled = np.concatenate([rng.permutation(idx[k:k + 5]) for k in range(0, 250, 5)])
    rows = [(0, int(i), float(i)) for i in shuffled]
    assert [q[2] for q in r.sample_order(rows)] == [float(i) for i in idx]


def test_to_model_input_inverts_ring_pulse():
    upright = hb.synthetic_ppg(70, 25.0, noise=0.0)       # PaPaGei-style pleth: rises fast, falls slowly
    ring_raw = 9_000_000 - 1000 * upright                  # the ring's detected light falls when volume rises
    x = np.asarray(r.to_model_input(ring_raw))
    assert hb.rise_fall_ratio(hb.bandpass(ring_raw, 25.0), 25.0) > 0.5
    assert hb.rise_fall_ratio(hb.bandpass(x, 25.0), 25.0) < 0.5
    assert np.allclose(r.to_model_input(ring_raw, invert=False), ring_raw)
