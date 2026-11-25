import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import train_head_wide as t


class Windows(unittest.TestCase):
    def test_ends_on_a_global_grid_with_full_windows(self):
        ends = t.window_ends(1_003, 31_000)
        self.assertEqual(ends[0], 15_000)          # first grid end with a full 10 s after the first sample
        self.assertTrue(all(e % 5000 == 0 for e in ends))
        self.assertEqual(ends[-1], 30_000)

    def test_squeeze_50hz_to_250_keeps_the_frequency(self):
        n = 500
        x = np.sin(2 * np.pi * 1.5 * np.arange(n) / 50.0)       # 1.5 Hz over 10 s -> 15 cycles
        y = t.to_n_samples(x)
        self.assertEqual(len(y), 250)
        self.assertEqual(int(np.argmax(np.abs(np.fft.rfft(y)[1:]))) + 1, 15)

    def test_cut_window_needs_enough_samples(self):
        tt = np.arange(0, 10_000, 40.0)             # 250 samples, 25 Hz
        v = np.arange(250.0)
        self.assertIsNone(t.cut_window(tt, v, 10_000, 300))
        w = t.cut_window(tt, v, 10_000, 200)
        self.assertEqual(len(w), 250)
        self.assertEqual(w[0], 1.0)             # (end-10 s, end]: the sample at exactly end-10 s is outside


class Labels(unittest.TestCase):
    ref = {10: 60.0, 11: 80.0, 12: 100.0, 14: 50.0}

    def test_minute_label_uses_the_minute_of_the_centre(self):
        self.assertEqual(t.label_minute(11 * 60_000 + 59_000, self.ref), 80.0)
        self.assertIsNone(t.label_minute(13 * 60_000 + 10, self.ref))

    def test_interp_between_minute_midpoints(self):
        # minute 10 sits at 10:30, minute 11 at 11:30
        self.assertAlmostEqual(t.label_interp(10 * 60_000 + 30_000, self.ref), 60.0)
        self.assertAlmostEqual(t.label_interp(11 * 60_000, self.ref), 70.0)
        self.assertAlmostEqual(t.label_interp(11 * 60_000 + 45_000, self.ref), 85.0)

    def test_interp_never_bridges_a_missing_minute_or_the_ends(self):
        self.assertIsNone(t.label_interp(13 * 60_000, self.ref))          # between minute 12 and 14
        self.assertIsNone(t.label_interp(10 * 60_000 + 10_000, self.ref))  # before the first anchor
        self.assertIsNone(t.label_interp(14 * 60_000 + 40_000, self.ref))  # after the last anchor


class BlockSplit(unittest.TestCase):
    def setUp(self):
        self.blocks = {'a': (0, 100_000), 'b': (100_000, 200_000), 'c': (200_000, 300_000)}

    def windows(self, centres):
        c = np.array(centres)
        return c - 5000, c + 5000

    def test_straddling_windows_are_in_neither_set(self):
        s, e = self.windows([100_000])                    # straddles a|b
        tr, te = t.split_blocks(s, e, ['b'], ['a', 'c'], self.blocks)
        self.assertFalse(tr.any() or te.any())

    def test_guard_band_removes_training_windows_next_to_the_test_block(self):
        s, e = self.windows([50_000, 75_000, 94_000 - 1000, 120_000, 250_000])
        tr, te = t.split_blocks(s, e, ['b'], ['a', 'c'], self.blocks, guard_ms=30_000)
        self.assertEqual(list(te), [False, False, False, True, False])
        # window ending at 99_000 is 1 s from block b; the one ending at 80_000 is 20 s away: both dropped
        self.assertEqual(list(tr), [True, False, False, False, True])

    def test_train_and_test_never_overlap_in_time(self):
        centres = np.arange(5000, 295_000, 5000)
        s, e = self.windows(centres)
        for held in ('a', 'b', 'c'):
            others = [b for b in self.blocks if b != held]
            tr, te = t.split_blocks(s, e, [held], others, self.blocks)
            self.assertFalse((tr & te).any())
            lo, hi = self.blocks[held]
            self.assertTrue(all(ee <= lo - 30_000 or ss >= hi + 30_000 for ss, ee in zip(s[tr], e[tr])))

    def test_train_on_one_block_only(self):
        centres = np.arange(5000, 295_000, 5000)
        s, e = self.windows(centres)
        tr, te = t.split_blocks(s, e, ['a', 'c'], ['b'], self.blocks)
        self.assertTrue(tr.any() and te.any())
        self.assertTrue(all(100_000 + 30_000 <= ss and ee <= 200_000 - 30_000 for ss, ee in zip(s[tr], e[tr])))


class Ridge(unittest.TestCase):
    def test_fold_in_reproduces_standardised_prediction(self):
        rng = np.random.default_rng(0)
        X = rng.normal(3, 2, (60, 8)); y = X @ rng.normal(size=8) + 5
        w = rng.uniform(0.2, 2, 60)
        fits = t.ridge_grid(X, y, w, [1.0])
        wt, b = fits[1.0]
        mean, std = t.weighted_stats(X, w)
        ym = float((w * y).sum() / w.sum())
        Z = (X - mean) / std
        Zw = Z * w[:, None]
        wz = np.linalg.solve(Zw.T @ Z + np.eye(8), Zw.T @ (y - ym))
        self.assertTrue(np.allclose(X @ wt + b, Z @ wz + ym))

    def test_unit_weights_and_small_alpha_recover_a_linear_rule(self):
        rng = np.random.default_rng(1)
        X = rng.normal(size=(200, 5)); y = X @ np.array([1., -2, 0.5, 0, 3]) + 70
        wt, b = t.ridge_grid(X, y, np.ones(200), [1e-6])[1e-6]
        self.assertTrue(np.allclose(X @ wt + b, y, atol=1e-3))

    def test_source_weights_give_each_added_source_the_own_total(self):
        kinds = np.array(['own'] * 10 + ['public'] * 1000 + ['stretch'] * 50)
        w = t.source_weights(kinds, 10)
        self.assertAlmostEqual(w[kinds == 'own'].sum(), 10)
        self.assertAlmostEqual(w[kinds == 'public'].sum(), 10)
        self.assertAlmostEqual(w[kinds == 'stretch'].sum(), 10)


class ScoreAndGate(unittest.TestCase):
    def test_minute_scoring_needs_three_windows_and_a_reference(self):
        centres = [60_000 * 5 + k * 5000 for k in range(5)] + [60_000 * 6 + 1000, 60_000 * 6 + 6000]
        pred = [70, 72, 74, 76, 78, 90, 90]
        s, pairs = t.score_minutes(centres, pred, {5: 80.0, 6: 100.0})
        self.assertEqual(len(pairs), 1)                       # minute 6 has only two windows
        self.assertEqual(pairs[0][1], 74.0)
        self.assertAlmostEqual(s['mae'], 6.0)

    def test_oracle_constant_is_the_median(self):
        s = t.summarise([(1, 70, 75), (2, 100, 113), (3, 105, 110), (4, 108, 112), (5, 99, 100)])
        self.assertAlmostEqual(s['oracle_const_mae'], 10.0)    # median 110

    def test_gate_needs_both_conditions(self):
        base = {'mae': 8.0, 'oracle_const_mae': 10.0, 'r': 0.7}
        self.assertTrue(t.gate(base))
        self.assertFalse(t.gate({**base, 'mae': 10.0}))
        self.assertFalse(t.gate({**base, 'r': 0.5}))
        self.assertFalse(t.gate({**base, 'r': float('nan')}))
        self.assertFalse(t.gate(None))


if __name__ == '__main__':
    unittest.main()
