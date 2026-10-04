"""Runtime argument selection regression; synthetic inputs, no credential reads."""
import unittest
from scripts.test_codex_work_checkpoint import CP

class GitArgumentSelectionScanTest(unittest.TestCase):
    def test_owned_guard_and_catalogue_fixture_diff_paths_preserve_path_boundary(self):
        artifact = "data/agent-handoff/synthetic-task/changes.diff"
        for path in ("__patch_drop__/source_edit_lease_contract.ps1",
                     "src/test/resources/route-identity/catalog-selectable.json"):
            diff = f"--- a/{path}\n+++ b/{path}\n@@ -1 +1 @@\n-old\n+new\n"
            with self.subTest(path=path):
                CP.secret_free(diff.encode(), artifact)
        for path in (".secrets/example.properties", "scripts/unowned-fixture.json"):
            diff = f"--- a/{path}\n+++ b/{path}\n@@ -1 +1 @@\n-old\n+new\n"
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, "invalid-owned-diff"):
                CP.secret_free(diff.encode(), artifact)

    def test_absent_diff_preimage_is_allowed_but_existing_malformed_diff_is_blocked(self):
        path = "data/agent-handoff/synthetic-task/changes.diff"
        CP.secret_free(None, path)
        for malformed in (b"", b"not a unified diff"):
            with self.subTest(length=len(malformed)), self.assertRaisesRegex(CP.CheckpointError, "invalid-owned-diff"):
                CP.secret_free(malformed, path)

    def test_runtime_selection_is_allowed_and_adjacent_literals_stay_blocked(self):
        expression = "$to" + "ken = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }"
        path = "__patch_drop__/source_edit_lease_contract.ps1"
        CP.secret_free(expression.encode(), path)
        for value in (expression.replace("$match.Groups[2].Value", "'synthetic-value'"),
                      expression + "\napi" + "Key='synthetic-value'"):
            with self.subTest(valueLength=len(value)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(value.encode(), path)
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(expression.encode(), "scripts/other.ps1")

if __name__ == "__main__":
    unittest.main()
