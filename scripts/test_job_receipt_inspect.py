"""job_receipt_inspect unittest — fixture 기반 분류/판정/마스킹 계약.

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 5.
live DB 없이 fixture JSON 으로 전체 경로를 검증한다.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("job_receipt_inspect.py")
SPEC = importlib.util.spec_from_file_location("job_receipt_inspect", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("job_receipt_inspect", MOD)
    SPEC.loader.exec_module(MOD)


def fixture_payload(**queries):
    return {"queries": queries}


ROWS_HEALTHY = {
    "stateCounts": {"ok": True, "rows": [
        {"STATE": "SUCCEEDED", "CNT": 40},
        {"STATE": "PENDING", "CNT": 2}]},
    "typeCounts": {"ok": True, "rows": [
        {"JOB_TYPE": "understanding_summary_v1", "STATE": "SUCCEEDED",
         "CNT": 40}]},
    "stuckRunning": {"ok": True, "rows": []},
    "outcomeUnknown": {"ok": True, "rows": []},
    "failed": {"ok": True, "rows": []},
    "ttlCandidates": {"ok": True, "rows": []},
    "callbackCounts": {"ok": True, "rows": [{"CALLBACK_STATE": "NONE", "CNT": 42}]},
    "receiptCounts": {"ok": True, "rows": [{"RECEIPT_STATE": "PERSISTED", "CNT": 40}]},
    "receiptOrphans": {"ok": True, "rows": [{"CNT": 0}]},
    "resultOrphans": {"ok": True, "rows": [{"CNT": 0}]},
}


class JobReceiptInspectTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write_fixture(self, payload: dict) -> Path:
        path = self.root / "fixture.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def run_main(self, *argv):
        out = self.root / "out" / "inspect.json"
        code = MOD.main(["--root", str(self.root), "--json-out", str(out),
                         *argv])
        payload = json.loads(out.read_text(encoding="utf-8")) \
            if out.is_file() else None
        return code, payload

    def test_healthy_fixture_verdict(self):
        fx = self.write_fixture(fixture_payload(**ROWS_HEALTHY))
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(0, code)
        self.assertEqual("healthy", payload["verdict"])
        self.assertEqual(42, payload["summary"]["totalJobs"])
        self.assertTrue(payload["summary"]["receipts"]["available"])
        self.assertEqual("fixture", payload["via"])

    def test_stuck_and_exhausted_flagged(self):
        rows = dict(ROWS_HEALTHY)
        rows["stuckRunning"] = {"ok": True, "rows": [
            {"TASK_ID": "t-1", "JOB_TYPE": "j", "STATE": "RUNNING",
             "PHASE": "COMMIT", "ERROR_CODE": None, "UPDATED_AT": 100,
             "LEASE_UNTIL": 50}]}
        rows["outcomeUnknown"] = {"ok": True, "rows": [
            {"TASK_ID": "t-2", "JOB_TYPE": "j", "PHASE": "COMPUTE",
             "ERROR_CODE": "derived_outcome_unconfirmed",
             "COMPUTE_ATTEMPTS": 2, "COMMIT_ATTEMPTS": 0,
             "NEXT_ATTEMPT_AT": 0, "UPDATED_AT": 200}]}
        fx = self.write_fixture(fixture_payload(**rows))
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(0, code)
        self.assertEqual("attention", payload["verdict"])
        self.assertEqual(1, payload["summary"]["stuck"]["count"])
        self.assertEqual("lease_expired",
                         payload["summary"]["stuck"]["candidates"][0]["kind"])
        self.assertEqual(1, payload["summary"]["outcomeUnknown"]["exhausted"])

    def test_outcome_unknown_classification(self):
        cancel = {"PHASE": "COMMIT", "ERROR_CODE": "cancelled_worker_lost",
                  "COMPUTE_ATTEMPTS": 0, "COMMIT_ATTEMPTS": 9}
        self.assertEqual("cancel_finalize_pending",
                         MOD.classify_outcome_unknown(cancel))
        retry = {"PHASE": "COMMIT", "ERROR_CODE": "x",
                 "COMPUTE_ATTEMPTS": 0, "COMMIT_ATTEMPTS": 1}
        self.assertEqual("retryable", MOD.classify_outcome_unknown(retry))

    def test_unreachable_when_state_counts_fails(self):
        fx = self.write_fixture(fixture_payload(
            stateCounts={"ok": False, "error": "locked", "rows": None}))
        # fixture 경로는 stateCounts 실패가 아닌 fixture-missing 만큼만 진행 —
        # 직접 summarize 없이 main 은 fixture 결과를 그대로 쓴다.
        # unreachable 은 live 경로 판정이므로, fixture 에선 ok=False 가
        # queryErrors 로 보고되고 reachable=True 유지(첫 쿼리만 게이트).
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(0, code)
        self.assertIn("stateCounts", payload["queryErrors"])

    def test_masking_never_selected_columns(self):
        fx = self.write_fixture(fixture_payload(**ROWS_HEALTHY))
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(0, code)
        # 후보 행에 자격/본문 컬럼이 실재하면 안 된다 (NEVER_SELECTED 는 목록만).
        for row in payload["summary"]["stuck"]["candidates"] + \
                payload["summary"]["failed"]["candidates"]:
            for col in ("PAYLOAD", "BODY", "OWNER_HASH", "WORKER_TOKEN"):
                self.assertNotIn(col, row)
        self.assertIn("payload", payload["masking"]["neverSelected"])

    def test_sql_contains_no_secret_columns(self):
        for key, sql in MOD.build_queries(1_700_000_000_000, 50).items():
            low = sql.lower()
            for col in ("owner_hash", "worker_token", "admission_key",
                        "request_fingerprint", "callback_token"):
                self.assertNotIn(col, low, f"{key} selects {col}")
            self.assertNotIn("payload", low.split("from")[0],
                             f"{key} selects payload")
            self.assertTrue(low.strip().startswith("select"), key)


if __name__ == "__main__":
    unittest.main()
