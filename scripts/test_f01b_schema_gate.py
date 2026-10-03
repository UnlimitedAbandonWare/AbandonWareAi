"""f01b_schema_gate unittest — 임시 디렉터리 fixture, 실DB/네트워크 불필요.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
live 모드는 가짜 db_agent 스크립트(JSON 응답 흉내)로 검증한다.
"""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f01b_schema_gate.py")
SPEC = importlib.util.spec_from_file_location("f01b_schema_gate", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("f01b_schema_gate", MOD)
    SPEC.loader.exec_module(MOD)

DURABLE = """CREATE TABLE IF NOT EXISTS awx_jobs (task_id VARCHAR(36) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS awx_job_results (result_id VARCHAR(36) PRIMARY KEY);
"""
IDEM = """ALTER TABLE awx_jobs ADD COLUMN admission_key VARCHAR(64);
ALTER TABLE awx_jobs ADD COLUMN request_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX awx_jobs_admission_key ON awx_jobs(admission_key);
"""

FAKE_AGENT = """import json, sys
sql = ""
for i, a in enumerate(sys.argv):
    if a == "--sql":
        sql = sys.argv[i + 1]
out = {"ok": True, "via": "fake", "rows": []}
if "TABLES" in sql:
    out["rows"] = [{"TABLE_NAME": "AWX_JOBS"},
                   {"TABLE_NAME": "AWX_JOB_RESULTS"}]
elif "COLUMNS" in sql:
    out["rows"] = [{"COLUMN_NAME": "ADMISSION_KEY"},
                   {"COLUMN_NAME": "REQUEST_FINGERPRINT"}]
elif "INDEXES" in sql:
    out["rows"] = %s
print(json.dumps(out))
"""


class SchemaGateTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.mig = self.root / "main/resources/db/migration"
        self.mig.mkdir(parents=True)

    def write_ddl(self, durable=DURABLE, idem=IDEM):
        (self.mig / "V20260912__durable_jobs.sql").write_text(
            durable, encoding="utf-8")
        (self.mig / "V20260912_03__job_idempotency.sql").write_text(
            idem, encoding="utf-8")

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def test_file_mode_pass(self):
        self.write_ddl()
        code = self.run_main("--root", str(self.root), "--mode", "file")
        self.assertEqual(code, 0)
        payload = json.loads((self.root / "data/diagnostics/"
                              "f01b-trace-access-0929/f01b_schema_gate.json")
                             .read_text(encoding="utf-8"))
        self.assertEqual(payload["verdict"], "GATE0_PASS")
        self.assertTrue(payload["file"]["ok"])
        self.assertIsNone(payload["live"])

    def test_file_mode_missing_ddl_fails(self):
        (self.mig / "V20260912__durable_jobs.sql").write_text(DURABLE)
        code = self.run_main("--root", str(self.root), "--mode", "file")
        self.assertEqual(code, 2)
        payload = json.loads((self.root / "data/diagnostics/"
                              "f01b-trace-access-0929/f01b_schema_gate.json")
                             .read_text(encoding="utf-8"))
        self.assertEqual(payload["verdict"], "GATE0_FAIL")
        self.assertFalse(payload["file"]["checks"]["admission_key_column"])

    def test_live_present_passes(self):
        self.write_ddl()
        agent = self.root / "fake_db_agent.py"
        agent.write_text(FAKE_AGENT % '[{"INDEX_NAME": "AWX_JOBS_ADMISSION_KEY"}]',
                         encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--mode", "live",
                             "--db-agent", str(agent))
        self.assertEqual(code, 0)
        payload = json.loads((self.root / "data/diagnostics/"
                              "f01b-trace-access-0929/f01b_schema_gate.json")
                             .read_text(encoding="utf-8"))
        self.assertEqual(payload["live"]["tables"]["awx_jobs"], "PRESENT")
        self.assertEqual(payload["live"]["index"]["awx_jobs_admission_key"],
                         "PRESENT")

    def test_live_absent_fails(self):
        self.write_ddl()
        agent = self.root / "fake_db_agent.py"
        agent.write_text(FAKE_AGENT % "[]", encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--mode", "live",
                             "--db-agent", str(agent))
        self.assertEqual(code, 2)

    def test_live_unreachable_partial(self):
        self.write_ddl()
        agent = self.root / "dead_agent.py"
        agent.write_text('import sys; sys.exit(5)', encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--mode", "both",
                             "--db-agent", str(agent))
        self.assertEqual(code, 3)
        payload = json.loads((self.root / "data/diagnostics/"
                              "f01b-trace-access-0929/f01b_schema_gate.json")
                             .read_text(encoding="utf-8"))
        self.assertEqual(payload["verdict"], "GATE0_PARTIAL_EVIDENCE_NEEDED")

    def test_env_value_never_printed(self):
        self.write_ddl()
        secret = "jdbc:h2:file:/nonexistent/secret-path;PASSWORD=hunter2"
        os.environ["AWX_TEST_JDBC_URL"] = secret
        self.addCleanup(os.environ.pop, "AWX_TEST_JDBC_URL")
        code = self.run_main("--root", str(self.root), "--mode", "file",
                             "--jdbc-url-env", "AWX_TEST_JDBC_URL")
        self.assertEqual(code, 0)
        raw = (self.root / "data/diagnostics/f01b-trace-access-0929/"
               "f01b_schema_gate.json").read_text(encoding="utf-8")
        self.assertIn("AWX_TEST_JDBC_URL", raw)
        self.assertNotIn("hunter2", raw)
        self.assertNotIn(secret, raw)


if __name__ == "__main__":
    unittest.main()
