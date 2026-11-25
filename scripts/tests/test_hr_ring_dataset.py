"""Offline tests for scripts/hr_ring_dataset.py helpers (no dataset needed)."""
import io
import os
import pickle
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import hr_ring_dataset as H   # noqa: E402


class RingDatasetHelpers(unittest.TestCase):
    def test_to_uniform_25hz_has_no_edge_step_on_offset_counts(self):
        t = np.arange(3000) / 100
        x = 600000 + 300 * np.sin(2 * np.pi * 1.2 * t)
        y = H.to_uniform(x, 100, 25)
        self.assertEqual(len(y), 750)
        # zero padding would give a ~2e5 step at the ends
        self.assertLess(np.abs(y - 600000).max(), 400)

    def test_motion_group(self):
        labels = ('striding', 'deepsquat', 'shaking_head', 'sitting', 'spo2', 'talking', 'None')
        self.assertEqual([H.motion_group(l) for l in labels],
                         ['motion', 'motion', 'motion', 'stationary', 'stationary', 'stationary', 'stationary'])

    def test_unpickler_blocks_code_and_allows_arrays(self):
        class Evil:
            def __reduce__(self):
                return (os.system, ('echo pwned',))
        with self.assertRaises(pickle.UnpicklingError):
            H.DataOnlyUnpickler(io.BytesIO(pickle.dumps(Evil()))).load()
        a = np.arange(5.0)
        self.assertTrue(np.array_equal(H.DataOnlyUnpickler(io.BytesIO(pickle.dumps(a))).load(), a))


if __name__ == '__main__':
    unittest.main()
