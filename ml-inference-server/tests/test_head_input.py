"""Input polarity per head (input_transform.invert in the manifest).

No model, GPU or network is needed: the encoder is replaced by a stub that records its input. Run from the repo root:
    python -m unittest discover -s ml-inference-server/tests -v
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

SERVER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER))

import model_heads  # noqa: E402
from preprocessing import PpgWindowConfig, prepare_ppg_window  # noqa: E402

RNG = np.random.default_rng(0)
RAW = (9_000_000 + 300 * np.sin(2 * np.pi * 1.1 * np.arange(250) / 25.0) + RNG.normal(0, 20, 250)).tolist()


def write_head(directory, head_id, transform):
    manifest = {"head_id": head_id, "head_version": "test", "base_model_id": "papagei_s", "input_dimension": 512,
                "output_type": "regression", "unit": "bpm", "weights_file": f"{head_id}.npz"}
    if transform is not None:
        manifest["input_transform"] = transform
    (Path(directory) / f"{head_id}.json").write_text(json.dumps(manifest), encoding="utf-8")
    np.savez(Path(directory) / f"{head_id}.npz", weights=np.zeros((512, 1), np.float32), bias=np.array([60.0], np.float32))


class InputSignTests(unittest.TestCase):
    def test_installed_v2_head_keeps_the_input_unchanged(self):
        self.assertEqual(model_heads.input_sign("hr_ring_stretch_v2"), 1.0)
        x = np.asarray(RAW, dtype=np.float32)
        self.assertTrue(np.array_equal(model_heads.head_input(x, "hr_ring_stretch_v2"), x))

    def test_every_installed_head_is_unchanged(self):
        for path in sorted(model_heads.HEADS_DIR.glob("*.json")):
            with self.subTest(head=path.stem):
                self.assertEqual(model_heads.input_sign(path.stem), 1.0)

    def test_inverting_manifest_negates_and_false_or_absent_does_not(self):
        with tempfile.TemporaryDirectory() as d:
            write_head(d, "inv", {"invert": True})
            write_head(d, "noinv", {"invert": False})
            write_head(d, "plain", None)
            x = np.asarray(RAW, dtype=np.float32)
            self.assertTrue(np.array_equal(model_heads.head_input(x, "inv", Path(d)), -x))
            self.assertTrue(np.array_equal(model_heads.head_input(x, "noinv", Path(d)), x))
            self.assertTrue(np.array_equal(model_heads.head_input(x, "plain", Path(d)), x))

    def test_only_a_literal_true_inverts(self):
        with tempfile.TemporaryDirectory() as d:
            write_head(d, "str", {"invert": "true"})
            self.assertEqual(model_heads.input_sign("str", Path(d)), 1.0)

    def test_unknown_or_unsafe_head_is_rejected(self):
        for bad in ("does_not_exist", "../heads/hr_ring_stretch_v2"):
            with self.subTest(head=bad), self.assertRaises(ValueError):
                model_heads.input_sign(bad)


class InferEndpointTests(unittest.TestCase):
    """POST /infer with a stub encoder: what reaches the encoder for each head (live and recompute both use /infer)."""

    def setUp(self):
        try:
            import app as worker
        except ImportError as exc:   # flask not installed in this environment
            self.skipTest(f"worker dependencies missing: {exc}")
        self.worker = worker
        self.seen = []
        self.client = worker.app.test_client()

    def post(self, head_id, heads_dir=None):
        stub = lambda x: (self.seen.append(np.array(x)), np.zeros(512, np.float32))[1]
        patches = [mock.patch.object(self.worker, "embed", stub)]
        if heads_dir is not None:
            patches.append(mock.patch.object(model_heads, "HEADS_DIR", Path(heads_dir)))
            patches.append(mock.patch.object(self.worker, "apply_head",
                                              lambda h, e: model_heads.apply_head(h, e, heads_dir=Path(heads_dir))))
        body = {"ppg_data": RAW, "sample_rate": 25, "window_duration_s": 10}
        if head_id:
            body["head_id"] = head_id
        with patches[0]:
            if len(patches) > 1:
                with patches[1], patches[2]:
                    return self.client.post("/infer", json=body)
            return self.client.post("/infer", json=body)

    def expected(self, sign):
        x, _ = prepare_ppg_window(np.asarray(RAW, dtype=np.float32) * sign, PpgWindowConfig(source_sample_rate_hz=25.0))
        return x

    def test_v2_request_reaches_the_encoder_exactly_as_before(self):
        r = self.post("hr_ring_stretch_v2")
        self.assertEqual(r.status_code, 200, r.get_json())
        self.assertTrue(np.array_equal(self.seen[-1], self.expected(1.0)))

    def test_no_head_means_no_change(self):
        r = self.post(None)
        self.assertEqual(r.status_code, 200, r.get_json())
        self.assertTrue(np.array_equal(self.seen[-1], self.expected(1.0)))

    def test_inverting_head_gets_the_negated_window(self):
        with tempfile.TemporaryDirectory() as d:
            write_head(d, "inv", {"invert": True})
            r = self.post("inv", heads_dir=d)
            self.assertEqual(r.status_code, 200, r.get_json())
            self.assertTrue(np.allclose(self.seen[-1], self.expected(-1.0)))
            self.assertTrue(np.allclose(self.seen[-1], -self.expected(1.0), atol=1e-5))
