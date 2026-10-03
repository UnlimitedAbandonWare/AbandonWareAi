"""agent_access_bundle unittest — 임시 root 에 스크립트 사본 + fixture 로 e2e.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
실DB 불필요 (--skip-live-db). subprocess 비용은 소규모.
"""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("agent_access_bundle.py")
SPEC = importlib.util.spec_from_file_location("agent_access_bundle", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("agent_access_bundle", MOD)
    SPEC.loader.exec_module(MOD)

SCRIPTS_DIR = SCRIPT.parent

CHILDREN = [
    "f01b_schema_gate.py", "f01b_tm_probe.py", "f01b_admission_key_demo.py",
    "f01b_evidence_pack.py", "trace_dock_cost_guard.py",
    "trace_dock_a11y_scan.py",
]

DURABLE = """CREATE TABLE IF NOT EXISTS awx_jobs (task_id VARCHAR(36) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS awx_job_results (result_id VARCHAR(36) PRIMARY KEY);
"""
IDEM = """ALTER TABLE awx_jobs ADD COLUMN admission_key VARCHAR(64);
ALTER TABLE awx_jobs ADD COLUMN request_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX awx_jobs_admission_key ON awx_jobs(admission_key);
"""

COUPLED_TRACE = """
function enabled() { return document.querySelector("[data-chat-trace-toggle]")?.checked; }
function withDebugQuery(path) {
  if (!enabled()) return path;
  return path + "?debug=true";
}
"""
CHAT_ROUTED = """
function chatTraceRequestUrl(url) { return window.AwxChatTraceUi?.withDebugQuery(url) || url; }
const streamUrl = chatTraceRequestUrl("/api/chat/stream");
"""

JDBC_PRIVATE = """
public final class JdbcJobService {
    public JdbcJobService(DataSource ds) {
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
}
"""
JOB_CONFIG = """
public class JobConfig {
    public JobService jobService(DataSource ds) { return new JdbcJobService(ds); }
}
"""


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        # 실 스크립트 사본을 임시 root/scripts 에 배치
        sdir = self.root / "scripts"
        sdir.mkdir()
        for name in CHILDREN:
            shutil.copy(SCRIPTS_DIR / name, sdir / name)
        # fixture: DDL + Java + JS
        mig = self.root / "main/resources/db/migration"
        mig.mkdir(parents=True)
        (mig / "V20260912__durable_jobs.sql").write_text(DURABLE,
                                                       encoding="utf-8")
        (mig / "V20260912_03__job_idempotency.sql").write_text(
            IDEM, encoding="utf-8")
        jdir = self.root / "main/java/com/example/lms/jobs"
        cdir = self.root / "main/java/com/example/lms/config"
        jdir.mkdir(parents=True)
        cdir.mkdir(parents=True)
        (jdir / "JdbcJobService.java").write_text(JDBC_PRIVATE,
                                                encoding="utf-8")
        (cdir / "JobConfig.java").write_text(JOB_CONFIG, encoding="utf-8")
        jsdir = self.root / "main/resources/static/js"
        jsdir.mkdir(parents=True)
        (jsdir / "chat-trace-ui.js").write_text(COUPLED_TRACE,
                                              encoding="utf-8")
        (jsdir / "chat.js").write_text(CHAT_ROUTED, encoding="utf-8")

    def payload(self):
        return json.loads(
            (self.root / "data/diagnostics/f01b-trace-access-0929/"
             "agent_access_bundle.json").read_text(encoding="utf-8"))

    def test_all_tracks_run(self):
        code = MOD.main(["--root", str(self.root), "--skip-live-db"])
        p = self.payload()
        self.assertEqual(set(p["children"].keys()),
                         {"F01B_SCHEMA", "F01B_TM", "F01B_KEYS",
                          "F01B_PACK", "TRACE_COST", "TRACE_A11Y"})
        # file-only schema fixture OK=0, TM private hint=0, keys=0, pack=0,
        # cost_guard coupled FAIL=2 (expected pre-patch), a11y absent=3
        self.assertEqual(p["children"]["F01B_SCHEMA"]["exit"], 0)
        self.assertEqual(p["children"]["F01B_KEYS"]["exit"], 0)
        self.assertEqual(p["children"]["F01B_PACK"]["exit"], 0)
        self.assertEqual(p["children"]["TRACE_COST"]["exit"], 2)
        self.assertEqual(p["children"]["TRACE_A11Y"]["exit"], 3)
        self.assertEqual(p["overallExit"], 2)
        self.assertEqual(code, 2)

    def test_track_filter_f01b_only(self):
        code = MOD.main(["--root", str(self.root), "--skip-live-db",
                         "--tracks", "f01b"])
        p = self.payload()
        self.assertEqual(set(p["children"].keys()),
                         {"F01B_SCHEMA", "F01B_TM", "F01B_KEYS", "F01B_PACK"})
        self.assertEqual(p["overallExit"], 0)
        self.assertEqual(code, 0)

    def test_unknown_track_usage_error(self):
        code = MOD.main(["--root", str(self.root), "--tracks", "bogus"])
        self.assertEqual(code, 1)

    def test_per_child_json_written(self):
        MOD.main(["--root", str(self.root), "--skip-live-db"])
        out = self.root / "data/diagnostics/f01b-trace-access-0929"
        for name in ("f01b_schema_gate.json", "f01b_tm_probe.json",
                     "f01b_admission_key_demo.json",
                     "trace_dock_cost_guard.json",
                     "trace_dock_a11y_scan.json", "agent_access_bundle.json"):
            self.assertTrue((out / name).is_file(), name)


if __name__ == "__main__":
    unittest.main()
