#!/usr/bin/env python3
"""test_session_close_gate.py — session_close_gate.py 테스트.
python -B scripts/test_session_close_gate.py

픽스처로 임시 디렉터리에 lease/journal/report/checkpoint 형태를 만든다.
실제 git/node/서버는 호출하지 않는다 (changed/runner 주입).
"""
from __future__ import annotations

import json
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timedelta, timezone
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import session_close_gate as g  # noqa: E402


def _write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def _lease(tmp: Path, topic: str, task: str, targets: list[str],
           expires: str = "2999-01-01T00:00:00+00:00") -> dict:
    data = {
        "topic": topic, "taskIdHash": g._sha256_text(task), "ownerId": "other-agent",
        "expiresAtUtc": expires, "targetPaths": targets,
    }
    _write(tmp / "__patch_drop__" / "source-edit-locks" / f"{topic}.lock" / "lease.json",
           json.dumps(data))
    return data


def _journal(tmp: Path, task: str, status: str, scope: list[str]) -> Path:
    j = {"taskId": task, "agent": "devin", "status": status, "plannedScope": scope, "events": []}
    return _write(tmp / "data/agent-handoff/codex-autonomy" / task / "journal.json",
                  json.dumps(j))


def _report(tmp: Path, task: str, body: str) -> Path:
    return _write(tmp / "data/agent-handoff/codex-autonomy" / task / "REPORT.md", body)


REPORT_OK = """# REPORT
## Acceptance
- A1: PASS
- A2: NOT_RUN (서버 없음)
"""


class GateFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self._saved = {k: getattr(g, k) for k in
                       ("LOCKS", "JOURNAL_ROOT", "HANDOFF", "HOT_FILES")}
        g.LOCKS = self.root / "__patch_drop__" / "source-edit-locks"
        g.JOURNAL_ROOT = self.root / "data" / "agent-handoff" / "codex-autonomy"
        g.HANDOFF = self.root / "data" / "agent-handoff"
        g.HOT_FILES = _write(self.root / "configs" / "hot-files.yaml",
                             'hotFiles:\n  - path: "main/java/x/ChatWorkflow.java"\n')
        self.addCleanup(mock.patch.stopall)
        self.process = mock.patch.object(g.subprocess, "run", side_effect=AssertionError("live subprocess forbidden")).start()
        self.server = mock.patch.object(g, "_server_up", return_value=True).start()

    def tearDown(self):
        for k, v in self._saved.items():
            setattr(g, k, v)
        self._tmp.cleanup()


class CheckStartTest(GateFixture):
    def test_go_when_free(self):
        task = "demo-task-aaaaaaaa"
        _journal(self.root, task, "in_progress", ["scripts/x.py"])
        _lease(self.root, "demo-task", task, ["scripts/x.py"])
        manifest = _write(self.root / "targets.json",
                          '{"targets":[{"path":"scripts/x.py","sha256":null}]}')
        verdict, code, _ = g.check_start(manifest, task)
        self.assertEqual((verdict, code), ("GO", 0))

    def test_hold_on_hot_overlap(self):
        task = "demo-task-bbbbbbbb"
        _journal(self.root, task, "in_progress", ["main/java/x/ChatWorkflow.java"])
        _lease(self.root, "foreign-lease", "other-task-11111111",
               ["main/java/x/ChatWorkflow.java"])
        _lease(self.root, "demo-task", task, ["main/java/x/ChatWorkflow.java"])
        manifest = _write(self.root / "targets.json",
                          '{"targets":[{"path":"main/java/x/ChatWorkflow.java","sha256":null}]}')
        verdict, code, detail = g.check_start(manifest, task)
        self.assertEqual(code, 1)
        self.assertTrue(verdict.startswith("HOLD:hot-file-lease-overlap"), verdict)
        self.assertIn("foreign-lease", detail["blockers"][0])


class CloseTest(GateFixture):
    def _closed_setup(self, task: str):
        _journal(self.root, task, "closed", ["scripts/x.py"])
        _report(self.root, task, REPORT_OK)
        _write(self.root / "data/agent-handoff/codex-autonomy" / task
               / "tests" / "test_x.exit0.log", "ok")

    def test_close_pass(self):
        task = "demo-task-cccccccc"
        self._closed_setup(task)
        verdict, code, detail = g.close_gate(task, root=self.root, changed=set())
        self.assertEqual((verdict, code), ("PASS", 0), detail)
        self.assertEqual(detail["closureStatus"], "SAFE_CLOSED")
        self.assertEqual(detail["acceptanceStatus"], "HAS_NOT_RUN")
        self.assertEqual(detail["evidenceStatus"], "REFERENCE_ONLY")
        self.assertEqual(detail["goalCompletion"], "NOT_PROVEN")
        self.server.assert_not_called()
        self.process.assert_not_called()

    def test_close_blocked_foreign_lease_modified(self):
        task = "demo-task-dddddddd"
        _journal(self.root, task, "closed",
                 ["scripts/x.py", "main/java/x/chatapicontroller.java"])
        _report(self.root, task, REPORT_OK)
        _write(self.root / "data/agent-handoff/codex-autonomy" / task
               / "tests" / "test_x.exit0.log", "ok")
        _lease(self.root, "foreign-lease", "other-task-22222222",
               ["main/java/x/chatapicontroller.java"])
        ck = {"status": "sealed",
              "postimages": {"main/java/x/chatapicontroller.java": "0" * 64}}
        _write(self.root / "data/agent-handoff/codex-autonomy" / task
               / "cycle-01" / "checkpoint.json", json.dumps(ck))
        changed = {"main/java/x/chatapicontroller.java"}
        verdict, code, detail = g.close_gate(task, root=self.root, changed=changed)
        self.assertEqual(code, 1)
        self.assertEqual(verdict, "BLOCKED:4_foreign_lease", detail)

    def test_close_blocked_model_mismatch(self):
        task = "demo-task-eeeeeeee"
        self._closed_setup(task)
        payload = {"sends": 2, "results": [
            {"id": "C1", "verdict": "PASS", "modelMatched": False,
             "requestedModel": "a", "observedModel": "b"}]}
        runner = lambda cmd: {"rc": 1, "stdout": json.dumps(payload), "stderr": ""}
        verdict, code, detail = g.close_gate(
            task, root=self.root, golden=["C1"], changed=set(), runner=runner,
            base="http://fixture.invalid")
        self.assertEqual(code, 1)
        self.assertEqual(detail["checks"]["3_golden"]["reason"], "model-mismatch")

    def test_close_golden_server_down_not_run(self):
        task = "demo-task-ffffffff"
        self._closed_setup(task)
        self.server.return_value = False
        runner = mock.Mock(side_effect=AssertionError("golden forbidden"))
        verdict, code, detail = g.close_gate(
            task, root=self.root, golden=["C1", "C2"], changed=set(),
            base="http://fixture.invalid", runner=runner)
        self.assertEqual(code, 2, detail)
        self.assertTrue(verdict.startswith("NOT_RUN:server-unreachable"), verdict)
        runner.assert_not_called()

    def test_close_golden_pass_with_runner(self):
        task = "demo-task-12121212"
        self._closed_setup(task)
        payload = {"sends": 2, "results": [
            {"id": "C1", "verdict": "PASS", "modelMatched": True},
            {"id": "C2", "verdict": "PASS", "modelMatched": True}]}
        # 서버 업으로 보이게 _server_up 패치
        orig = g._server_up
        g._server_up = lambda base, timeout=5.0: True
        try:
            runner = lambda cmd: {"rc": 0, "stdout": json.dumps(payload), "stderr": ""}
            verdict, code, detail = g.close_gate(
                task, root=self.root, golden=["C1", "C2"], changed=set(), runner=runner)
        finally:
            g._server_up = orig
        self.assertEqual((verdict, code), ("PASS", 0), detail)
        self.assertEqual(detail["checks"]["3_golden"]["status"], "PASS")

    def test_closed_partial_blocked_hold_remain_safe(self):
        for result in ("partial", "blocked"):
            task = "held-" + result
            self._closed_setup(task)
            report = _report(self.root, task, "## Acceptance\n- A1: HOLD\n")
            journal = {"status": "closed", "result": result, "plannedScope": [], "events": []}
            verdict, code, detail = g.close_gate(task, root=self.root, changed=set(), journal=journal)
            self.assertEqual((verdict, code), ("PASS", 0))
            self.assertEqual(detail["closureStatus"], "SAFE_CLOSED")
            self.assertEqual(detail["journalResult"], result)
            self.assertEqual(detail["acceptanceStatus"], "HAS_HOLD")
            self.assertEqual(detail["goalCompletion"], "NOT_PROVEN")
            self.assertTrue(report.is_file())

    def test_open_journal_or_owned_lease_blocks_closure(self):
        for mode in ("journal", "lease"):
            task = "open-" + mode
            self._closed_setup(task)
            if mode == "journal":
                _journal(self.root, task, "in_progress", [])
            else:
                _lease(self.root, "owned", task, [])
            _, code, detail = g.close_gate(task, root=self.root, changed=set())
            self.assertEqual(code, 1)
            self.assertEqual(detail["closureStatus"], "BLOCKED")

    def test_foreign_dirty_is_not_our_write(self):
        task = "foreign-dirty"
        self._closed_setup(task)
        _lease(self.root, "foreign", "other", ["scripts/other.py"])
        verdict, code, detail = g.close_gate(task, root=self.root, changed={"scripts/other.py"})
        self.assertEqual((verdict, code), ("PASS", 0), detail)
        self.assertEqual(detail["closureStatus"], "SAFE_CLOSED")

    def test_golden_empty_missing_case_and_nonzero_are_not_pass(self):
        task = "golden-invalid"
        self._closed_setup(task)
        row = {"id": "C1", "verdict": "PASS", "modelMatched": True}
        for rc, rows in ((0, []), (0, [row]), (7, [row, dict(row, id="C2")])):
            with self.subTest(rc=rc, rows=rows):
                runner = lambda cmd: {"rc": rc, "stdout": json.dumps({"results": rows}), "stderr": ""}
                _, code, detail = g.close_gate(task, root=self.root, golden=["C1", "C2"],
                                                changed=set(), runner=runner)
                self.assertEqual(code, 1, detail)

    def test_no_golden_requested_means_zero_calls(self):
        task = "no-golden"
        self._closed_setup(task)
        runner = mock.Mock(side_effect=AssertionError("golden forbidden"))
        g.close_gate(task, root=self.root, changed=set(), runner=runner)
        runner.assert_not_called()
        self.server.assert_not_called()


class AcceptanceTest(GateFixture):
    def parse(self, body, required=None):
        return g._acceptance_status(_report(self.root, "acceptance", body), required)

    def test_table_bullet_plain_equivalence(self):
        for row in ("- A1: PASS", "A1: PASS", "| A1 | condition | PASS |"):
            self.assertEqual(self.parse("## Acceptance\n" + row), (["A1"], []))

    def test_table_condition_status_words_do_not_override_status_cell(self):
        self.assertEqual(self.parse("## Acceptance\n| A1 | previous PASS is stale | FAIL |"),
                         (["A1"], ["A1(FAIL)"]))
        self.assertEqual(self.parse("## Acceptance\n| A1 | previous FAIL repaired | PASS |"), (["A1"], []))
        self.assertEqual(self.parse("## Acceptance\n| A1 | condition | NOT_RUN | server unavailable |"), (["A1"], []))

    def test_unbounded_lines_and_heading_boundary(self):
        body = "A1 FAIL outside\n## Acceptance\n- A1 PASS\n" + "context\n" * 70
        body += "### Details\n| A2 | PASS |\n## Other\n- A3 FAIL\n"
        self.assertEqual(self.parse(body), (["A1", "A2"], []))

    def test_duplicate_worse_status_and_required_missing(self):
        wanted, blocked = self.parse("## Acceptance\n- A1 PASS\n| A1 | FAIL |\n", ["A1", "A2"])
        self.assertEqual(wanted, ["A1", "A2"])
        self.assertEqual(blocked, ["A1(FAIL)", "A2(missing)"])

    def test_same_id_not_run_cannot_disappear_behind_pass(self):
        task = "duplicate-not-run"
        _journal(self.root, task, "closed", [])
        _report(self.root, task, "## Acceptance\n- A1 PASS\n- A1 NOT_RUN pending proof\n")
        _write(g.JOURNAL_ROOT / task / "test.log", "ok")
        _, _, detail = g.close_gate(task, root=self.root, changed=set())
        self.assertEqual(detail["acceptanceStatus"], "HAS_NOT_RUN")

    def test_missing_heading_items_and_oversize(self):
        for body in ("Acceptance mentioned in prose\nA1 PASS", "## Acceptance\nno items",
                     "## Acceptance\n" + "x" * (4 * 1024 * 1024)):
            self.assertTrue(self.parse(body)[1])

    def test_reasonless_not_run_preserves_legacy_block(self):
        self.assertEqual(self.parse("## Acceptance\n- A1 NOT_RUN\n")[1], ["A1(NOT_RUN-reason-missing)"])


class ReceiptTest(GateFixture):
    _closed_setup = CloseTest._closed_setup

    def receipt(self, task):
        base = g.JOURNAL_ROOT / task / "tests" / "scoped"
        source = _write(self.root / "scripts/x.py", "fixture source\n")
        xml = _write(base / "TEST-Fixture.xml", '<testsuite name="Fixture" tests="2" failures="0" errors="0" skipped="0"><testcase/><testcase/></testsuite>')
        oracle = _write(self.root / "oracle.txt", "independent oracle\n")
        hashes = {k: hashlib.sha256(k.encode()).hexdigest() for k in ("candidate", "fixture", "oracle", "root", "toolchain")}
        hashes["oracle"] = hashlib.sha256(oracle.read_bytes()).hexdigest()
        now = datetime.now(timezone.utc)
        command = ["python", "-B", "-m", "unittest", "fixture"]
        counts = dict(tests=2, failures=0, errors=0, skipped=0)
        run = dict(runId="fixture-run", scope="scoped", cwd=str(self.root), commandArgv=command,
                   commandSha256=hashlib.sha256(json.dumps(command).encode()).hexdigest(), status="passed",
                   exitCode=0, verificationExitCode=0, elapsedMs=1000, failures=[], expectedSuites=["Fixture"],
                   startedAt=(now-timedelta(seconds=2)).isoformat(), endedAt=(now-timedelta(seconds=1)).isoformat(),
                   totals=counts, resultFiles=[dict(suite="Fixture", path=xml.name, counts=counts,
                                                   sha256=hashlib.sha256(xml.read_bytes()).hexdigest())],
                   sourceIdentity=[dict(path=str(source), kind="file", sha256=hashlib.sha256(source.read_bytes()).hexdigest())])
        contract = dict(schema="agent_code_evidence_contract.v1", hashes=hashes, runId=run["runId"],
                        scope=run["scope"], commandSha256=run["commandSha256"], requiredSuites=["Fixture"], maxAgeSeconds=300,
                        candidateRoot=str(source.parent), oraclePath=str(oracle))
        evidence = dict(hashes=hashes, oracleAfterSha256=hashes["oracle"], staticNewHighCount=0, secretHitCount=0,
                        expectedSignalMatches=True, oracleIndependent=True, sandboxAvailable=True,
                        requiredToolsAvailable=True, evidenceComplete=True, deterministic=True,
                        mutationCounts=dict.fromkeys(("source", "apply", "rollback", "deploy", "provider", "database"), 0))
        self.save_receipt(base, run, contract, evidence)
        self.contract_path = base / "contract.json"
        self.contract_sha256 = hashlib.sha256(self.contract_path.read_bytes()).hexdigest()
        return base, run, contract, evidence, source

    def save_receipt(self, base, run, contract, evidence):
        contract["runSha256"] = hashlib.sha256(json.dumps(run, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        for name, obj in (("run.json", run), ("contract.json", contract), ("evidence.json", dict(run=run, evidence=evidence))):
            _write(base / name, json.dumps(obj))

    def close_with_contract(self, task):
        return g.close_gate(task, root=self.root, changed=set(), evidence_contract=self.contract_path,
                            evidence_contract_sha256=self.contract_sha256)

    def test_fresh_scoped_receipt_is_not_whole_goal_proof(self):
        task = "fresh-receipt"
        self._closed_setup(task)
        self.receipt(task)
        _, _, detail = self.close_with_contract(task)
        self.assertEqual(detail["evidenceStatus"], "VERIFIED", detail)
        self.assertEqual(detail["verifiedScope"], ["scripts/x.py"])
        self.assertEqual(detail["goalCompletion"], "NOT_PROVEN")

    def test_fail_zero_stale_command_unreadable_receipts_not_verified(self):
        for mode in ("failure", "zero", "stale", "command", "unreadable", "results", "runid", "oracle", "malformed"):
            with self.subTest(mode=mode):
                task = "receipt-" + mode
                self._closed_setup(task)
                base, run, contract, evidence, source = self.receipt(task)
                if mode == "failure":
                    run.update(status="failed", exitCode=1, verificationExitCode=1)
                elif mode == "zero":
                    run["totals"] = dict(tests=0, failures=0, errors=0, skipped=0)
                elif mode == "stale":
                    source.write_text("changed source", encoding="utf-8")
                elif mode == "command":
                    run["commandArgv"] = ["python", "different"]
                elif mode == "results":
                    (base / "TEST-Fixture.xml").write_text("changed results", encoding="utf-8")
                elif mode == "runid":
                    run["runId"] = "different"
                elif mode == "oracle":
                    (self.root / "oracle.txt").write_text("changed oracle", encoding="utf-8")
                elif mode == "malformed":
                    run["resultFiles"] = [1]
                self.save_receipt(base, run, contract, evidence)
                self.contract_sha256 = hashlib.sha256(self.contract_path.read_bytes()).hexdigest()
                if mode == "unreadable":
                    (base / "run.json").write_text("invalid JSON", encoding="utf-8")
                _, _, detail = self.close_with_contract(task)
                self.assertNotEqual(detail["evidenceStatus"], "VERIFIED", detail)

    def test_unpinned_and_modified_contract_are_only_references(self):
        task = "unpinned"
        self._closed_setup(task)
        self.receipt(task)
        _, _, detail = g.close_gate(task, root=self.root, changed=set())
        self.assertEqual(detail["evidenceStatus"], "REFERENCE_ONLY")
        self.contract_path.write_text("{}", encoding="utf-8")
        _, _, detail = self.close_with_contract(task)
        self.assertEqual(detail["evidenceStatus"], "REFERENCE_ONLY")

    def test_verify_event_is_only_reference_and_missing_is_missing(self):
        task = "event-reference"
        _journal(self.root, task, "closed", [])
        _report(self.root, task, "## Acceptance\n- A1 PASS")
        journal = dict(status="closed", events=[dict(kind="verify")])
        _, _, detail = g.close_gate(task, root=self.root, journal=journal, changed=set())
        self.assertEqual(detail["evidenceStatus"], "REFERENCE_ONLY")
        journal["events"] = []
        _, _, detail = g.close_gate(task, root=self.root, journal=journal, changed=set())
        self.assertEqual(detail["evidenceStatus"], "MISSING")

    def test_cleanup_promotion_connection_absent(self):
        import inspect
        source = inspect.getsource(g)
        self.assertNotIn("completion_cleanup(", source)
        self.assertNotIn('"stopWork":', source)
        self.assertNotIn('"taskStatus": "completed"', source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
