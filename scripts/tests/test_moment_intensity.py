import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import moment_windows as mw
import moment_features as mf
import moment_intensity_eval as ev


class Labels(unittest.TestCase):
    def test_free_text_labels_are_normalised(self):
        for raw, want in (('Skipping roppe', 'skipping'), ('Plahing piano', 'piano'), ('Hanging washing', 'washing'),
                          ('Tennis practise', 'tennis'), ('Lopse ring', 'loose ring'), ('Loose ring', 'loose ring'),
                          ('Exercise pushups', 'pushups'), ('jogging on the spot', 'jogging'),
                          ('Lying down', 'lying down'), ('Typing on computer', 'typing'), ('Sitting', 'sitting')):
            self.assertEqual(mw.normalise_label(raw), want)

    def test_mapping_and_exclusions(self):
        self.assertEqual(mw.intensity_of('piano'), 'still')
        self.assertEqual(mw.intensity_of('stairs'), 'light')
        self.assertEqual(mw.intensity_of('star jumps'), 'vigorous')
        self.assertIsNone(mw.intensity_of('loose ring'))
        self.assertIsNone(mw.intensity_of('typing'))

    def test_bouts_merge_same_class_but_a_fault_breaks_them(self):
        iv = [(0, 10_000, 'sitting'), (10_500, 20_000, 'standing'), (20_000, 30_000, 'walking'),
              (30_000, 40_000, 'loose ring'), (40_000, 50_000, 'sitting')]
        bouts = mw.make_bouts(iv)
        self.assertEqual([(a, b, c) for a, b, c, _ in bouts],
                         [(0, 20_000, 'still'), (20_000, 30_000, 'light'), (40_000, 50_000, 'still')])

    def test_window_across_still_and_light_is_dropped_but_still_to_still_is_kept(self):
        iv = mw.intensity_intervals([(0, 100_000, 'sitting'), (100_000, 200_000, 'walking')])
        self.assertIsNone(mw.label_window(iv, 95_000, 105_000))
        self.assertEqual(mw.label_window(iv, 10_000, 20_000), 'still')
        iv2 = mw.intensity_intervals([(0, 50_000, 'sitting'), (50_000, 100_000, 'standing')])
        self.assertEqual(mw.label_window(iv2, 45_000, 55_000), 'still')


class Features(unittest.TestCase):
    def test_sample_entropy_is_higher_for_noise_than_for_a_sine(self):
        rng = np.random.RandomState(0)
        t = np.arange(250) / 25.0
        self.assertLess(mf.sample_entropy(np.sin(2 * np.pi * 1.5 * t)), mf.sample_entropy(rng.randn(250)))
        self.assertEqual(mf.sample_entropy(np.ones(250)), 0.0)

    def test_extra_features_shape_and_dominant_frequency(self):
        t = np.arange(250) / 25.0
        raw = np.stack([np.sin(2 * np.pi * 2.0 * t), np.zeros(250), np.ones(250) * 9.8], axis=1)
        f = mf.extra_features(raw, 25.0)
        self.assertEqual(len(f), len(mf.EXTRA_NAMES))
        self.assertTrue(np.all(np.isfinite(f)))


class Folds(unittest.TestCase):
    def test_held_out_bout_and_its_10s_neighbourhood_never_train(self):
        ends = np.arange(10_000, 200_000, 5_000)
        starts = ends - 10_000
        groups = np.concatenate([np.full(len(ends), 65), np.full(5, 1)])
        ends = np.concatenate([ends, np.arange(5) * 5_000 + 10_000])
        starts = ends - 10_000
        bouts = np.array(['65:0' if e <= 80_000 else '65:1' for e in ends[:len(ends) - 5]] + ['1:0'] * 5)
        folds = list(ev.lobo_folds(groups, bouts, starts, ends, 65))
        self.assertEqual(len(folds), 2)
        te, tr = folds[0]
        self.assertFalse(np.any(te & tr))
        lo, hi = starts[te].min() - 10_000, ends[te].max() + 10_000
        self.assertFalse(np.any(tr & (groups == 65) & (ends > lo) & (starts < hi)))
        self.assertTrue(np.all(tr[groups == 1]))            # other recordings always train


if __name__ == '__main__':
    unittest.main()
