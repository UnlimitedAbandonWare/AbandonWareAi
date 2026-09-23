"""Falsifiers for the read-only Display stage selector.

All receipts and target files here are synthetic, host-local fixtures. Passing
these tests proves selector behavior only; it is never Display/runtime proof.
"""

import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import next_step as selector


class NextStepTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="display-selector-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for relative in selector.TASK_FILES:
            self.write(relative, "synthetic task policy input\n")

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")
        return path

    def receipt_path(self, stage):
        return self.root / selector.RECEIPT_DIR / (stage + ".json")

    def make_receipt(self, stage):
        contract = selector.STAGES[stage]
        targets = {}
        for relative in contract["targets"]:
            path = self.root / relative
            if not path.exists():
                self.write(relative, "synthetic application target\n")
            targets[relative] = selector.file_hash(path)
        checks = {}
        for name, kind in contract["checks"].items():
            relative = (Path(selector.RECEIPT_DIR) / "evidence" /
                        (stage + "-" + name + ".txt")).as_posix()
            content = "synthetic verification evidence\n"
            if stage == "E0" and name == "intake-reviewed":
                content = json.dumps({
                    "status": "PASS", "reason": "match",
                    "canonicalExecutionRoot": "C:\\AbandonWare\\demo-1\\demo-1\\src",
                    "targetRel": "AGENTS.md", "withinRoot": True,
                    "exists": True, "reparseRisk": False,
                    "desktopTarget": "C:\\AbandonWare\\demo-1\\demo-1\\src\\AGENTS.md",
                })
            path = self.write(relative, content)
            checks[name] = {
                "kind": kind,
                "performedNow": True,
                "observedAt": datetime.now(timezone.utc).isoformat(),
                "exitCode": 0,
                "result": "pass",
                "evidence": {"path": relative,
                             "sha256": selector.file_hash(path)},
            }
        return {
            "schemaVersion": 1,
            "taskId": "meta-display-sync-v1-20260912",
            "stage": stage,
            "status": "pass",
            "executionOwner": "desktop",
            "executionMode": "executed",
            "performedNow": True,
            "taskBinding": selector.task_binding(self.root),
            "targetHashes": targets,
            "checks": checks,
        }

    def save_receipt(self, stage, receipt=None):
        if receipt is None:
            receipt = self.make_receipt(stage)
        path = self.receipt_path(stage)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(receipt), encoding="utf-8")
        return receipt

    def pass_through(self, last_stage):
        for stage in selector.STAGES:
            self.save_receipt(stage)
            if stage == last_stage:
                break

    def assert_read_only_contract(self, result):
        self.assertIs(result["sourceMutationAllowed"], False)
        self.assertIs(result["receiptConsistencyOnly"], True)
        self.assertEqual(result["desktopFinalProof"], "evidence_needed")
        self.assertNotEqual(result.get("runtimeLineageVerdict"), "PASS")

    def assert_ready(self, stage):
        result = selector.inspect(self.root)
        self.assertEqual(result["status"], "READY")
        self.assertEqual(result["nextStage"], stage)
        self.assertEqual(result["reason"], "evidence-needed")
        self.assert_read_only_contract(result)
        return result

    def assert_hold(self, stage):
        result = selector.inspect(self.root)
        self.assertEqual(result["status"], "HOLD")
        self.assertEqual(result["nextStage"], stage)
        self.assertIsInstance(result["reason"], str)
        self.assertTrue(result["reason"])
        self.assertEqual(selector.inspect(self.root)["reason"], result["reason"])
        self.assert_read_only_contract(result)
        return result

    def test_missing_required_input_holds(self):
        (self.root / list(selector.TASK_FILES)[0]).unlink()
        result = selector.inspect(self.root)
        self.assertEqual(result["status"], "HOLD")
        self.assertEqual(result["reason"], "input-artifact-missing")
        self.assert_read_only_contract(result)

    def test_initial_stage_requires_desktop_intake_receipt(self):
        result = self.assert_ready("E0")
        self.assertNotEqual(result.get("observedExecutionOwner"), "desktop")

    def test_existing_application_files_do_not_complete_any_stage(self):
        for contract in selector.STAGES.values():
            for relative in contract["targets"]:
                self.write(relative, "existing file is not execution proof\n")
        self.assert_ready("E0")

    def test_valid_receipts_advance_exactly_one_stage_in_order(self):
        stages = list(selector.STAGES)
        for index, stage in enumerate(stages[:-1]):
            with self.subTest(stage=stage):
                self.save_receipt(stage)
                self.assert_ready(stages[index + 1])

    def test_later_receipt_cannot_skip_a_gap(self):
        self.save_receipt("E0")
        self.save_receipt("E2")
        self.save_receipt("E4")
        self.assert_ready("E1")

    def test_similarly_named_receipt_is_not_selected(self):
        receipt = self.make_receipt("E0")
        self.write(Path(selector.RECEIPT_DIR) / "E0-latest.json",
                   json.dumps(receipt))
        self.assert_ready("E0")

    def test_corrupt_json_holds_without_echoing_payload(self):
        marker = "SYNTHETIC_PRIVATE_PAYLOAD_DO_NOT_ECHO"
        self.write(Path(selector.RECEIPT_DIR) / "E0.json", "{" + marker)
        result = self.assert_hold("E0")
        self.assertNotIn(marker, json.dumps(result))

    def test_duplicate_json_keys_are_rejected_before_last_value_can_win(self):
        for location in ("receipt", "intake-evidence"):
            with self.subTest(location=location):
                receipt = self.make_receipt("E0")
                if location == "receipt":
                    raw = '{"status":"planned",' + json.dumps(receipt)[1:]
                    self.write(Path(selector.RECEIPT_DIR) / "E0.json", raw)
                else:
                    evidence = receipt["checks"]["intake-reviewed"]["evidence"]
                    path = self.root / evidence["path"]
                    raw = '{"exists":false,' + path.read_text(encoding="utf-8")[1:]
                    path.write_text(raw, encoding="utf-8")
                    evidence["sha256"] = selector.file_hash(path)
                    self.save_receipt("E0", receipt)
                result = self.assert_hold("E0")
                self.assertEqual(result["reason"], "duplicate-json-key")

    def test_oversized_receipt_holds(self):
        receipt = json.dumps(self.make_receipt("E0"))
        self.write(Path(selector.RECEIPT_DIR) / "E0.json",
                   receipt + (" " * 65537))
        self.assert_hold("E0")

    def test_oversized_evidence_is_rejected_even_with_matching_hash(self):
        self.save_receipt("E0")
        receipt = self.make_receipt("E1")
        evidence = next(iter(receipt["checks"].values()))["evidence"]
        path = self.write(evidence["path"], b"x" * (1024 * 1024 + 1))
        evidence["sha256"] = selector.file_hash(path)
        self.save_receipt("E1", receipt)
        result = self.assert_hold("E1")
        self.assertEqual(result["reason"], "input-too-large")

    def test_secret_patterns_are_rejected_without_echoing_them(self):
        token = "s" + "k-" + ("A" * 32)
        for location in ("receipt", "evidence"):
            with self.subTest(location=location):
                receipt = self.make_receipt("E0")
                if location == "receipt":
                    receipt["diagnostic"] = token
                else:
                    evidence = receipt["checks"]["intake-reviewed"]["evidence"]
                    path = self.root / evidence["path"]
                    content = json.loads(path.read_text(encoding="utf-8"))
                    content["diagnostic"] = token
                    path.write_text(json.dumps(content), encoding="utf-8")
                    evidence["sha256"] = selector.file_hash(path)
                self.save_receipt("E0", receipt)
                result = self.assert_hold("E0")
                self.assertEqual(result["reason"], "secret-pattern-detected")
                self.assertNotIn(token, json.dumps(result))

    def test_receipt_requires_desktop_owner(self):
        for owner in ("notebook", "browser", "desktop-implied"):
            with self.subTest(owner=owner):
                receipt = self.make_receipt("E0")
                receipt["executionOwner"] = owner
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_receipt_requires_actual_current_execution(self):
        for field in ("performedNow", "check.performedNow"):
            with self.subTest(field=field):
                receipt = self.make_receipt("E0")
                if field == "performedNow":
                    receipt["performedNow"] = False
                else:
                    next(iter(receipt["checks"].values()))["performedNow"] = False
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_reviewed_or_proposed_execution_cannot_advance(self):
        for mode in ("proposed", "reviewed-evidence"):
            with self.subTest(mode=mode):
                receipt = self.make_receipt("E0")
                receipt["executionMode"] = mode
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_every_check_requires_valid_timezone_aware_observed_at(self):
        invalid_values = (None, "not-a-timestamp", "2026-09-12T12:00:00")
        for observed_at in invalid_values:
            with self.subTest(value_kind=str(observed_at)):
                receipt = self.make_receipt("E0")
                next(iter(receipt["checks"].values()))["observedAt"] = observed_at
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")
        receipt = self.make_receipt("E0")
        del next(iter(receipt["checks"].values()))["observedAt"]
        self.save_receipt("E0", receipt)
        self.assert_hold("E0")

    def test_utc_z_and_explicit_offsets_represent_valid_observed_instants(self):
        now = datetime.now(timezone.utc)
        timestamps = (now.isoformat().replace("+00:00", "Z"),
                      now.astimezone(timezone(timedelta(hours=9))).isoformat())
        for observed_at in timestamps:
            with self.subTest(timestamp_form=observed_at[-6:]):
                receipt = self.make_receipt("E0")
                next(iter(receipt["checks"].values()))["observedAt"] = observed_at
                self.save_receipt("E0", receipt)
                self.assert_ready("E1")

    def test_future_check_timestamp_outside_clock_tolerance_is_rejected(self):
        receipt = self.make_receipt("E0")
        next(iter(receipt["checks"].values()))["observedAt"] = (
            datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
        self.save_receipt("E0", receipt)
        self.assert_hold("E0")

    def test_static_stages_have_no_runtime_age_limit(self):
        old_timestamp = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
        for stage in ("E0", "E1", "E2"):
            receipt = self.make_receipt(stage)
            for check in receipt["checks"].values():
                check["observedAt"] = old_timestamp
            self.save_receipt(stage, receipt)
        self.assert_ready("E3")

    def test_each_runtime_stage_check_expires_after_twenty_four_hours(self):
        old_timestamp = (datetime.now(timezone.utc) - timedelta(hours=25)).isoformat()
        self.pass_through("E2")
        for stage in ("E3", "E4"):
            for name in selector.STAGES[stage]["checks"]:
                with self.subTest(stage=stage, check=name):
                    receipt = self.make_receipt(stage)
                    receipt["checks"][name]["observedAt"] = old_timestamp
                    self.save_receipt(stage, receipt)
                    result = self.assert_hold(stage)
                    self.assertEqual(result["reason"], "runtime-evidence-stale")
            self.save_receipt(stage)

    def test_intake_requires_existing_desktop_resolver_pass_metadata(self):
        for mutation in ("plain-pass", "lexical-only", "wrong-root",
                         "missing-target", "reparse-risk"):
            with self.subTest(mutation=mutation):
                receipt = self.make_receipt("E0")
                evidence = receipt["checks"]["intake-reviewed"]["evidence"]
                path = self.root / evidence["path"]
                content = json.loads(path.read_text(encoding="utf-8"))
                if mutation == "plain-pass":
                    content = "PASS"
                elif mutation == "lexical-only":
                    content["reason"] = "lexical-only"
                elif mutation == "wrong-root":
                    content["canonicalExecutionRoot"] = "C:\\elsewhere"
                elif mutation == "missing-target":
                    content["exists"] = False
                else:
                    content["reparseRisk"] = True
                path.write_text(json.dumps(content), encoding="utf-8")
                evidence["sha256"] = selector.file_hash(path)
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_failed_command_or_result_does_not_advance(self):
        for field, value in (("exitCode", 1), ("result", "fail")):
            with self.subTest(field=field):
                receipt = self.make_receipt("E0")
                next(iter(receipt["checks"].values()))[field] = value
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_required_check_cannot_be_omitted(self):
        receipt = self.make_receipt("E0")
        receipt["checks"].clear()
        self.save_receipt("E0", receipt)
        self.assert_hold("E0")

    def test_wrong_evidence_kind_does_not_advance(self):
        receipt = self.make_receipt("E0")
        next(iter(receipt["checks"].values()))["kind"] = "file-exists"
        self.save_receipt("E0", receipt)
        self.assert_hold("E0")

    def test_wrong_stage_task_or_schema_is_rejected(self):
        for field, value in (("stage", "E1"), ("taskId", "unrelated-task"),
                             ("schemaVersion", 2), ("status", "planned")):
            with self.subTest(field=field):
                receipt = self.make_receipt("E0")
                receipt[field] = value
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_task_change_invalidates_existing_receipt(self):
        self.save_receipt("E0")
        self.write(list(selector.TASK_FILES)[0], "changed task contract\n")
        self.assert_hold("E0")

    def test_current_target_change_invalidates_stage_receipt(self):
        self.pass_through("E1")
        self.write(selector.STAGES["E1"]["targets"][0], "changed client\n")
        self.assert_hold("E1")

    def test_unrelated_file_change_does_not_invalidate_receipt(self):
        self.pass_through("E3")
        self.write("unrelated/notes.txt", "first unrelated content\n")
        first = self.assert_ready("E4")
        self.write("unrelated/notes.txt", "changed unrelated content\n")
        second = self.assert_ready("E4")
        self.assertEqual(first, second)

    def test_backend_dependency_change_invalidates_runtime_stage(self):
        self.pass_through("E3")
        backend = [relative for relative in selector.STAGES["E3"]["targets"]
                   if relative.startswith("main/java/")]
        self.assertTrue(backend, "Runtime receipts must bind backend dependencies")
        self.write(backend[0], "changed backend dependency\n")
        result = self.assert_hold("E3")
        self.assertEqual(result["reason"], "target-hash-stale")

    def test_missing_current_target_does_not_advance(self):
        self.pass_through("E1")
        (self.root / selector.STAGES["E1"]["targets"][0]).unlink()
        self.assert_hold("E1")

    def test_target_hash_cannot_be_omitted(self):
        self.save_receipt("E0")
        receipt = self.make_receipt("E1")
        del receipt["targetHashes"][selector.STAGES["E1"]["targets"][0]]
        self.save_receipt("E1", receipt)
        self.assert_hold("E1")

    def test_evidence_content_change_invalidates_receipt(self):
        self.save_receipt("E0")
        receipt = self.save_receipt("E1")
        evidence = next(iter(receipt["checks"].values()))["evidence"]
        self.write(evidence["path"], "changed evidence\n")
        self.assert_hold("E1")

    def test_missing_evidence_is_not_proof(self):
        receipt = self.save_receipt("E0")
        evidence = next(iter(receipt["checks"].values()))["evidence"]
        (self.root / evidence["path"]).unlink()
        self.assert_hold("E0")

    def test_evidence_path_must_remain_in_declared_evidence_directory(self):
        valid = self.make_receipt("E0")
        valid_evidence = next(iter(valid["checks"].values()))["evidence"]
        content = (self.root / valid_evidence["path"]).read_bytes()
        outside = self.write("outside.txt", content)
        self.write(Path(selector.RECEIPT_DIR) / "outside.txt", content)
        escapes = (
            "outside.txt",
            (Path(selector.RECEIPT_DIR) / "evidence" / ".." /
             "outside.txt").as_posix(),
            "../outside.txt",
            str(outside.resolve()),
            "C:/outside/evidence.txt",
            "C:\\outside\\evidence.txt",
        )
        for escape in escapes:
            with self.subTest(path_form=escapes.index(escape)):
                receipt = copy.deepcopy(valid)
                evidence = next(iter(receipt["checks"].values()))["evidence"]
                evidence["path"] = escape
                evidence["sha256"] = selector.file_hash(outside)
                self.save_receipt("E0", receipt)
                self.assert_hold("E0")

    def test_nul_and_control_evidence_paths_hold_without_exception_or_echo(self):
        marker = "synthetic-path-marker"
        for codepoint in (0, 9, 10, 13, 31, 127):
            with self.subTest(codepoint=codepoint):
                receipt = self.make_receipt("E0")
                evidence = receipt["checks"]["intake-reviewed"]["evidence"]
                evidence["path"] += "-" + marker + chr(codepoint)
                self.save_receipt("E0", receipt)
                result = self.assert_hold("E0")
                self.assertEqual(result["reason"], "unsafe-path")
                self.assertNotIn(marker, json.dumps(result))

    def test_symlink_evidence_cannot_refer_outside_evidence_directory(self):
        receipt = self.make_receipt("E0")
        evidence = next(iter(receipt["checks"].values()))["evidence"]
        path = self.root / evidence["path"]
        content = path.read_bytes()
        path.unlink()
        outside = self.write("outside.txt", content)
        try:
            path.symlink_to(outside)
        except (OSError, NotImplementedError):
            self.skipTest("Host cannot create test symlinks")
        evidence["sha256"] = selector.file_hash(outside)
        self.save_receipt("E0", receipt)
        self.assert_hold("E0")

    def test_reparse_attribute_on_existing_evidence_ancestor_is_rejected(self):
        self.save_receipt("E0")
        ancestor = self.root / selector.RECEIPT_DIR / "evidence"
        self.assertTrue(ancestor.is_dir())
        original_lstat = Path.lstat

        def lstat_with_reparse(path, *args, **kwargs):
            actual = original_lstat(path, *args, **kwargs)
            if path == ancestor:
                return SimpleNamespace(
                    st_mode=actual.st_mode,
                    st_file_attributes=(getattr(actual, "st_file_attributes", 0) |
                                        stat.FILE_ATTRIBUTE_REPARSE_POINT),
                )
            return actual

        with mock.patch.object(Path, "lstat", new=lstat_with_reparse):
            result = self.assert_hold("E0")
        self.assertEqual(result["reason"], "unsafe-path")
        self.assert_ready("E1")

    def test_custom_viewport_is_not_official_simulator_evidence(self):
        self.pass_through("E3")
        receipt = self.make_receipt("E4")
        for check in receipt["checks"].values():
            check["kind"] = "browser"
        self.save_receipt("E4", receipt)
        self.assert_hold("E4")

    def test_repeated_inspection_changes_no_files(self):
        self.pass_through("E1")

        def snapshot():
            return {p.relative_to(self.root).as_posix():
                    (p.read_bytes(), p.stat().st_mtime_ns)
                    for p in self.root.rglob("*") if p.is_file()}

        before = snapshot()
        first = selector.inspect(self.root)
        second = selector.inspect(self.root)
        self.assertEqual(first, second)
        self.assertEqual(snapshot(), before)

    def test_complete_receipt_set_does_not_grant_mutation_or_runtime_proof(self):
        self.pass_through("E4")
        result = selector.inspect(self.root)
        self.assertEqual(result["status"], "RECEIPTS_COMPLETE")
        self.assertIsNone(result["nextStage"])
        self.assertEqual(result["completionScope"], "receipt-consistency-only")
        self.assertEqual(result["nextAction"], "review-desktop-final-evidence")
        self.assertNotEqual(result.get("observedExecutionOwner"), "desktop")
        self.assert_read_only_contract(result)

    def test_explicit_desktop_observation_still_does_not_grant_mutation(self):
        result = selector.inspect(self.root, desktop_execution=True)
        self.assertEqual(result["observedExecutionOwner"], "desktop")
        self.assertEqual(result["status"], "READY")
        self.assertEqual(result["nextStage"], "E0")
        self.assert_read_only_contract(result)


if __name__ == "__main__":
    unittest.main()
