"""Synthetic delivery/continuity regression fixtures; no user sessions or network."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import checkpoint_doctor as doctor
import codex_work_checkpoint as ck


class ContinuityDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.task = "fixture-task"
        self.state = self.root / "data/agent-handoff/codex-autonomy" / self.task / "state.md"
        self.state.parent.mkdir(parents=True)
        self.source = self.root / "report.txt"
        self.destination = self.root / "Downloads/report.txt"
        self.destination.parent.mkdir()
        self.source.write_text("synthetic report v1", encoding="utf-8")
        self.destination.write_bytes(self.source.read_bytes())
        self.sha = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.contract = {
            "schemaVersion": "awx.task-continuity.v1", "taskId": self.task,
            "revision": 1, "instructionRef": "user:fixture-1", "observedAt": "2026-10-08T11:00:00Z",
            "goal": "Deliver current report", "nonGoals": ["resume stopped management"],
            "taskStatus": "active", "supersedes": None, "remaining": [], "blockers": [],
            "nextAction": "verify delivery", "accessFailures": [], "preferenceEvents": [],
            "deliverables": [{"id": "report", "version": "v1", "source": str(self.source),
                              "sha256": self.sha, "deliveries": [self.file_delivery()]}],
        }

    def file_delivery(self, status="FOUND_VERIFIED"):
        return {"method": "file", "required": True, "destination": str(self.destination),
                "environment": "fixture", "status": status,
                "evidence": {"sha256": self.sha, "version": "v1", "taskRevision": 1,
                             "observedAt": "2026-10-08T11:00:00Z", "environment": "fixture"}}

    def save(self):
        self.state.write_text("goal: fixture\ncontinuity: " + json.dumps(self.contract) + "\n", encoding="utf-8")

    def cli(self, script="checkpoint_doctor.py", *extra):
        args = [sys.executable, "-B", str(SCRIPTS / script)]
        if script == "checkpoint_doctor.py":
            args += ["--root", str(self.root), "--state", str(self.state), "--check-complete", "--environment", "fixture",
                     "--latest-instruction-ref", self.contract["instructionRef"],
                     "--expected-revision", str(self.contract["revision"])]
        args += list(extra)
        if script != "checkpoint_doctor.py":
            args += ["--root", str(self.root)]
        result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
        return result.returncode, json.loads(result.stdout or "{}")

    def check(self):
        return doctor.check_continuity(self.state, latest_ref=self.contract["instructionRef"],
                                       expected_revision=self.contract["revision"],
                                       complete=True, environment="fixture")

    def test_T1_attachment_success_cannot_replace_blocked_downloads(self):
        self.contract["deliverables"][0]["deliveries"][0]["status"] = "BLOCKED"
        self.save()
        code, out = self.cli()
        self.assertEqual(5, code, out)
        self.assertIn("delivery-not-verified", out["errors"])

    def test_T2_found_file_verified_read_only_no_authorship_claim(self):
        self.save()
        before = self.destination.stat().st_mtime_ns
        code, out = self.cli()
        self.assertEqual(0, code, out)
        self.assertEqual("FOUND_VERIFIED", out["deliveries"][0]["status"])
        self.assertNotIn("savedBy", out["deliveries"][0])
        self.assertEqual(before, self.destination.stat().st_mtime_ns)

    def test_T3_new_version_does_not_inherit_v1_proof(self):
        artifact = self.contract["deliverables"][0]
        self.source.write_text("synthetic report v2", encoding="utf-8")
        artifact.update(version="v2", sha256=hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.save()
        self.assertIn("delivery-evidence-stale", self.check()["errors"])

    def test_T4_latest_destination_supersedes_old_obligation(self):
        old = self.contract["deliverables"][0]["deliveries"][0]
        old.update(status="SUPERSEDED", supersededBy="user:fixture-1")
        self.save()
        self.assertTrue(self.check()["allowed"])
        old["supersededBy"] = "user:obsolete"
        self.save()
        self.assertIn("delivery-supersession-unbound", self.check()["errors"])

    def test_T5_paused_cannot_complete_or_silently_resume(self):
        self.contract["taskStatus"] = "paused"
        self.save()
        self.assertIn("task-not-active", self.check()["errors"])
        new = copy.deepcopy(self.contract)
        new.update(revision=2, taskStatus="active", instructionRef="user:resume-new-scope",
                   supersedes="user:fixture-1", goal="Only deliver new report")
        doctor.write_contract(self.state, new, expected_revision=1)
        self.assertEqual("Only deliver new report", doctor.read_contract(self.state)["goal"])
        self.assertIn("delivery-evidence-stale", doctor.check_continuity(
            self.state, latest_ref="user:resume-new-scope", expected_revision=2,
            complete=True, environment="fixture")["errors"])

    def test_T6_failure_stays_action_target_environment_specific(self):
        self.contract["accessFailures"] = [{"action": "read", "target": "fixture:A",
            "environment": "fixture", "observedAt": "2026-10-08T11:00:00Z", "errorCode": "ACCESS_DENIED"}]
        self.save()
        with patch.object(doctor, "file_digest", wraps=doctor.file_digest) as reads:
            self.assertTrue(self.check()["allowed"])
        self.assertEqual(1, len(doctor.read_contract(self.state)["accessFailures"]))
        self.assertNotIn("fixture:A", [str(call.args[0]) for call in reads.call_args_list])

    def test_T7_roundtrip_and_compare_revision_refuse_lost_update(self):
        doctor.write_contract(self.state, self.contract, expected_revision=0)
        self.assertEqual(self.contract, doctor.read_contract(self.state))
        self.assertLessEqual(len(self.state.read_text().splitlines()), 20)
        new = copy.deepcopy(self.contract)
        new["revision"] = 2
        doctor.write_contract(self.state, new, expected_revision=1)
        before = self.state.read_bytes()
        with self.assertRaisesRegex(ValueError, "state-revision-conflict"):
            doctor.write_contract(self.state, new, expected_revision=1)
        self.assertEqual(before, self.state.read_bytes())

    def test_T7_missing_contract_field_and_wrong_latest_instruction_fail(self):
        self.save()
        self.assertIn("latest-instruction-mismatch", doctor.check_continuity(
            self.state, latest_ref="user:new", expected_revision=1)["errors"])
        del self.contract["goal"]
        self.save()
        self.assertIn("continuity-schema-invalid", self.check()["errors"])

    def test_T9_not_run_partial_unknown_are_not_pass(self):
        for status in ("PARTIAL", "NOT_RUN", "UNKNOWN", "READY", "NOT_STARTED"):
            with self.subTest(status=status):
                self.contract["deliverables"][0]["deliveries"][0]["status"] = status
                self.save()
                self.assertIn("delivery-not-verified", self.check()["errors"])
                self.assertEqual(status, doctor.read_contract(self.state)["deliverables"][0]["deliveries"][0]["status"])

    def test_T10_external_backend_not_implemented_or_called(self):
        self.save()
        before = self.state.read_bytes()
        with patch("socket.socket", side_effect=AssertionError("no network")):
            self.assertTrue(self.check()["allowed"])
        self.assertEqual(before, self.state.read_bytes())
        self.contract["externalBackend"] = "unapproved"
        self.save()
        self.assertIn("continuity-schema-invalid", self.check()["errors"])

    def test_T11_unknown_environment_path_and_actual_hash_fail(self):
        for key, value, reason in (("environment", "other-host", "delivery-environment-unverified"),
                                   ("destination", "unknown", "delivery-path-invalid")):
            original = self.contract["deliverables"][0]["deliveries"][0][key]
            self.contract["deliverables"][0]["deliveries"][0][key] = value
            self.save()
            self.assertIn(reason, self.check()["errors"])
            self.contract["deliverables"][0]["deliveries"][0][key] = original
        self.destination.write_text("wrong bytes")
        self.save()
        self.assertIn("delivery-content-mismatch", self.check()["errors"])

    def test_attachment_needs_separate_current_tool_receipt(self):
        artifact = self.contract["deliverables"][0]
        attachment = {"method": "attachment", "required": True, "destination": "chat:fixture",
                      "environment": "fixture", "status": "VERIFIED", "evidence": {}}
        artifact["deliveries"].append(attachment)
        self.save()
        self.assertFalse(self.check()["allowed"])
        receipt = self.root / "attachment-receipt.json"
        evidence = dict(self.file_delivery()["evidence"], artifactId="report", destination="chat:fixture",
                        method="attachment", status="delivered", sourceRef="tool:synthetic-fixture")
        receipt.write_text(json.dumps(evidence))
        attachment["evidence"] = dict(evidence, receipt=str(receipt))
        self.save()
        self.assertTrue(self.check()["allowed"])
        evidence["sha256"] = "0" * 64
        receipt.write_text(json.dumps(evidence))
        self.assertIn("attachment-receipt-unbound", self.check()["errors"])

    def test_completion_gate_reads_task_state_not_only_success_prose(self):
        self.contract["deliverables"][0]["deliveries"][0]["status"] = "BLOCKED"
        self.save()
        code, out = self.cli("demo1_goal_switch_barrier.py", "reject-complete", "--task", self.task,
            "--latest-instruction-ref", "user:fixture-1", "--expected-revision", "1",
            "--environment", "fixture", "--text", "Implemented guard; 20/20 tests pass, exit 0")
        self.assertEqual(5, code, out)
        self.assertEqual("continuity-incomplete", out["reason"])

    def test_cleanup_refuses_receipt_but_keeps_source_verification(self):
        self.contract["deliverables"][0]["deliveries"][0]["status"] = "BLOCKED"
        self.save()
        run = self.state.parent / "cycle"
        run.mkdir()
        request = {"schemaVersion": "awx.completed-task-cleanup.v1", "taskId": self.task,
                   "postimages": [{"path": "report.txt", "sha256": self.sha}],
                   "instructionRef": "user:fixture-1", "taskRevision": 1, "environment": "fixture"}
        (run / "task-cleanup-request.json").write_text(json.dumps(request))
        state = {"status": "verified", "postimages": {"report.txt": self.sha}}
        with patch.object(ck.subprocess, "run", side_effect=AssertionError("must not invoke cleanup")):
            ck.completion_cleanup(self.root, run, {"decision": {"goalId": self.task}}, state)
        self.assertEqual("verified", state["status"])
        self.assertNotEqual("completed", state.get("taskStatus"))
        self.assertEqual("continuity-incomplete", state["completionCleanup"]["reason"])

    def test_legacy_readable_doctor_keeps_exit_zero(self):
        run = self.root / "legacy"
        run.mkdir()
        (run / "checkpoint.json").write_text(json.dumps({"status": "verified"}))
        result = subprocess.run([sys.executable, "-B", str(SCRIPTS / "checkpoint_doctor.py"),
            "--root", str(self.root), "--run", str(run)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode)

    def test_known_read_denial_not_retried_by_completion_check(self):
        self.contract["accessFailures"] = [{"action": "read", "target": str(self.destination),
            "environment": "fixture", "observedAt": "2026-10-08T11:00:00Z", "errorCode": "ACCESS_DENIED"}]
        self.save()
        with patch.object(doctor, "file_digest", wraps=doctor.file_digest) as reads:
            out = self.check()
        self.assertIn("delivery-access-failure-recorded", out["errors"])
        self.assertNotIn(str(self.destination), [str(call.args[0]) for call in reads.call_args_list])

    def event(self, task, destination="downloads", source="user:choice", actor=True,
              intent="explicit-user-choice", action="deliver", outcome="chosen"):
        return {"eventId": "event-" + task, "taskId": task, "sourceRef": source,
                "actorVerified": actor, "intentEvidence": intent, "actionCategory": action,
                "artifactType": "report", "destinationClass": destination,
                "scope": "report/local/fixture", "outcome": outcome,
                "observedAt": "2026-10-08T11:00:00Z", "supersedes": None}

    def preference(self, events, **kwargs):
        return doctor.preference_default(events, scope="report/local/fixture", artifact_type="report", **kwargs)

    def test_P1_P2_P5_retries_quotes_agent_actions_never_add_support(self):
        retry = self.event("one")
        events = [dict(retry, eventId=str(i)) for i in range(5)]
        events += [self.event("quoted", source="tool:quote"), self.event("agent", actor=False),
                   self.event("silent", intent="inferred", outcome="success")]
        out = self.preference(events)
        self.assertEqual(1, out["support"])
        self.assertIsNone(out["default"])

    def test_P3_independent_same_context_only_shadow_candidate(self):
        events = [self.event(str(i)) for i in range(3)]
        events += [dict(self.event("other"), scope="drive/original")]
        out = self.preference(events)
        self.assertEqual((3, 3, "downloads"), (out["support"], out["opportunities"], out["default"]))
        self.assertEqual("shadow-candidate", out["status"])
        self.assertFalse(out["actionAuthorized"])

    def test_P4_P10_latest_explicit_choice_requires_no_relearning(self):
        out = self.preference([], explicit_destination="downloads")
        self.assertEqual("downloads", out["default"])
        events = [self.event(str(i)) for i in range(5)]
        self.assertEqual("attachment", self.preference(events, explicit_destination="attachment")["default"])
        self.assertIsNone(self.preference(events, cancelled=True)["default"])

    def test_P6_risky_repetition_never_promoted(self):
        for action in ("delete", "external-send", "pay", "credential", "security", "restart"):
            with self.subTest(action=action):
                out = self.preference([self.event(str(i), action=action) for i in range(100)])
                self.assertIsNone(out["default"])
                self.assertFalse(out["actionAuthorized"])

    def test_P7_P12_candidate_does_not_satisfy_delivery_obligation(self):
        self.assertEqual("downloads", self.preference([self.event(str(i)) for i in range(3)])["default"])
        self.contract["deliverables"][0]["deliveries"][0]["status"] = "NOT_STARTED"
        self.save()
        self.assertFalse(self.check()["allowed"])

    def test_P8_P9_conflict_correction_or_missing_intent_suspends_candidate(self):
        events = [self.event(str(i)) for i in range(5)]
        events.append(self.event("correction", outcome="rejected"))
        self.assertIsNone(self.preference(events)["default"])
        self.assertEqual(6, self.preference(events)["opportunities"])
        events = [dict(self.event(str(i)), scope="") for i in range(5)]
        self.assertIsNone(self.preference(events)["default"])

    def test_P11_raw_authority_or_transcript_cannot_be_saved(self):
        self.contract["preferenceEvents"] = [dict(self.event("1"), rawTranscript="synthetic bulk conversation")]
        self.save()
        self.assertIn("continuity-schema-invalid", self.check()["errors"])

    def test_T8_structured_synthetic_instruction_golden_slots(self):
        # This intentionally supports tagged synthetic instructions only, not arbitrary natural language.
        source = "goal: Deliver report\ndestination: attachment\nstatus: paused\nstop: previous management"
        self.assertEqual({"goal": "Deliver report", "destination": "attachment", "status": "paused",
                          "stop": "previous management"}, doctor.instruction_slots(source, source_type="user"))
        self.assertEqual({}, doctor.instruction_slots(source, source_type="quoted"))

    def test_task_aware_cleanup_missing_or_malformed_state_never_bypasses(self):
        self.save()
        run = self.state.parent / "cycle-missing"
        run.mkdir()
        request = {"schemaVersion": "awx.completed-task-cleanup.v1", "taskId": self.task,
                   "postimages": [{"path": "report.txt", "sha256": self.sha}],
                   "instructionRef": "user:fixture-1", "taskRevision": 1, "environment": "fixture"}
        (run / "task-cleanup-request.json").write_text(json.dumps(request))
        for content in (None, "goal: legacy only\n", "continuity:broken-json\n"):
            if content is None:
                self.state.unlink()
            else:
                self.state.write_text(content)
            state = {"status": "verified", "postimages": {"report.txt": self.sha}}
            with patch.object(ck.subprocess, "run", side_effect=AssertionError("must not finalize")):
                ck.completion_cleanup(self.root, run, {"decision": {"goalId": self.task}}, state)
            self.assertEqual("verified", state["status"])
            self.assertEqual("continuity-incomplete", state["completionCleanup"]["reason"])

    def test_same_instruction_cannot_drop_or_cancel_required_delivery(self):
        self.save()
        for mode in ("drop", "optional", "cancel", "destination"):
            new = copy.deepcopy(self.contract)
            new["revision"] = 2
            if mode == "drop":
                new["deliverables"] = []
            elif mode == "optional":
                new["deliverables"][0]["deliveries"][0]["required"] = False
            elif mode == "cancel":
                new["deliverables"][0]["deliveries"][0].update(status="CANCELLED", supersededBy="user:fixture-1")
            else:
                new["deliverables"][0]["deliveries"][0]["destination"] = str(self.root / "elsewhere")
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, "instruction-change-required"):
                doctor.write_contract(self.state, new, expected_revision=1)
            self.assertEqual(self.contract, doctor.read_contract(self.state))

    def test_protected_session_git_and_linked_paths_never_read(self):
        for directory in (".codex", ".git", ".secrets", ".auth"):
            target = self.root / directory / "synthetic.txt"
            target.parent.mkdir(exist_ok=True)
            target.write_text("synthetic only")
            with self.subTest(directory=directory), self.assertRaisesRegex(ValueError, "delivery-path-protected"):
                doctor.file_digest(target)

    def test_over_capacity_write_preserves_legacy_bytes(self):
        self.state.write_text("summary\n" * 20)
        before = self.state.read_bytes()
        with self.assertRaisesRegex(ValueError, "capacity-exceeded"):
            doctor.write_contract(self.state, self.contract, expected_revision=0)
        self.assertEqual(before, self.state.read_bytes())

    def test_finalization_lock_and_postimage_check_reject_concurrent_state_change(self):
        self.save()
        run = self.state.parent / "cycle-race"
        run.mkdir()
        request = {"schemaVersion": "awx.completed-task-cleanup.v1", "taskId": self.task,
                   "postimages": [{"path": "report.txt", "sha256": self.sha}],
                   "instructionRef": "user:fixture-1", "taskRevision": 1, "environment": "fixture"}
        (run / "task-cleanup-request.json").write_text(json.dumps(request))
        new = copy.deepcopy(self.contract)
        new["revision"] = 2
        def racing_helper(*args, **kwargs):
            with self.assertRaisesRegex(ValueError, "state-writer-conflict"):
                doctor.write_contract(self.state, new, expected_revision=1)
            # An uncooperative writer bypassing CAS must still invalidate receipt acceptance.
            self.state.write_text("continuity: " + json.dumps(new))
            return SimpleNamespace(stdout=b"{}")
        state = {"status": "verified", "postimages": {"report.txt": self.sha}}
        with patch.object(ck.subprocess, "run", side_effect=racing_helper):
            ck.completion_cleanup(self.root, run, {"decision": {"goalId": self.task}}, state)
        self.assertEqual("cleanup-continuity-state-changed", state["completionCleanup"]["reason"])
        self.assertEqual("verified", state["status"])
        self.assertNotEqual("completed", state.get("taskStatus"))
        self.assertFalse(self.state.with_name("state.md.write-lock").exists())


class RequestContractTest(unittest.TestCase):
    def contract(self):
        return {
            "schemaVersion": "awx.request-contract.v1", "taskId": "request-fixture",
            "revision": 1, "instructionRef": "user:synthetic", "goal": "Filter a synthetic list",
            "knowledge": [{"id": "contract", "kind": "fact", "summary": "Exact synthetic contract",
                           "sourceRef": "fixture:contract-v1"}],
            "stages": [{"id": "filter", "dependsOn": [], "requiredKnowledge": ["contract"],
                        "inputs": [{"name": "items", "type": "string[]", "knowledgeRef": "contract"}],
                        "outputs": [{"name": "filtered", "type": "string[]", "knowledgeRef": "contract"}],
                        "api": {"applicable": False, "reason": "Local pure function", "knowledgeRef": "contract"},
                        "errors": [], "successTests": [{"id": "dedup", "commandId": "focused",
                            "argvSha256": "0" * 64, "expectedSuites": ["SyntheticSuite"],
                            "expectation": "Repeated input appears once", "knowledgeRef": "contract"}],
                        "steps": ["Write a focused failing fixture", "Patch the existing function"],
                        "sourceFiles": ["scripts/example.py"], "testFiles": ["scripts/test_example.py"]}]
        }

    def check(self, doc=None, **kwargs):
        return doctor.check_request_contract(doc or self.contract(), **kwargs)

    def test_ready_contract_hash_is_canonical_and_does_not_claim_fact_truth(self):
        doc = self.contract()
        result = self.check(doc, latest_ref="user:synthetic", expected_revision=1)
        self.assertEqual("READY", result["status"])
        expected = hashlib.sha256(json.dumps(doc, sort_keys=True, separators=(",", ":"),
                                            ensure_ascii=False, allow_nan=False).encode()).hexdigest()
        self.assertEqual(expected, result["contractHash"])
        self.assertEqual([], result["errors"])
        self.assertNotIn("actionAuthorized", result)
        self.assertEqual([{"id": "filter", "status": "READY", "blockers": []}], result["stages"])

    def test_unknown_and_assumption_hold_only_affected_dependency_chain(self):
        for kind in ("unknown", "assumption"):
            doc = self.contract()
            doc["knowledge"].append({"id": "gap", "kind": kind, "summary": "Unconfirmed type", "sourceRef": None})
            base = doc["stages"][0]
            base["inputs"][0]["knowledgeRef"] = "gap"
            dependent = copy.deepcopy(base)
            dependent.update(id="dependent", dependsOn=["filter"])
            dependent["inputs"][0]["knowledgeRef"] = "contract"
            independent = copy.deepcopy(dependent)
            independent.update(id="independent", dependsOn=[])
            doc["stages"].extend([dependent, independent])
            result = self.check(doc)
            self.assertEqual("HOLD", result["status"])
            self.assertEqual(["HOLD", "HOLD", "READY"], [s["status"] for s in result["stages"]])
            self.assertIn("dependency-held:filter", result["stages"][1]["blockers"])

    def test_missing_or_empty_contract_slots_hold_without_inventing_api(self):
        for field in ("inputs", "outputs", "api", "successTests", "steps", "sourceFiles", "testFiles", "requiredKnowledge"):
            for missing in (False, True):
                doc = self.contract()
                if missing:
                    doc["stages"][0].pop(field)
                else:
                    doc["stages"][0][field] = None if field == "api" else []
                self.assertEqual("HOLD", self.check(doc)["status"], (field, missing))
        doc = self.contract()
        doc["stages"][0]["api"] = {"method": "POST", "path": "/api/filter", "knowledgeRef": "contract"}
        self.assertEqual("HOLD", self.check(doc)["status"])
        doc["stages"][0]["errors"] = [{"status": 400, "condition": "items missing", "knowledgeRef": "contract"}]
        self.assertEqual("READY", self.check(doc)["status"])

    def test_invalid_shape_refs_ids_cycles_and_extra_fields_reject(self):
        mutations = [lambda d: d.update(rawReasoning="Private reasoning must never be stored"),
                     lambda d: d["knowledge"].append(copy.deepcopy(d["knowledge"][0])),
                     lambda d: d["stages"][0]["inputs"][0].update(knowledgeRef="absent"),
                     lambda d: d["stages"][0].update(dependsOn=["filter"]),
                     lambda d: d["stages"][0].update(steps="not a list"),
                     lambda d: d["knowledge"][0].update(sourceRef=None),
                     lambda d: d["stages"].append(copy.deepcopy(d["stages"][0]))]
        for mutate in mutations:
            doc = self.contract()
            mutate(doc)
            self.assertEqual("REJECTED", self.check(doc)["status"])

    def test_revision_and_instruction_mismatch_reject(self):
        self.assertEqual("REJECTED", self.check(latest_ref="user:other")["status"])
        self.assertEqual("REJECTED", self.check(expected_revision=2)["status"])

    def test_planned_execution_missing_empty_holds_and_malformed_rejects(self):
        for field in ("argvSha256", "expectedSuites"):
            doc = self.contract()
            doc["stages"][0]["successTests"][0].pop(field)
            self.assertEqual("HOLD", self.check(doc)["status"], field)
            doc = self.contract()
            doc["stages"][0]["successTests"][0][field] = "" if field == "argvSha256" else []
            self.assertEqual("HOLD", self.check(doc)["status"], field)
        for value in ("not-a-hash", "A" * 64, 123, ["0" * 64]):
            doc = self.contract()
            doc["stages"][0]["successTests"][0]["argvSha256"] = value
            self.assertEqual("REJECTED", self.check(doc)["status"])
        for value in ("SyntheticSuite", [""], ["../suite"], ["same", "same"], [False]):
            doc = self.contract()
            doc["stages"][0]["successTests"][0]["expectedSuites"] = value
            self.assertEqual("REJECTED", self.check(doc)["status"])

    def test_importlib_without_scripts_import_path_uses_existing_sibling_scanner(self):
        code = (
            "import importlib.util,json,sys; "
            "spec=importlib.util.spec_from_file_location('standalone_doctor',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "print(json.dumps(module.check_request_contract(json.loads(sys.stdin.read()))))"
        )
        with tempfile.TemporaryDirectory() as folder:
            result = subprocess.run([sys.executable, "-B", "-c", code,
                str(SCRIPTS / "checkpoint_doctor.py")], input=json.dumps(self.contract()),
                cwd=folder, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("READY", json.loads(result.stdout)["status"])

    def test_canonical_paths_and_existing_link_boundaries(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.assertEqual("READY", self.check(root=root)["status"])
            for path in ("../escape.py", "/absolute.py", "C:/elsewhere.py", "scripts\\example.py",
                         "scripts//example.py", ".secrets/value.txt", ".git/config", "sessions/raw.jsonl"):
                doc = self.contract()
                doc["stages"][0]["sourceFiles"] = [path]
                self.assertEqual("REJECTED", self.check(doc, root=root)["status"], path)
            outside = root.parent / (root.name + "-outside")
            try:
                (root / "linked").symlink_to(outside, target_is_directory=True)
            except OSError:
                return
            doc = self.contract()
            doc["stages"][0]["sourceFiles"] = ["linked/example.py"]
            self.assertEqual("REJECTED", self.check(doc, root=root)["status"])

    def test_bounded_secret_and_nonfinite_inputs_fail_closed(self):
        doc = self.contract()
        with patch.object(ck, "secret_free", side_effect=ValueError("secret-pattern")) as scanner:
            self.assertEqual("REJECTED", self.check(doc)["status"])
            scanner.assert_called_once()
        doc = self.contract()
        doc["revision"] = float("nan")
        self.assertEqual("REJECTED", self.check(doc)["status"])
        doc = self.contract()
        doc["knowledge"] *= 101
        self.assertEqual("REJECTED", self.check(doc)["status"])

    def test_cli_duplicate_json_field_is_ambiguous_and_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "request.json"
            body = json.dumps(self.contract()).replace('"revision": 1', '"revision": 1, "revision": 1')
            source.write_text(body, encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(SCRIPTS / "checkpoint_doctor.py"),
                "--request-contract", str(source), "--root", folder], capture_output=True, text=True)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertEqual("REJECTED", json.loads(result.stdout)["status"])
            self.assertEqual(body, source.read_text(encoding="utf-8"))

    def test_cli_input_link_is_rejected_before_target_read(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "request.json"
            source.write_text(json.dumps(self.contract()), encoding="utf-8")
            linked = Path(folder) / "linked.json"
            try:
                linked.symlink_to(source)
            except OSError:
                self.skipTest("Host does not permit creating a synthetic file link")
            result = subprocess.run([sys.executable, "-B", str(SCRIPTS / "checkpoint_doctor.py"),
                "--request-contract", str(linked), "--root", folder], capture_output=True, text=True)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertEqual("REJECTED", json.loads(result.stdout)["status"])

    def test_cli_ready_hold_rejected_are_read_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "request.json"
            for status, code in (("READY", 0), ("HOLD", 5), ("REJECTED", 2)):
                doc = self.contract()
                if status == "HOLD":
                    doc["stages"][0]["api"] = None
                elif status == "REJECTED":
                    doc["stages"][0]["extra"] = "reject"
                source.write_text(json.dumps(doc), encoding="utf-8")
                before = source.read_bytes()
                result = subprocess.run([sys.executable, "-B", str(SCRIPTS / "checkpoint_doctor.py"),
                    "--request-contract", str(source), "--root", str(root),
                    "--latest-instruction-ref", "user:synthetic", "--expected-revision", "1"],
                    capture_output=True, text=True)
                self.assertEqual(code, result.returncode, result.stderr)
                self.assertEqual(status, json.loads(result.stdout)["status"])
                self.assertEqual(before, source.read_bytes())
                self.assertEqual(["request.json"], sorted(p.name for p in root.iterdir()))


if __name__ == "__main__":
    unittest.main()
