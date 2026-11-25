#!/usr/bin/env python3
"""Regression tests for the dependency-free processor contract validator."""

from __future__ import annotations

import copy
import unittest

import validate_processor_contract as contract


class ProcessorContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = contract.load_json(contract.CONTRACTS / "processor-manifest.schema.json")
        cls.papagei = contract.load_json(contract.PROCESSORS / "papagei_s_v1.json")

    def test_all_repository_manifests_satisfy_schema_and_invariants(self) -> None:
        for path in sorted(contract.PROCESSORS.glob("*.json")):
            with self.subTest(path=path.name):
                manifest = contract.load_json(path)
                self.assertEqual([], contract.validate_instance(manifest, self.schema, path.name))
                self.assertEqual([], contract.manifest_invariants(manifest, path.name))

    def test_unknown_model_specific_field_is_rejected(self) -> None:
        manifest = copy.deepcopy(self.papagei)
        manifest["papagei_settings"] = {}
        errors = contract.validate_instance(manifest, self.schema, "manifest")
        self.assertTrue(any("additional property is not allowed" in error for error in errors))

    def test_inconsistent_target_sample_count_is_rejected(self) -> None:
        manifest = copy.deepcopy(self.papagei)
        manifest["inputs"][0]["sampling"]["target_sample_count"] = 1249
        errors = contract.manifest_invariants(manifest, "manifest")
        self.assertTrue(any("expected 1250" in error for error in errors))

    def test_draft_requirements_must_expose_open_questions(self) -> None:
        manifest = copy.deepcopy(self.papagei)
        manifest["lifecycle"]["requirements_status"] = "draft"
        manifest["lifecycle"].pop("open_questions", None)
        errors = contract.manifest_invariants(manifest, "manifest")
        self.assertTrue(any("must list at least one open question" in error for error in errors))


if __name__ == "__main__":
    unittest.main(verbosity=2)
