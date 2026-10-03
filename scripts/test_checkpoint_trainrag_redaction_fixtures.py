"""Checkpoint the existing TrainRag redaction fixtures without relaxing literal scans."""
from pathlib import Path
import unittest
from scripts.test_codex_work_checkpoint import CP


class TrainRagRedactionFixtureTest(unittest.TestCase):
    path = "src/test/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestServiceTest.java"

    def test_existing_synthetic_redaction_fixture_is_checkpointable(self):
        CP.secret_free(Path(self.path).read_bytes(), self.path)

    def test_changed_fixture_values_are_still_blocked(self):
        statement = 'String api' + 'Key = "sk-" + "B".repeat(24);'
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(statement.encode(), self.path)

    def test_same_shape_in_production_is_still_blocked(self):
        statement = 'String api' + 'Key = "sk-" + "A".repeat(24);'
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(statement.encode(), "main/java/Example.java")

    def test_neighboring_literal_remains_blocked(self):
        statement = 'String api' + 'Key = "sk-" + "A".repeat(24);'
        statement += '\nString pass' + 'word = "synthetic-sensitive";'
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(statement.encode(), self.path)

