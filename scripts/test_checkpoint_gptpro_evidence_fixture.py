#!/usr/bin/env python3
"""Focused checkpoint regression for the exact generated GPTPro header fixture."""
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_work_checkpoint as checkpoint

SOURCE = "scripts/test_gptpro_pack_evidence.py"
ROOT = Path(__file__).resolve().parent.parent
BINDING = b'SYNTHETIC = "sk-proj-" + "Ab3" * 24'

class GptproCheckpointFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = (ROOT / SOURCE).read_bytes()

    def test_exact_synthetic_fixture_is_allowed(self):
        checkpoint.secret_free(self.fixture, SOURCE)

    def test_changed_synthetic_header_stays_rejected(self):
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(self.fixture.replace(b'"a" * 40', b'"a" * 41'), SOURCE)

    def test_changed_synthetic_binding_stays_rejected(self):
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(self.fixture.replace(BINDING, b'SYNTHETIC = "opaque-fixture-value"'), SOURCE)

    def test_duplicate_synthetic_binding_stays_rejected(self):
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(self.fixture + b"\n" + BINDING + b"\n", SOURCE)


    def test_annotated_synthetic_binding_stays_rejected(self):
        changed = self.fixture.replace(BINDING, BINDING + b'\nSYNTHETIC: str = "opaque-fixture-value"', 1)
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(changed, SOURCE)


    def test_literal_header_value_stays_rejected(self):
        replacement = ('"' + "sk-" + "Z" * 40 + '")))').encode()
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(self.fixture.replace(b'SYNTHETIC)))', replacement), SOURCE)

    def test_adjacent_token_stays_rejected(self):
        adjacent = ('\nfixture_key="sk-' + "Z" * 40 + '"\n').encode()
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(self.fixture + adjacent, SOURCE)

    def test_same_fixture_in_unrecognized_source_stays_rejected(self):
        with self.assertRaises(checkpoint.CheckpointError):
            checkpoint.secret_free(self.fixture, "scripts/unrecognized_fixture.py")

if __name__ == "__main__":
    unittest.main(verbosity=2)
