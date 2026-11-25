import io
import os
import pickle
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import train_head_public as t


class Windowing(unittest.TestCase):
    def test_starts_and_count(self):
        # 30 s at 25 Hz: windows start at 0, 5, 10, 15, 20 s
        self.assertEqual(list(t.window_starts(750, 25.0)), [0, 125, 250, 375, 500])
        self.assertEqual(len(t.window_starts(249, 25.0)), 0)

    def test_cut_windows_shape_and_content(self):
        x = np.arange(750.0)
        s, w = t.cut_windows(x, 25.0)
        self.assertEqual(w.shape, (5, 250))
        self.assertEqual(list(s), [0, 5, 10, 15, 20])
        self.assertEqual(w[1, 0], 125.0)
        self.assertEqual(w[1, -1], 374.0)

    def test_short_signal(self):
        s, w = t.cut_windows(np.zeros(100), 25.0)
        self.assertEqual(len(s), 0)
        self.assertEqual(w.shape[0], 0)


class Resampling(unittest.TestCase):
    def test_length_and_frequency_kept(self):
        fs = 64.0
        n = int(fs * 20)
        x = np.sin(2 * np.pi * 1.5 * np.arange(n) / fs)
        y = t.resample_signal(x, fs)
        self.assertEqual(len(y), 500)
        spec = np.abs(np.fft.rfft(y))
        f = np.fft.rfftfreq(len(y), 1 / 25.0)
        self.assertAlmostEqual(f[spec.argmax()], 1.5, places=1)

    def test_125_to_25(self):
        self.assertEqual(len(t.resample_signal(np.random.randn(1250), 125.0)), 250)

    def test_antialiasing(self):
        # 20 Hz at 64 Hz is above the 12.5 Hz Nyquist of 25 Hz and must be removed, not folded
        fs = 64.0
        x = np.sin(2 * np.pi * 20 * np.arange(int(fs * 20)) / fs)
        y = t.resample_signal(x, fs)
        self.assertLess(y[50:-50].std(), 0.1)

    def test_same_rate_is_identity(self):
        x = np.random.randn(100)
        self.assertTrue(np.allclose(t.resample_signal(x, 25.0), x))


class Labels(unittest.TestCase):
    def test_mean_reference_uses_centres_in_window(self):
        centres = np.arange(10) * 2.0 + 4.0   # 4, 6, ..., 22
        hr = np.arange(10) * 10.0 + 60        # 60, 70, ...
        # window [0, 10): centres 4, 6, 8 -> 60, 70, 80
        self.assertAlmostEqual(t.mean_reference(centres, hr, 0, 10), 70.0)
        self.assertTrue(np.isnan(t.mean_reference(centres, hr, 100, 110)))

    def test_nan_labels_ignored(self):
        self.assertAlmostEqual(t.mean_reference([1, 2, 3], [60, np.nan, 80], 0, 10), 70.0)

    def test_modal_value(self):
        self.assertEqual(t.modal_value([0, 1, 2, 3], [1, 2, 2, 5], 0, 4), 2)
        self.assertIsNone(t.modal_value([0, 1], [1, 1], 5, 9))

    def test_usable(self):
        self.assertFalse(t.usable(np.ones(250)))
        self.assertFalse(t.usable(np.r_[np.ones(249), np.nan]))
        self.assertTrue(t.usable(np.random.randn(250)))


class RidgeFoldIn(unittest.TestCase):
    def test_raw_weights_equal_standardised_fit(self):
        rng = np.random.default_rng(0)
        X = rng.normal(5, [1, 20, 0.1, 3], (200, 4))
        y = X @ [1, 0.1, 30, -2] + 7 + rng.normal(0, 0.1, 200)
        w, b = t.ridge(X, y, 1.0)
        mean, std = X.mean(0), X.std(0) + 1e-9
        Z = (X - mean) / std
        wz = np.linalg.solve(Z.T @ Z + np.eye(4), Z.T @ (y - y.mean()))
        self.assertTrue(np.allclose(X @ w + b, Z @ wz + y.mean()))
        self.assertLess(np.abs(X @ w + b - y).mean(), 0.2)

    def test_mae_r(self):
        mae, r = t.mae_r([1, 2, 3], [2, 3, 4])
        self.assertAlmostEqual(mae, 1.0)
        self.assertAlmostEqual(r, 1.0)

    def test_loso_never_trains_on_held_group(self):
        X = np.array([[0.0], [1.0], [2.0], [3.0]]); y = np.array([0.0, 1.0, 2.0, 3.0])
        g = np.array(['a', 'a', 'b', 'b'])
        p = t.loso(X, y, g, (1e-6,))[1e-6]
        self.assertTrue(np.isfinite(p).all())
        pc = t.constant_loso(y, g, np.ones(4, bool))
        self.assertEqual(list(pc), [2.5, 2.5, 0.5, 0.5])


class SafeUnpickle(unittest.TestCase):
    def test_numpy_array_roundtrip(self):
        buf = io.BytesIO(); pickle.dump({'a': np.arange(5.0), 'b': [1, 'x']}, buf, protocol=2)
        buf.seek(0)
        d = t.NumpyOnlyUnpickler(buf, encoding='latin1').load()
        self.assertTrue(np.array_equal(d['a'], np.arange(5.0)))

    def test_blocks_code_execution(self):
        class Evil:
            def __reduce__(self):
                return (os.system, ('echo hi',))
        buf = io.BytesIO(pickle.dumps(Evil())); buf.seek(0)
        with self.assertRaises(pickle.UnpicklingError):
            t.NumpyOnlyUnpickler(buf).load()


if __name__ == '__main__':
    unittest.main()
