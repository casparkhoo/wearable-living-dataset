import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import hr_common as h  # noqa: E402

M = 60_000


class MinuteTable(unittest.TestCase):
    def test_counts_and_rules_only_from_good(self):
        rows = [{'t': 5_000, 'quality': 'good', 'hr': 60, 'shown': 61, 'movement': 'still'},
                {'t': 10_000, 'quality': 'poor', 'hr': 90, 'shown': 62, 'movement': 'moving'},
                {'t': 15_000, 'quality': None, 'hr': None, 'shown': None, 'movement': None},
                {'t': 20_000, 'quality': 'fair', 'hr': 64, 'shown': None, 'movement': 'still'},
                {'t': M + 1, 'quality': 'good', 'hr': 70, 'shown': 70, 'movement': 'active'}]
        t = h.minute_table(rows)
        self.assertEqual(t[0]['n'], 4)
        self.assertEqual(t[0]['n_good'], 2)
        self.assertEqual(t[0]['n_still'], 2)
        self.assertEqual(t[0]['rules'], [60.0, 64.0])      # the poor 90 is not a rules estimate
        self.assertEqual(t[0]['shown'], [61.0, 62.0])
        self.assertEqual(t[1]['n'], 1)

    def test_gate(self):
        d = {'n': 12, 'n_good': 6, 'n_still': 9}
        self.assertTrue(h.passes_gate(d, 6))
        self.assertFalse(h.passes_gate(d, 7))
        self.assertTrue(h.passes_gate(d, 6, 0.75))
        self.assertFalse(h.passes_gate({'n': 12, 'n_good': 6, 'n_still': 8}, 6, 0.75))
        self.assertFalse(h.passes_gate(None, 0))
        self.assertTrue(h.passes_gate({'n': 4, 'n_good': 0, 'n_still': 3}, 0, 0.75))


class Estimates(unittest.TestCase):
    def test_per_minute_median_needs_three(self):
        out = h.per_minute_median([1, 2, 3, M + 1, M + 2], [10, 30, 20, 50, 60])
        self.assertEqual(out, {0: 20.0})

    def test_fuse(self):
        self.assertEqual(h.fuse(60.0, 70.0, True), (60.0, 'rules'))
        self.assertEqual(h.fuse(60.0, 70.0, False), (70.0, 'head'))
        self.assertEqual(h.fuse(None, 70.0, True), (70.0, 'head'))
        self.assertEqual(h.fuse(None, None, True), (None, None))


class Labels(unittest.TestCase):
    def test_interp(self):
        ref = {0: 60.0, 1: 70.0, 3: 90.0}
        self.assertEqual(h.label_interp(30_000, ref), 60.0)
        self.assertAlmostEqual(h.label_interp(60_000, ref), 65.0)
        self.assertIsNone(h.label_interp(120_000, ref))     # gap over the missing minute 2
        self.assertIsNone(h.label_interp(10_000, ref))      # before the first anchor


class Metrics(unittest.TestCase):
    def test_values(self):
        pairs = [(0, 60, 60), (1, 66, 60), (2, 50, 62)]
        s = h.metrics(pairs, const=61)
        self.assertAlmostEqual(s['mae'], (0 + 6 + 12) / 3)
        self.assertAlmostEqual(s['bias'], (0 + 6 - 12) / 3)
        self.assertAlmostEqual(s['within5'], 100 / 3)
        self.assertAlmostEqual(s['within10'], 200 / 3)
        self.assertAlmostEqual(s['oracle_const_mae'], (0 + 0 + 2) / 3)
        self.assertAlmostEqual(s['train_const_mae'], (1 + 1 + 1) / 3)
        self.assertIsNone(h.metrics([]))

    def test_gate(self):
        self.assertTrue(h.beats_gate({'mae': 5, 'oracle_const_mae': 6, 'r': 0.6}))
        self.assertFalse(h.beats_gate({'mae': 5, 'oracle_const_mae': 6, 'r': 0.5}))
        self.assertFalse(h.beats_gate({'mae': 7, 'oracle_const_mae': 6, 'r': 0.9}))
        self.assertFalse(h.beats_gate({'mae': 1, 'oracle_const_mae': 6, 'r': float('nan')}))


class Recalibration(unittest.TestCase):
    def test_linear_recovers(self):
        est = np.array([50., 60., 70.])
        sl, off = h.fit_linear(est, 2 * est - 40)
        self.assertAlmostEqual(sl, 2.0)
        self.assertAlmostEqual(off, -40.0)

    def test_constant_estimate(self):
        self.assertEqual(h.fit_linear([60, 60, 60], [50, 70, 90]), (0.0, 70.0))

    def test_offset(self):
        self.assertAlmostEqual(h.fit_offset([60, 62], [70, 74]), 11.0)


class Reference(unittest.TestCase):
    def test_jumps_adjacent_only(self):
        j = h.consecutive_jumps({0: 60, 1: 75, 2: 113, 4: 59})
        self.assertEqual(j, [(1, 60, 75, 15), (2, 75, 113, 38)])

    def test_missing(self):
        self.assertEqual(h.missing_minutes({1: 60}, {1, 2, 3}), [2, 3])


class Ridge(unittest.TestCase):
    def test_source_weights(self):
        w = h.source_weights(['own', 'own', 'stretch', 'stretch', 'stretch', 'stretch'])
        self.assertEqual(w[:2].tolist(), [1, 1])
        self.assertAlmostEqual(w[2:].sum(), 2.0)

    def test_small_alpha_recovers_linear(self):
        rng = np.random.default_rng(0)
        X = rng.normal(size=(200, 3))
        y = X @ np.array([1.0, -2.0, 0.5]) + 60
        w, b = h.ridge_fit(X, y, np.ones(200), [1e-6])[1e-6]
        np.testing.assert_allclose(X @ w + b, y, atol=1e-4)

    def test_large_alpha_gives_mean(self):
        rng = np.random.default_rng(1)
        X = rng.normal(size=(50, 4)); y = rng.normal(70, 5, size=50)
        w, b = h.ridge_fit(X, y, np.ones(50), [1e12])[1e12]
        np.testing.assert_allclose(X @ w + b, y.mean(), atol=1e-6)


if __name__ == '__main__':
    unittest.main()
