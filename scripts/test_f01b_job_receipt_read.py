"""f01b_job_receipt_read unittest - 가짜 db_agent fixture, 실DB/네트워크 불필요.

Contract DEMO1-CODEX-POSTF01B-TRACE-R2-TOOLMAP-20260929 (Devin post-tools).
마스킹: payload/body 원문이 SELECT 되지 않고 바이트 수만, token 은 presence,
hash/key 는 prefix8 만 내려오는지 SQL 문자열 수준에서 검증한다.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f01b_job_receipt_read.py")
SPEC = importlib.util.spec_from_file_location("f01b_job_receipt_read", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("f01b_job_receipt_read", MOD)
    SPEC.loader.exec_module(MOD)

JOBS_COLS = ["task_id", "job_type", "owner_hash", "payload", "state",
             "created_at", "updated_at", "completed_at", "expires_at",
             "worker_token", "lease_until", "result_ref", "error_code",
             "callback_state", "callback_attempts", "callback_next",
             "callback_token", "callback_until", "admission_key",
             "request_fingerprint", "effect_key", "original_run_id",
             "source_session_id", "phase", "compute_attempts",
             "commit_attempts", "next_attempt_at"]
RECEIPT_COLS = ["effect_key", "owner_namespace", "session_id", "channel",
                "consent_epoch", "user_message_id", "user_revision",
                "assistant_message_id", "assistant_revision", "kind",
                "original_run_id", "job_task_id", "usum_message_id",
                "result_sha256", "persisted_at", "receipt_state"]
RESULT_COLS = ["result_id", "task_id", "body", "body_sha256", "body_bytes"]

FAKE_AGENT = '''import json, os, sys
args = sys.argv[1:]
if args and args[0] == "tables":
    print(json.dumps({"ok": True, "via": "fake", "tables": %s}))
    sys.exit(0)
sql = ""
for i, a in enumerate(args):
    if a == "--sql":
        sql = args[i + 1]
cap = os.environ.get("SQL_CAPTURE")
if cap:
    with open(cap, "a", encoding="utf-8") as f:
        f.write(sql + "\\n---\\n")
out = {"ok": True, "via": "fake", "columns": [], "rows": []}
low = sql.lower()
if "information_schema.columns" in low:
    if "awx_jobs" in low:
        out["rows"] = [[c] for c in %r]
    elif "awx_job_results" in low:
        out["rows"] = [[c] for c in %r]
    elif "awx_understanding_receipts" in low:
        out["rows"] = [[c] for c in %r]
elif "count(*)" in low and "group by" not in low:
    out["rows"] = [["4"]]
elif "group by" in low:
    out["columns"] = ["k", "c"]
    out["rows"] = [["DONE", "3"], ["FAILED", "1"]]
elif "from awx_jobs" in low:
    out["columns"] = ["task_id", "job_type", "state", "owner_hash_prefix",
                      "payload_len", "worker_tok_presence"]
    out["rows"] = [["t-1", "understanding", "DONE", "deadbeef", 512, "present"]]
elif "from awx_job_results" in low:
    out["columns"] = ["result_id", "task_id", "body_bytes", "body_sha256_prefix"]
    out["rows"] = [["r-1", "t-1", 512, "cafe1234"]]
elif "from awx_understanding_receipts" in low:
    out["columns"] = ["session_id", "kind", "receipt_state",
                      "effect_key_prefix", "owner_namespace_prefix"]
    out["rows"] = [[7, "summary", "PERSISTED", "aa11bb22", "ownerxyz"]]
print(json.dumps(out))
'''


def fake_agent(tables):
    rows = [{"name": t} for t in tables]
    return FAKE_AGENT % (rows, JOBS_COLS, RESULT_COLS, RECEIPT_COLS)


class JobReceiptReadTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.capture = self.root / "sqls.txt"
        os.environ["SQL_CAPTURE"] = str(self.capture)
        self.addCleanup(os.environ.pop, "SQL_CAPTURE")

    def write_agent(self, text=None):
        agent = self.root / "fake_db_agent.py"
        agent.write_text(text if text is not None else
                         fake_agent(["awx_jobs", "awx_job_results",
                                     "awx_understanding_receipts"]),
                         encoding="utf-8")
        return agent

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def captured_sqls(self):
        return self.capture.read_text(encoding="utf-8") \
            if self.capture.exists() else ""

    def test_full_summary_masked(self):
        agent = self.write_agent()
        code = self.run_main("--db-agent", str(agent), "--recent", "5")
        self.assertEqual(code, 0)

    def test_absent_tables_still_exit_zero(self):
        agent = self.write_agent(fake_agent(["chat_messages"]))
        code = self.run_main("--db-agent", str(agent))
        self.assertEqual(code, 0)

    def test_lane_error_partial(self):
        agent = self.write_agent('import sys; sys.exit(5)')
        code = self.run_main("--db-agent", str(agent))
        self.assertEqual(code, 3)

    def test_payload_never_raw_selected(self):
        agent = self.write_agent()
        code = self.run_main("--db-agent", str(agent))
        self.assertEqual(code, 0)
        sqls = self.captured_sqls()
        for stmt in sqls.split("\n---\n"):
            stripped = re.sub(r"LENGTH\(payload\)", "", stmt)
            self.assertNotRegex(stripped, r"\bpayload\b",
                                f"raw payload selected: {stmt}")
            stripped = re.sub(r"LENGTH\(body\)", "", stmt)
            self.assertNotRegex(stripped, r"\bbody\b(?!\w)",
                                f"raw body selected: {stmt}")

    def test_token_and_hash_masked_in_sql(self):
        agent = self.write_agent()
        code = self.run_main("--db-agent", str(agent))
        self.assertEqual(code, 0)
        sqls = self.captured_sqls()
        self.assertIn("CASE WHEN worker_token IS NULL", sqls)
        self.assertIn("worker_tok_presence", sqls)
        self.assertIn("LEFT(owner_hash, 8)", sqls)
        self.assertIn("LEFT(effect_key, 8)", sqls)


if __name__ == "__main__":
    unittest.main()
