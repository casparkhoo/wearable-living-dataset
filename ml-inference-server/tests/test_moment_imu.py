"""Tests for the MOMENT accelerometer pilot that need no weights, GPU or network.

Run from the repo root:
    python -m unittest discover -s ml-inference-server/tests -v
The encoder itself (torch + transformers + the downloaded checkpoint) is replaced by a stub.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import moment_encoder as me  # noqa: E402
import model_heads  # noqa: E402

try:
    import moment_app  # noqa: E402
except ImportError:  # Flask missing
    moment_app = None


class ResamplingTests(unittest.TestCase):
    def test_ten_seconds_at_25hz_becomes_512_points_with_ends_kept(self):
        window = np.arange(250, dtype=float)
        out = me.resample_window(window)
        self.assertEqual(out.shape, (512, 1))
        self.assertAlmostEqual(float(out[0, 0]), 0.0, places=5)
        self.assertAlmostEqual(float(out[-1, 0]), 249.0, places=3)
        self.assertTrue(np.all(np.diff(out[:, 0]) > 0))

    def test_a_ramp_stays_a_straight_line(self):
        out = me.resample_window(np.linspace(0, 1, 500))[:, 0]
        self.assertTrue(np.allclose(np.diff(out), np.diff(out).mean(), atol=1e-4))

    def test_fifty_hz_windows_give_the_same_shape(self):
        self.assertEqual(me.resample_window(np.zeros((500, 3))).shape, (512, 3))

    def test_too_short_or_non_finite_windows_are_refused(self):
        with self.assertRaises(ValueError):
            me.resample_window(np.zeros(5))
        with self.assertRaises(ValueError):
            me.resample_window(np.array([1.0] * 20 + [float("nan")]))

    def test_window_channels_is_x_y_z_magnitude(self):
        xyz = np.tile([3.0, 4.0, 0.0], (250, 1))
        ch = me.window_channels(xyz)
        self.assertEqual(ch.shape, (4, 512))
        self.assertTrue(np.allclose(ch[3], 5.0, atol=1e-5))
        self.assertTrue(np.allclose(ch[0], 3.0, atol=1e-5))
        with self.assertRaises(ValueError):
            me.window_channels(np.zeros((250, 2)))


class StatsAndFeatureTests(unittest.TestCase):
    def test_gravity_direction_is_a_unit_vector_and_scale_free(self):
        rng = np.random.default_rng(1)
        xyz = rng.normal(0, 1, (250, 3)) + [0, 0, 10]
        s = me.window_stats(xyz)
        g = s[me.STAT_NAMES.index("gx"):me.STAT_NAMES.index("gz") + 1]
        self.assertAlmostEqual(float(np.linalg.norm(g)), 1.0, places=4)
        self.assertGreater(g[2], 0.99)
        g2 = me.window_stats(xyz * 100)[6:9]
        self.assertTrue(np.allclose(g, g2, atol=1e-5))

    def test_feature_vector_follows_the_manifest_order(self):
        emb = np.arange(4 * 512, dtype=float).reshape(4, 512)
        stats = np.arange(len(me.STAT_NAMES), dtype=float)
        v = me.feature_vector(["moment:mag", "stat:gx", "logstat:mag_std"], emb, stats)
        self.assertEqual(v.shape, (514,))
        self.assertTrue(np.array_equal(v[:512], emb[3].astype(np.float32)))
        self.assertAlmostEqual(float(v[512]), 6.0)
        self.assertAlmostEqual(float(v[513]), float(np.log1p(1.0)), places=5)

    def test_unknown_feature_is_refused(self):
        with self.assertRaises(ValueError):
            me.feature_vector(["moment:w"], np.zeros((4, 512)), np.zeros(9))


def write_head(directory, head_id="act_test", features=("moment:mag",), base="moment_1_small",
               labels=("sitting", "lying down")):
    dim = 512 * len(features)
    w = np.zeros((dim, 2), dtype=np.float32)
    w[0, 1] = 1.0
    np.savez(directory / f"{head_id}.npz", weights=w, bias=np.array([0.0, -0.5], dtype=np.float32))
    manifest = {"head_id": head_id, "head_version": "t1", "base_model_id": base, "input_dimension": dim,
                "output_type": "classification", "labels": list(labels), "weights_file": f"{head_id}.npz",
                "features": list(features), "device_types": ["OpenRing"], "validation_status": "test"}
    (directory / f"{head_id}.json").write_text(json.dumps(manifest), encoding="utf-8")


class HeadLoaderTests(unittest.TestCase):
    def test_listing_and_applying_a_classification_head(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            write_head(d)
            listed = model_heads.available_heads(d)
            self.assertEqual([h["head_id"] for h in listed], ["act_test"])
            self.assertEqual(listed[0]["features"], ["moment:mag"])
            v = np.zeros(512, dtype=np.float32)
            v[0] = 5.0
            out = model_heads.apply_head("act_test", v, base_model_id="moment_1_small", heads_dir=d)
            self.assertEqual(out["prediction"]["label"], "lying down")
            self.assertAlmostEqual(sum(out["prediction"]["probabilities"]), 1.0, places=5)

    def test_a_head_for_another_model_dimension_or_name_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            write_head(d)
            with self.assertRaises(ValueError):
                model_heads.apply_head("act_test", np.zeros(512), base_model_id="papagei_s", heads_dir=d)
            with self.assertRaises(ValueError):
                model_heads.apply_head("act_test", np.zeros(100), base_model_id="moment_1_small", heads_dir=d)
            with self.assertRaises(ValueError):
                model_heads.apply_head("nope", np.zeros(512), base_model_id="moment_1_small", heads_dir=d)
            with self.assertRaises(ValueError):
                model_heads.apply_head("../act_test", np.zeros(512), base_model_id="moment_1_small", heads_dir=d)

    def test_papagei_listing_never_includes_imu_heads(self):
        self.assertNotEqual(model_heads.HEADS_DIR, model_heads.IMU_HEADS_DIR)
        self.assertFalse(any(h["base_model_id"] == "moment_1_small" for h in model_heads.available_heads()))

    def test_shipped_imu_heads_are_well_formed(self):
        folder = model_heads.IMU_HEADS_DIR
        for path in sorted(folder.glob("*.json")) if folder.exists() else []:
            m = json.loads(path.read_text(encoding="utf-8"))
            with self.subTest(head=path.name):
                self.assertEqual(m["base_model_id"], "moment_1_small")
                self.assertEqual(m["output_type"], "classification")
                self.assertTrue(m["features"] and m["labels"])
                with np.load(folder / m["weights_file"]) as z:
                    self.assertEqual(z["weights"].shape, (m["input_dimension"], len(m["labels"])))


class _StubEncoder:
    def embed(self, channels):
        out = np.zeros((channels.shape[0], 512), dtype=np.float32)
        out[:, 0] = channels.std(axis=1)
        return out


@unittest.skipIf(moment_app is None, "flask not installed")
class WorkerEndpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        write_head(Path(self.tmp.name))
        self.old = (moment_app.encoder, moment_app.IMU_HEADS_DIR)
        moment_app.encoder = _StubEncoder()
        moment_app.IMU_HEADS_DIR = Path(self.tmp.name)
        self.client = moment_app.app.test_client()

    def tearDown(self):
        moment_app.encoder, moment_app.IMU_HEADS_DIR = self.old
        self.tmp.cleanup()

    def window(self, n=250):
        t = np.linspace(0, 10, n)
        return np.stack([np.sin(t), np.cos(t), np.full(n, 9.8)], axis=1).tolist()

    def test_infer_returns_embeddings_and_a_head_result(self):
        r = self.client.post("/infer", json={"accel": self.window(), "sample_rate": 25, "head_id": "act_test",
                                              "device_type": "OpenRing"})
        self.assertEqual(r.status_code, 200, r.get_json())
        body = r.get_json()
        self.assertEqual(set(body["embeddings"]), {"x", "y", "z", "mag"})
        self.assertIn(body["head_result"]["prediction"]["label"], ["sitting", "lying down"])
        self.assertEqual(body["preprocessing"]["resampled_to"], 512)

    def test_wrong_device_short_window_and_unknown_head_are_400(self):
        base = {"accel": self.window(), "sample_rate": 25, "head_id": "act_test"}
        self.assertEqual(self.client.post("/infer", json={**base, "device_type": "OpenEarable"}).status_code, 400)
        self.assertEqual(self.client.post("/infer", json={**base, "accel": self.window(100)}).status_code, 400)
        self.assertEqual(self.client.post("/infer", json={**base, "head_id": "missing"}).status_code, 400)
        self.assertEqual(self.client.post("/infer", json={"sample_rate": 25}).status_code, 400)

    def test_without_a_loaded_model_infer_is_a_500_not_a_crash(self):
        moment_app.encoder = None
        r = self.client.post("/infer", json={"accel": self.window()})
        self.assertEqual(r.status_code, 500)


if __name__ == "__main__":
    unittest.main()
