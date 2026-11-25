"""Offline tests for the pure functions of the analysis scripts.

No GPU, database or network. Run from the repo root:
    python -m unittest discover -s scripts/tests -v     (or: python -m pytest scripts/tests)
"""
import math
import os
import sys
import tempfile
import unittest
import zipfile

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))

import align_reference as ar  # noqa: E402
import score_methods as sm  # noqa: E402
import train_head_from_rules as th  # noqa: E402


def ramp_series(n, start_ms=0, step_ms=40):
    """(arrival_ms, index, green) rows."""
    return [(start_ms + i * step_ms, i, float(i)) for i in range(n)]


def dominant_hz(x, fs=25.0):
    x = np.asarray(x) - np.mean(x)
    freqs = np.fft.rfftfreq(8192, 1 / fs)
    return freqs[np.argmax(np.abs(np.fft.rfft(x, 8192)))]


class TempFiles(unittest.TestCase):
    def tmp(self, name, text):
        d = tempfile.mkdtemp()
        path = os.path.join(d, name)
        with open(path, 'w', newline='') as f:
            f.write(text)
        return path


class StretchTests(unittest.TestCase):
    def test_too_few_samples_gives_nothing(self):
        self.assertEqual(th.stretched(ramp_series(374), 14_960), [])
        self.assertEqual(th.stretched([], 10_000), [])

    def test_every_stretch_is_250_samples(self):
        out = th.stretched(ramp_series(400), 15_960)
        self.assertEqual([k for k, _ in out], list(th.STRETCHES))
        for _, x in out:
            self.assertEqual(len(x), 250)

    def test_unit_stretch_is_the_newest_250_samples_unchanged(self):
        series = ramp_series(400)
        out = dict(th.stretched(series, 15_960))
        self.assertEqual(out[1.0], [g for _, _, g in series][-250:])

    def test_stretch_keeps_the_ends_of_the_segment(self):
        series = ramp_series(400)
        values = [g for _, _, g in series]
        out = dict(th.stretched(series, 15_960))
        for k in th.STRETCHES:
            n = int(round(250 * k))
            self.assertAlmostEqual(out[k][0], values[-n])
            self.assertAlmostEqual(out[k][-1], values[-1])

    def test_samples_outside_the_fifteen_second_window_are_ignored(self):
        self.assertEqual(th.stretched(ramp_series(400), 8_000), [])

    def test_stretch_k_multiplies_the_pulse_rate_by_k(self):
        fs = 25.0
        t = np.arange(375) / fs
        series = [((i + 1) * 40, i, float(v)) for i, v in enumerate(np.sin(2 * np.pi * 1.2 * t))]
        out = dict(th.stretched(series, 375 * 40))
        self.assertAlmostEqual(dominant_hz(out[1.0]), 1.2, delta=0.1)
        for k in (0.67, 1.5):
            self.assertAlmostEqual(dominant_hz(out[k]), 1.2 * k, delta=0.2)

    def test_stretches_are_symmetric_on_a_log_scale(self):
        logs = sorted(math.log(k) for k in th.STRETCHES)
        self.assertAlmostEqual(logs[0], -logs[-1], delta=0.1)
        self.assertIn(1.0, th.STRETCHES)


class CutTests(unittest.TestCase):
    def test_window_is_ten_seconds_before_end_inclusive_of_end(self):
        x = th.cut(ramp_series(400), 10_000)
        self.assertEqual(len(x), 250)
        self.assertEqual((x[0], x[-1]), (1.0, 250.0))

    def test_too_few_samples_is_none(self):
        self.assertIsNone(th.cut(ramp_series(150), 10_000))
        self.assertIsNone(th.cut([], 10_000))


class RidgeTests(unittest.TestCase):
    def test_recovers_a_linear_map_in_raw_feature_space(self):
        rng = np.random.default_rng(0)
        X = rng.normal(5.0, [1.0, 10.0, 0.1], size=(500, 3))
        true_w = np.array([2.0, -0.5, 30.0])
        w, b = th.ridge(X, X @ true_w + 7.0, alpha=1e-6)
        np.testing.assert_allclose(w, true_w, rtol=1e-3)
        self.assertAlmostEqual(float(b), 7.0, places=2)

    def test_stronger_penalty_shrinks_weights(self):
        rng = np.random.default_rng(1)
        X = rng.normal(size=(100, 4))
        y = X @ np.array([1.0, 2.0, 3.0, 4.0]) + rng.normal(0, 0.1, 100)
        self.assertLess(np.linalg.norm(th.ridge(X, y, 1e4)[0]), np.linalg.norm(th.ridge(X, y, 0.1)[0]))

    def test_constant_feature_gets_no_weight_and_no_nan(self):
        rng = np.random.default_rng(2)
        X = np.column_stack([rng.normal(size=50), np.full(50, 3.0)])
        w, b = th.ridge(X, 2 * X[:, 0] + 1, 1e-3)
        self.assertTrue(np.all(np.isfinite(w)) and np.isfinite(b))
        self.assertAlmostEqual(float(w[1]), 0.0, places=6)

    def test_offset_step_makes_real_windows_unbiased(self):
        # The offset rule in main(): shift the bias so unstretched windows are right on average.
        rng = np.random.default_rng(3)
        X = rng.normal(size=(60, 3))
        y = X @ np.ones(3) + 70 + rng.normal(0, 2, 60)
        w, b = th.ridge(X, y, 100.0)
        real = np.arange(60) % 3 == 0
        b += float(np.mean(y[real] - (X[real] @ w + b)))
        self.assertAlmostEqual(float(np.mean(y[real] - (X[real] @ w + b))), 0.0, places=9)

    def test_stretched_label_is_hr_times_factor(self):
        # main() trains on hr * k, because a pulse stretched by k beats k times faster.
        self.assertEqual([round(60.0 * k, 1) for k in (0.67, 1.0, 1.5)], [40.2, 60.0, 90.0])


class ReferenceAndScoreTests(TempFiles):
    def test_reference_minutes_filters_blank_and_out_of_range(self):
        path = self.tmp('r.csv', 'timestamp_unix,pulse_rate_bpm\n60000,70\n120000,\n180000,80\n9999999,90\n')
        self.assertEqual(th.reference_minutes(path, 0, 200_000), {1: 70.0, 3: 80.0})

    def test_score_needs_three_estimates_a_minute(self):
        windows = [(1000, 72.0), (2000, 74.0)] + [(60_000 + i, 70.0) for i in (0, 1, 2)]
        s = th.score(windows, {0: 70.0, 1: 70.0})
        self.assertEqual((s['minutes'], s['mae']), (1, 0.0))

    def test_score_none_without_overlap(self):
        self.assertIsNone(th.score([(0, 70.0)] * 5, {5: 70.0}))
        self.assertIsNone(th.score([], {}))

    def test_score_uses_the_median_of_the_minute(self):
        s = th.score([(0, 60.0), (1, 62.0), (2, 200.0)], {0: 60.0})
        self.assertEqual((s['mae'], s['bias']), (2.0, 2.0))

    def test_score_correlation_is_nan_for_a_constant_estimate(self):
        windows = []
        for m in range(3):
            windows += [(m * 60_000 + i, 70.0) for i in range(3)]
        s = th.score(windows, {0: 60.0, 1: 70.0, 2: 80.0})
        self.assertTrue(math.isnan(s['correlation']))
        self.assertAlmostEqual(s['best_constant_mae'], 20 / 3)


class AgreementTests(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(sm.agreement([]), {'minutes': 0})

    def test_single_pair_has_no_spread(self):
        a = sm.agreement([(72.0, 70.0)])
        self.assertEqual((a['minutes'], a['bias'], a['sd']), (1, 2.0, 0.0))
        self.assertNotIn('pearson_r', a)

    def test_bias_mae_rmse_and_limits(self):
        a = sm.agreement([(72.0, 70.0), (68.0, 70.0), (75.0, 70.0), (70.0, 70.0)])
        self.assertEqual((a['bias'], a['mae']), (1.25, 2.25))
        self.assertAlmostEqual(a['rmse'], math.sqrt((4 + 4 + 25 + 0) / 4), places=2)
        self.assertAlmostEqual(a['loa_high'] - a['loa_low'], 2 * 1.96 * a['sd'], delta=0.11)
        self.assertEqual(a['within_5_bpm'], 1.0)

    def test_constant_reference_has_no_correlation_and_best_constant_wins(self):
        a = sm.agreement([(70.0, 70.0), (71.0, 70.0), (72.0, 70.0)])
        self.assertNotIn('pearson_r', a)
        self.assertEqual(a['best_constant_mae'], 0.0)
        self.assertFalse(a['beats_best_constant'])

    def test_perfect_tracking_of_a_varying_reference_beats_constant(self):
        a = sm.agreement([(60.0, 60.0), (80.0, 80.0), (100.0, 100.0)])
        self.assertTrue(a['beats_best_constant'])
        self.assertEqual(a['pearson_r'], 1.0)

    def test_within_5_bpm_boundary_is_inclusive(self):
        self.assertEqual(sm.agreement([(75.0, 70.0), (75.1, 70.0)])['within_5_bpm'], 0.5)


def row(start, end, hr, method='m', version='1', session='1', run=''):
    return {'method_id': method, 'method_version': version, 'session_id': session,
            'window_start_ms': str(start), 'window_end_ms': str(end), 'hr_bpm': hr, 'run_id': run}


class PerMinuteTests(unittest.TestCase):
    def test_median_per_minute_and_min_estimates(self):
        rows = [row(0, 10_000, '70'), row(5_000, 15_000, '72'), row(10_000, 20_000, '74'), row(70_000, 80_000, '60')]
        out, span = sm.per_minute(rows, min_estimates=3)
        self.assertEqual(out['m 1'], {0: (72.0, 3, 3)})
        self.assertIn('1', span['m 1'])

    def test_blank_hr_counts_as_a_window_but_not_an_estimate(self):
        out, _ = sm.per_minute([row(0, 10_000, ''), row(5_000, 15_000, '70'), row(10_000, 20_000, None)], 1)
        self.assertEqual(out['m 1'][0], (70.0, 1, 3))

    def test_a_minute_with_only_blanks_is_dropped(self):
        out, _ = sm.per_minute([row(0, 10_000, '')], min_estimates=1)
        self.assertEqual(out['m 1'], {})

    def test_by_run_keeps_runs_apart(self):
        out, _ = sm.per_minute([row(0, 10_000, '70'), row(0, 10_000, '71', run='5')], 1, by_run=True)
        self.assertEqual(set(out), {'m 1 [live]', 'm 1 [run 5]'})

    def test_latest_runs_keeps_newest_recompute_per_session_and_method(self):
        rows = [row(0, 1, '1', run=''), row(0, 1, '2', run='3'), row(0, 1, '3', run='7'),
                row(0, 1, '4', method='other', run=''), row(0, 1, '5', session='2', run='')]
        self.assertEqual(sorted(r['hr_bpm'] for r in sm.latest_runs(rows)), ['3', '4', '5'])

    def test_method_key_for_live_rows(self):
        self.assertEqual(sm.method_key({'method_id': 'a', 'method_version': '2'}, True), 'a 2 [live]')
        self.assertEqual(sm.method_key({'method_id': 'a', 'method_version': '2'}, False), 'a 2')


class ReadExportTests(TempFiles):
    def test_plain_csv(self):
        rows, notes = sm.read_export(self.tmp('x.csv', 'a,b\n1,2\n'))
        self.assertEqual((rows, notes), ([{'a': '1', 'b': '2'}], []))

    def test_zip_without_derived_windows_exits(self):
        zpath = os.path.join(tempfile.mkdtemp(), 'x.zip')
        with zipfile.ZipFile(zpath, 'w') as z:
            z.writestr('other.csv', 'a\n1\n')
        with self.assertRaises(SystemExit):
            sm.read_export(zpath)

    def test_zip_with_derived_windows_and_annotations(self):
        zpath = os.path.join(tempfile.mkdtemp(), 'y.zip')
        with zipfile.ZipFile(zpath, 'w') as z:
            z.writestr('derived/derived_windows.csv', 'a\n1\n')
            z.writestr('annotations.csv', 'n\nhello\n')
        self.assertEqual(sm.read_export(zpath), ([{'a': '1'}], [{'n': 'hello'}]))

    def test_read_reference_rounds_to_the_minute_and_skips_blanks(self):
        p = self.tmp('r.csv', 'timestamp_unix,pulse_rate_bpm\n1700000030500,70\n1700000090500,\n1700000100500,75\n')
        self.assertEqual(sm.read_reference([p]),
                         {1700000030500 // 1000 // 60 * 60: 70.0, 1700000100500 // 1000 // 60 * 60: 75.0})


class AlignParseTimeTests(unittest.TestCase):
    def test_units_by_magnitude(self):
        for s in ('1700000000', '1700000000000', '1700000000000000', '1700000000000000000'):
            self.assertEqual(ar.parse_time(s), 1.7e9, s)

    def test_iso_with_z_naive_and_offset_are_the_same_instant(self):
        self.assertEqual(ar.parse_time('2023-11-14T22:13:20Z'), 1_700_000_000.0)
        self.assertEqual(ar.parse_time(' 2023-11-14T22:13:20 '), 1_700_000_000.0)
        self.assertEqual(ar.parse_time('2023-11-14T23:13:20+01:00'), 1_700_000_000.0)

    def test_garbage_raises(self):
        with self.assertRaises(ValueError):
            ar.parse_time('yesterday')


class AlignReadReferenceTests(TempFiles):
    def test_sorts_by_time_skips_bad_rows_and_guesses_columns(self):
        p = self.tmp('r.csv', 'timestamp_iso,timestamp_unix,pulse_rate_bpm\n'
                              'x,1700000020,72\nx,1700000010,70\nx,oops,1\nx,1700000030,\n')
        t, v, tc, vc = ar.read_reference(p)
        self.assertEqual((tc, vc), ('timestamp_unix', 'pulse_rate_bpm'))
        self.assertEqual((list(t), list(v)), ([1700000010.0, 1700000020.0], [70.0, 72.0]))


class MovingAverageTests(unittest.TestCase):
    def test_constant_stays_constant_at_the_edges(self):
        np.testing.assert_allclose(ar.moving_average(np.full(20, 5.0), 7), 5.0)

    def test_length_is_preserved_even_and_odd(self):
        for n in (1, 2, 5, 6):
            self.assertEqual(len(ar.moving_average(np.arange(30.0), n)), 30)


class EstimateHrTests(unittest.TestCase):
    def window(self, bpm, seconds=10.0, amp=200.0, base=5000.0):
        t = np.arange(int(seconds * ar.FS)) / ar.FS
        return base + amp * np.sin(2 * np.pi * bpm / 60.0 * t)

    def test_clean_pulse(self):
        for bpm in (55, 72, 110):
            hr, sqi, _ = ar.estimate_hr(self.window(bpm))
            self.assertIsNotNone(hr, bpm)
            self.assertAlmostEqual(hr, bpm, delta=5)
            self.assertGreater(sqi, ar.FAIR_SQI)

    def test_flat_signal_has_no_pulse(self):
        hr, _, perf = ar.estimate_hr(np.full(250, 9_000_000.0))
        self.assertIsNone(hr)
        self.assertEqual(perf, 0.0)

    def test_tiny_amplitude_is_no_pulse(self):
        hr, _, perf = ar.estimate_hr(self.window(72, amp=1e-4, base=1e7))
        self.assertIsNone(hr)
        self.assertLess(perf, ar.NO_PULSE_PCT)

    def test_pulse_at_the_lower_band_edge_is_rejected(self):
        hr, _, _ = ar.estimate_hr(self.window(42))
        self.assertIsNone(hr)

    def test_zero_baseline_does_not_divide_by_zero(self):
        w = self.window(72, base=0.0)
        _, _, perf = ar.estimate_hr(w - w.mean())
        self.assertTrue(np.isfinite(perf))


class EstimateOffsetTests(unittest.TestCase):
    def test_too_short_overlap_is_none(self):
        tu = np.arange(0, 30, 1 / ar.FS)
        self.assertIsNone(ar.estimate_offset(tu, np.zeros(len(tu)), np.arange(30.0), np.zeros(30)))

    def test_recovers_a_known_shift_magnitude(self):
        rng = np.random.default_rng(4)
        tu = np.arange(0, 300, 1 / ar.FS)
        bursts = np.convolve(rng.random(300) > 0.9, np.ones(8), mode='same').astype(float)
        move = np.interp(tu, np.arange(300.0), bursts)
        act_t = np.arange(0, 300.0)
        act_v = np.interp(act_t - 7, np.arange(300.0), bursts)
        best = ar.estimate_offset(tu, move, act_t, act_v)
        self.assertIsNotNone(best)
        self.assertEqual(abs(best[0]), 7)
        self.assertGreater(best[1], 0.5)


if __name__ == '__main__':
    unittest.main()
