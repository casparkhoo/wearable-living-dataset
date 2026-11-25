"""Schema checks for every prediction head in ml-inference-server/heads.

No model, GPU or network is needed. Run from the repo root:
    python -m unittest discover -s ml-inference-server/tests -v
"""
import json
import unittest
from pathlib import Path

import numpy as np

HEADS = Path(__file__).resolve().parents[1] / "heads"
REQUIRED = {"head_id", "head_version", "base_model_id", "input_dimension", "output_type", "weights_file"}
OUTPUT_TYPES = {"regression", "classification"}


def manifests():
    return sorted(HEADS.glob("*.json"))


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


class HeadManifestTests(unittest.TestCase):
    def test_there_are_heads_to_check(self):
        self.assertTrue(manifests())

    def test_every_manifest_has_the_required_keys(self):
        for path in manifests():
            with self.subTest(head=path.name):
                m = load(path)
                self.assertFalse(REQUIRED - set(m), f"missing {REQUIRED - set(m)}")
                self.assertEqual(m["head_id"], path.stem)
                self.assertEqual(m["base_model_id"], "papagei_s")
                self.assertIn(m["output_type"], OUTPUT_TYPES)
                self.assertIsInstance(m["input_dimension"], int)
                self.assertGreater(m["input_dimension"], 0)
                self.assertTrue(str(m["head_version"]))
                weights_path = HEADS / m["weights_file"]
                self.assertEqual(weights_path.resolve().parent, HEADS.resolve(), "weights must sit in heads/")
                self.assertTrue(weights_path.is_file(), f"{weights_path.name} missing")

    def test_weights_match_the_manifest(self):
        for path in manifests():
            with self.subTest(head=path.name):
                m = load(path)
                with np.load(HEADS / m["weights_file"]) as z:
                    self.assertEqual(set(z.files), {"weights", "bias"})
                    w, b = z["weights"], z["bias"]
                self.assertEqual(w.ndim, 2)
                self.assertEqual(w.shape[0], m["input_dimension"])
                self.assertEqual(b.shape, (w.shape[1],))
                self.assertTrue(np.all(np.isfinite(w)) and np.all(np.isfinite(b)))
                if m["output_type"] == "regression":
                    self.assertEqual(w.shape[1], 1)
                    self.assertTrue(m.get("unit"))
                else:
                    self.assertGreaterEqual(w.shape[1], 2)
                    self.assertEqual(len(m.get("labels", [])), w.shape[1])

    def test_a_head_applies_to_an_embedding_of_the_declared_size(self):
        for path in manifests():
            with self.subTest(head=path.name):
                m = load(path)
                with np.load(HEADS / m["weights_file"]) as z:
                    out = np.zeros(m["input_dimension"], dtype=np.float32) @ z["weights"] + z["bias"]
                self.assertTrue(np.all(np.isfinite(out)))

    def test_declared_label_range_is_plausible(self):
        for path in manifests():
            rng = (load(path).get("training") or {}).get("label_range_bpm")
            if rng:
                with self.subTest(head=path.name):
                    self.assertEqual(len(rng), 2)
                    self.assertLess(rng[0], rng[1])
                    self.assertGreater(rng[0], 0)
                    self.assertLess(rng[1], 300)

    def test_head_ids_are_unique(self):
        ids = [load(p)["head_id"] for p in manifests()]
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
