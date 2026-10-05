"""Synthetic scanner fixtures for the quota checkpoint blocker; no credentials."""
from pathlib import Path
import unittest

from scripts.test_codex_work_checkpoint import CP


class JavaMemberAssignmentTest(unittest.TestCase):
    word = "to" + "ken"

    def scan(self, text, path="main/java/Fixture.java"):
        CP.secret_free(text.encode(), path)

    def test_same_named_runtime_argument_copy_is_not_a_literal(self):
        self.scan(f"this.{self.word} = {self.word};")

    def test_current_admission_preimage_scans(self):
        path = "main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java"
        root = Path(__file__).resolve().parent.parent
        CP.secret_free((root / path).read_bytes(), path)

    def test_literal_expression_and_other_reference_remain_blocked(self):
        for rhs in ('"synthetic-value";', self.word + ' + "synthetic-value";', "otherValue;"):
            with self.subTest(rhs=rhs), self.assertRaises(CP.CheckpointError):
                self.scan(f"this.{self.word} = {rhs}")

    def test_comments_strings_and_other_languages_remain_strict(self):
        copy = f"this.{self.word} = {self.word};"
        for text in ("// " + copy, "/* " + copy + " */", '"' + copy + '"'):
            with self.subTest(text=text), self.assertRaises(CP.CheckpointError):
                self.scan(text)
        with self.assertRaises(CP.CheckpointError):
            self.scan(copy, "docs/fixture.md")

    def test_following_literal_is_still_scanned(self):
        with self.assertRaises(CP.CheckpointError):
            self.scan(f"this.{self.word} = {self.word};\napi" + 'Key="synthetic-value";')


if __name__ == "__main__":
    unittest.main()
