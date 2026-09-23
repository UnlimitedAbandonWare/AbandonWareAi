"""Exercise recovery against real temporary files, never the active checkout."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from datetime import datetime, timedelta, timezone


SCRIPT = Path(__file__).with_name("codex_work_checkpoint.py")
SPEC = importlib.util.spec_from_file_location("checkpoint", SCRIPT) if SCRIPT.exists() else None
CP = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    SPEC.loader.exec_module(CP)


def decision(**gates):
    return {
        "goalId": "synthetic-recovery-test", "reasonCode": "minimal-verified-fix",
        "risk": dict(recovery=1, blastRadius=1, regression=2, uncertainty=1, cost=0),
        "gates": {key: gates.get(key, False) for key in (
            "bulkDelete", "unrecoverableOverwrite", "credentialChange", "externalRealData",
            "paidBulkCalls", "productionMutation", "permissionChange", "irreversibleLoss")},
    }


class WorkCheckpointTest(unittest.TestCase):
    def test_config_bindings_are_references_but_literal_credentials_stay_blocked(self):
        for sentinel in ("ollama", "sk-local"):
            binding = "${llm.api-" + "key:${LLM_API_KEY:" + sentinel + "}}"
            CP.secret_free(binding.encode(), "main/java/Fixture.java")
        binding = "${llm.api-" + "key:${LLM_API_KEY:literal-credential}}"
        with self.assertRaises(CP.CheckpointError):
            CP.secret_free(binding.encode(), "main/java/Fixture.java")
        label = "api" + "-key: "
        for reference in ("${OPENAI_API_KEY}", "${OPENAI_API_KEY:}",
                          "${LLM_API_KEY:ollama}", "${BRAVE_API_KEY:__MISSING__}"):
            CP.secret_free((label + reference).encode(), "main/resources/application-llm.yaml")
        for literal in ("fixture-private-value", "${KEY:fixture-private-value}",
                        "${OPENAI_API_KEY}" + "\n" + label + "fixture-private-value"):
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free((label + literal).encode(), "main/resources/application-llm.yaml")
        with self.assertRaises(CP.CheckpointError):
            CP.secret_free((label + "${LLM_API_KEY:ollama}").encode(), "docs/notes.md")

    def setUp(self):
        self.assertIsNotNone(CP, "checkpoint recovery helper is not implemented")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "docs").mkdir()
        self.file = self.root / "docs/notes.md"
        self.file.write_bytes(b"existing user edits\r\n")
        self.run = "data/agent-handoff/codex-autonomy/test-cycle"

    def start(self, targets=None):
        return CP.begin(self.root, self.run, targets or ["docs/notes.md"], decision())

    def change(self):
        self.start()
        self.file.write_bytes(b"new change\n")
        CP.seal(self.root, self.run)

    def test_failed_verification_restores_exact_dirty_preimage(self):
        self.change()
        result = CP.finish(self.root, self.run, 1, "focused-unit-test", "AssertionError: mismatch")
        self.assertEqual("rolled_back", result["status"])
        self.assertEqual("test_assertion", result["failureClass"])
        self.assertEqual(b"existing user edits\r\n", self.file.read_bytes())
        self.assertNotEqual(0, result["verificationExitCode"])
        diff = (self.root / self.run / "change.diff").read_text()
        self.assertIn("-existing user edits", diff)
        self.assertIn("+new change", diff)

    def test_success_keeps_patch_and_finalizes_checkpoint(self):
        self.change()
        result = CP.finish(self.root, self.run, 0, "focused-unit-test")
        self.assertEqual("verified", result["status"])
        self.assertEqual(b"new change\n", self.file.read_bytes())
        with self.assertRaises(CP.CheckpointError):
            CP.finish(self.root, self.run, 1, "replayed-failure")

    def test_new_task_file_is_removed_on_failed_verification(self):
        self.start(["docs/notes.md", "docs/new.md"])
        self.file.write_bytes(b"changed")
        new = self.root / "docs/new.md"
        new.write_bytes(b"task-owned")
        CP.seal(self.root, self.run)
        result = CP.finish(self.root, self.run, 2, "focused-test")
        self.assertEqual("rolled_back", result["status"])
        self.assertFalse(new.exists())
        self.assertEqual(b"existing user edits\r\n", self.file.read_bytes())

    def test_foreign_postimage_is_preserved_and_rollback_is_held(self):
        self.change()
        self.file.write_bytes(b"concurrent writer")
        result = CP.finish(self.root, self.run, 1, "focused-test")
        self.assertEqual("hold", result["status"])
        self.assertEqual("postimage-drift", result["firstBlockingRule"])
        self.assertFalse(result["repositoryWideHold"])
        self.assertEqual(b"concurrent writer", self.file.read_bytes())

    def test_all_backups_are_validated_before_any_rollback_write(self):
        self.start(["docs/notes.md", "docs/new.md"])
        self.file.write_bytes(b"changed")
        (self.root / "docs/new.md").write_bytes(b"new")
        CP.seal(self.root, self.run)
        (self.root / self.run / "before/0.bin").write_bytes(b"tampered")
        result = CP.finish(self.root, self.run, 1, "focused-test")
        self.assertEqual("hold", result["status"])
        self.assertEqual("backup-integrity", result["firstBlockingRule"])
        self.assertEqual(b"changed", self.file.read_bytes())
        self.assertTrue((self.root / "docs/new.md").exists())

    def test_unsealed_failure_cannot_guess_ownership(self):
        self.start()
        self.file.write_bytes(b"unknown owner")
        with self.assertRaises(CP.CheckpointError):
            CP.finish(self.root, self.run, 1, "interrupted")
        self.assertEqual(b"unknown owner", self.file.read_bytes())

    def test_high_loss_gate_overrides_low_risk_and_writes_nothing(self):
        for gate in decision()["gates"]:
            d = decision(**{gate: True})
            d["risk"] = {key: 0 for key in d["risk"]}
            with self.subTest(gate=gate):
                result = CP.begin(self.root, self.run, ["docs/notes.md"], d)
                self.assertEqual("approval_required", result["status"])
                self.assertFalse((self.root / self.run).exists())

    def test_high_local_risk_requires_stronger_proof_not_step_approval(self):
        d = decision()
        d["risk"] = {key: 4 for key in d["risk"]}
        result = CP.assess(d)
        self.assertEqual("autonomous", result["status"])
        self.assertEqual(100, result["riskScore"])
        self.assertEqual("split-and-broaden", result["verificationDepth"])

    def test_missing_gate_is_unknown_not_safe(self):
        d = decision()
        del d["gates"]["externalRealData"]
        with self.assertRaises(CP.CheckpointError):
            CP.assess(d)

    def test_traversal_duplicate_and_metadata_targets_are_rejected(self):
        for targets in (["../outside.md"], ["docs/notes.md", "docs/NOTES.md"],
                        [".git/config"], ["docs/../docs/notes.md"]):
            with self.subTest(targets=targets), self.assertRaises(CP.CheckpointError):
                self.start(targets)
        self.assertFalse((self.root / self.run).exists())

    def test_secret_source_is_rejected_before_snapshot(self):
        self.file.write_text("api" + "_key=" + "sk-" + "x" * 28)
        with self.assertRaises(CP.CheckpointError):
            self.start()
        self.assertFalse((self.root / self.run).exists())

    def test_symlink_target_is_rejected(self):
        link = self.root / "docs/link.md"
        try:
            link.symlink_to(self.file)
        except OSError:
            self.skipTest("symlink privilege unavailable")
        with self.assertRaises(CP.CheckpointError):
            self.start(["docs/link.md"])

    def test_application_target_requires_existing_owner_lease(self):
        app = self.root / "main/java/Example.java"
        app.parent.mkdir(parents=True)
        app.write_text("class Example {}")
        with self.assertRaises(CP.CheckpointError):
            self.start(["main/java/Example.java"])

    def test_executable_helpers_also_require_existing_source_lease(self):
        with self.assertRaises(CP.CheckpointError):
            self.start(["scripts/helper.py"])

    def test_checkpoint_cannot_write_payloads_into_published_docs(self):
        with self.assertRaises(CP.CheckpointError):
            CP.begin(self.root, "docs/snapshots", ["docs/notes.md"], decision())
        self.assertFalse((self.root / "docs/snapshots").exists())

    def test_expired_or_changed_lease_cannot_restore_source(self):
        source = self.root / "scripts/example.py"
        source.parent.mkdir()
        source.write_bytes(b"original")
        lease_name = "__patch_drop__/source-edit-locks/owned.lock/lease.json"
        lease_path = self.root / lease_name
        lease_path.parent.mkdir(parents=True)
        lease_data = dict(root=str(self.root), ownerId="synthetic-owner", mutationAllowed=True,
                          coordinationMode="target-scoped", targetPaths=["scripts/example.py"],
                          expiresAtUtc=(datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat())
        lease_path.write_text(json.dumps(lease_data))
        CP.begin(self.root, self.run, ["scripts/example.py"], decision(), lease_name)
        source.write_bytes(b"new")
        CP.seal(self.root, self.run)
        lease_data["expiresAtUtc"] = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
        lease_path.write_text(json.dumps(lease_data))
        result = CP.finish(self.root, self.run, 1, "source-test")
        self.assertEqual("hold", result["status"])
        self.assertEqual(b"new", source.read_bytes())
        self.assertTrue(lease_path.exists())

    def test_valid_source_lease_restores_owned_bytes_without_releasing_lease(self):
        source = self.root / "scripts/example.py"
        source.parent.mkdir()
        source.write_bytes(b"original")
        lease_name = "__patch_drop__/source-edit-locks/owned.lock/lease.json"
        lease_path = self.root / lease_name
        lease_path.parent.mkdir(parents=True)
        lease_path.write_text(json.dumps(dict(
            root=str(self.root), ownerId="synthetic-owner", mutationAllowed=True,
            coordinationMode="target-scoped", targetPaths=["scripts/example.py"],
            expiresAtUtc=(datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat())))
        CP.begin(self.root, self.run, ["scripts/example.py"], decision(), lease_name)
        source.write_bytes(b"new")
        CP.seal(self.root, self.run)
        result = CP.finish(self.root, self.run, 1, "source-test")
        self.assertEqual("rolled_back", result["status"])
        self.assertEqual(b"original", source.read_bytes())
        self.assertTrue(lease_path.exists())

    def test_mid_rollback_race_reports_partial_and_preserves_foreign_bytes(self):
        second = self.root / "docs/second.md"
        second.write_bytes(b"second original")
        self.start(["docs/notes.md", "docs/second.md"])
        self.file.write_bytes(b"first patch")
        second.write_bytes(b"second patch")
        CP.seal(self.root, self.run)
        replace = CP.os.replace

        def concurrent_write(src, dst):
            replace(src, dst)
            if Path(dst) == self.file:
                second.write_bytes(b"foreign bytes")

        with patch.object(CP.os, "replace", side_effect=concurrent_write):
            result = CP.finish(self.root, self.run, 1, "source-test")
        self.assertEqual("hold", result["status"])
        self.assertEqual(1, result["restoredCount"])
        self.assertEqual(b"existing user edits\r\n", self.file.read_bytes())
        self.assertEqual(b"foreign bytes", second.read_bytes())

    def test_failure_classification_never_saves_raw_log(self):
        self.change()
        raw = "error: cannot find symbol\n" + "Bearer " + "a" * 30
        result = CP.finish(self.root, self.run, 1, "compile-check", raw)
        self.assertEqual("compile", result["failureClass"])
        evidence = (self.root / self.run / "checkpoint.json").read_text()
        self.assertNotIn("a" * 30, evidence)
        self.assertNotIn("cannot find symbol", evidence)

    def test_success_after_target_drift_is_not_false_green(self):
        self.change()
        self.file.write_bytes(b"changed during test")
        result = CP.finish(self.root, self.run, 0, "focused-test")
        self.assertEqual("hold", result["status"])
        self.assertEqual(b"changed during test", self.file.read_bytes())


if __name__ == "__main__":
    unittest.main()
