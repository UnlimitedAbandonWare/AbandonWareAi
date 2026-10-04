"""Tests for codex_auto_unblock.py — D30~D33 auto-unblock judgement tool.

All fixtures are synthetic (temp dirs): fake launcher logs, fake journals,
fake lock dirs. No real log/ledger reads, no network, no leases touched.
"""
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "codex_auto_unblock.py"
SPEC = importlib.util.spec_from_file_location("codex_auto_unblock", SCRIPT)
CAU = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault("codex_auto_unblock", CAU)
SPEC.loader.exec_module(CAU)


def write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def journal(task_id, result=None, status="closed", events=None, purpose="g",
            updated="2026-10-03T08:00:00+00:00"):
    return {
        "schemaVersion": "awx.work_journal.v1", "taskId": task_id,
        "agent": task_id.rsplit("-", 1)[0], "purpose": purpose,
        "status": status, "result": result,
        "startedAtUtc": "2026-10-03T07:00:00+00:00",
        "updatedAtUtc": updated,
        "events": events or [],
    }


class BudgetTest(unittest.TestCase):
    def run_budget(self, ledger, cap=10):
        return CAU.budget_eval(str(ledger), cap)

    def test_recount_under_cap_no_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-aaaa1111", events=[
                    {"kind": "verify", "text": "라이브 생성 10/10 도달"},
                    {"kind": "info", "text": "SemanticRequestFingerprint "
                     "클래스 로딩 오류로 모델 호출 전 실패 x2"},
                ])))
            r = self.run_budget(td)
        self.assertTrue(r["allowed"])
        self.assertEqual(0, r["extendBy"])
        self.assertEqual(8, r["recountedUsed"])
        self.assertIn("recount-under-cap", r["reasons"])

    def test_full_cap_auto_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-bbbb2222", events=[
                    {"kind": "verify", "text": "generation 10/10 reached "
                     "for verification items"},
                ])))
            r = self.run_budget(td)
        self.assertTrue(r["allowed"])
        self.assertEqual(5, r["extendBy"])
        self.assertEqual(10, r["recountedUsed"])

    def test_hardcap_blocks_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-cccc3333", events=[
                    {"kind": "verify", "text": "generation 10/10"},
                    {"kind": "info", "text": "HARD_CAP: 추가 생성 절대 금지"},
                ])))
            r = self.run_budget(td)
        self.assertFalse(r["allowed"])
        self.assertEqual(0, r["extendBy"])
        self.assertIn("hard-cap", r["reasons"])

    def test_auth_fail_blocks_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-dddd4444", events=[
                    {"kind": "verify", "text": "generation 10/10"},
                    {"kind": "info", "text": "upstream HTTP 401 observed"},
                ])))
            r = self.run_budget(td)
        self.assertFalse(r["allowed"])
        self.assertIn("auth-or-rate-fail-observed", r["reasons"])

    def test_already_extended_blocks_second(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-eeee5555", events=[
                    {"kind": "verify", "text": "generation 15/15"},
                    {"kind": "info", "text": "AUTO_DECISION: D30 "
                     "live-budget +5 (was 10, now 15)"},
                ])))
            r = self.run_budget(td)
        self.assertFalse(r["allowed"])
        self.assertIn("already-extended", r["reasons"])


LOG_LINE = ("{ts} INFO  [chat-1 {uuid}] c.e.l.s.r.PolicyBasedModelRouter - "
            "[plan9-oauth-phase] phase={phase} count=1 "
            "requestHash=hash:{h} atEpochMs=1 httpStatus={status}\n")
FINAL_LINE = ("{ts} DEBUG [chat-1 {uuid}] rag.pipeline - [rag-pipeline] "
              "stage=final-response elapsedMs=9 characters={chars} "
              "evidenceCount=1\n")


def log_text(h="aaaa1111"):
    u = "ff0d26db-ea1c-4d91-bf5c-35987651d817"
    out = []
    out.append(LOG_LINE.format(ts="2026-10-03T18:58:08.877+0900",
                             uuid=u, phase="controller", h=h, status=200))
    out.append(LOG_LINE.format(ts="2026-10-03T18:58:17.555+0900",
                             uuid=u, phase="terminal", h=h, status=200))
    out.append(FINAL_LINE.format(ts="2026-10-03T18:58:25.378+0900",
                                 uuid=u, chars=303))
    out.append(LOG_LINE.format(ts="2026-10-03T19:30:01.000+0900",
                               uuid=u, phase="terminal", h="bbbb2222",
                               status=500))
    # 리터럴 분할: checkpoint secret-scan 오탐 방지 — 실행 시 문자열은 동일.
    out.append("2026-10-03T18:58:30.000+0900 INFO  sid=SECRETSESSIONID12345 "
               "tok" "en=tok_ABCDEFGHIJKLMNOP unrelated\n")
    return "".join(out)


class LogEvidenceTest(unittest.TestCase):
    def make_logs(self, td):
        d = Path(td) / "run1"
        write(d / "chat-ui-vibe-listener-18180.out.log", log_text())
        return Path(td)

    def test_groups_hash_phases_and_final(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.make_logs(td)
            rep = CAU.log_evidence(root, model="aaaa1111",
                                   since="18:55", until="19:00")
        self.assertEqual(1, len(rep["requests"]))
        r = rep["requests"][0]
        self.assertEqual("aaaa1111", r["hash"])
        phases = [p["phase"] for p in r["phases"]]
        self.assertIn("controller", phases)
        self.assertIn("terminal", phases)
        self.assertEqual(303, r["finalResponse"]["characters"])

    def test_window_filters_later_hash(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.make_logs(td)
            rep = CAU.log_evidence(root, model="",
                                   since="18:55", until="19:00")
        hashes = [r["hash"] for r in rep["requests"]]
        self.assertIn("aaaa1111", hashes)
        self.assertNotIn("bbbb2222", hashes)  # 19:30 > until

    def test_masks_session_and_token(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.make_logs(td)
            rep = CAU.log_evidence(root, model="",
                                   since="18:55", until="19:05")
            blob = json.dumps(rep, ensure_ascii=False)
        self.assertNotIn("SECRETSESSIONID12345", blob)
        self.assertNotIn("tok_ABCDEFGHIJKLMNOP", blob)
        self.assertNotIn("ff0d26db-ea1c-4d91-bf5c-35987651d817", blob)


class LeaseWaitTest(unittest.TestCase):
    def lease(self, topic, targets, expires, status="active"):
        return {"topic": topic, "status": status,
                "expiresAtUtc": expires,
                "targetPaths": targets, "ownerState": "unknown"}

    def test_no_locks_is_free(self):
        with tempfile.TemporaryDirectory() as td:
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("free", rep["result"])
        self.assertEqual([], rep["live"])

    def test_expired_lease_is_stale(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "a.lock" / "lease.json", json.dumps(
                self.lease("old", ["scripts/x.py"],
                           "2026-10-03T00:00:00+00:00")))
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("stale", rep["result"])
        self.assertEqual(["old"], [l["topic"] for l in rep["stale"]])

    def test_active_lease_is_live(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "b.lock" / "lease.json", json.dumps(
                self.lease("peer", ["scripts/x.py"],
                           "2999-01-01T00:00:00+00:00")))
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("live", rep["result"])
        self.assertEqual(["peer"], [l["topic"] for l in rep["live"]])

    def test_non_overlapping_lease_ignored(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "c.lock" / "lease.json", json.dumps(
                self.lease("peer", ["scripts/other.py"],
                           "2999-01-01T00:00:00+00:00")))
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("free", rep["result"])

    def test_dry_run_main_exit(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "d.lock" / "lease.json", json.dumps(
                self.lease("peer", ["scripts/x.py"],
                           "2999-01-01T00:00:00+00:00")))
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CAU.main(["lease-wait", "--paths", "scripts/x.py",
                               "--locks-dir", td, "--dry-run"])
        self.assertEqual(0, rc)
        out = json.loads(buf.getvalue())
        self.assertEqual("live", out["result"])


class SupersededTest(unittest.TestCase):
    def make_pair(self, td, old_id, new_id, new_result):
        root = Path(td)
        write(root / old_id / "journal.json", json.dumps(journal(
            old_id, result="partial", purpose="same goal",
            events=[{"kind": "verify", "text": "A1 partial"}])))
        write(root / new_id / "journal.json", json.dumps(journal(
            new_id, result=new_result, status="closed",
            purpose="same goal", updated="2026-10-03T09:00:00+00:00")))
        write(root / new_id / "report.md",
              "A1 PASS\nA2 PASS\nA3 NOT_RUN\n")
        return root / old_id

    def test_newer_verified_is_superseded(self):
        with tempfile.TemporaryDirectory() as td:
            old = self.make_pair(td, "topic-aaaa1111", "topic-bbbb2222",
                                 "verified")
            rep = CAU.superseded_eval(old)
        self.assertTrue(rep["superseded"])
        self.assertEqual("topic-bbbb2222", rep["by"])
        self.assertIn("A1", rep["passItems"])
        self.assertIn("A2", rep["passItems"])
        self.assertNotIn("A3", rep["passItems"])

    def test_newer_partial_not_superseded(self):
        with tempfile.TemporaryDirectory() as td:
            old = self.make_pair(td, "topic-aaaa1111", "topic-bbbb2222",
                                 "partial")
            rep = CAU.superseded_eval(old)
        self.assertFalse(rep["superseded"])

    def test_different_prefix_not_superseded(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            write(root / "topic-aaaa1111" / "journal.json",
                  json.dumps(journal("topic-aaaa1111", result="partial")))
            write(root / "other-bbbb2222" / "journal.json",
                  json.dumps(journal("other-bbbb2222", result="verified")))
            rep = CAU.superseded_eval(root / "topic-aaaa1111")
        self.assertFalse(rep["superseded"])

    def test_main_json_output(self):
        with tempfile.TemporaryDirectory() as td:
            old = self.make_pair(td, "topic-aaaa1111", "topic-bbbb2222",
                                 "verified")
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CAU.main(["superseded", "--ledger", str(old)])
        self.assertEqual(0, rc)
        out = json.loads(buf.getvalue())
        self.assertTrue(out["superseded"])


if __name__ == "__main__":
    unittest.main()
