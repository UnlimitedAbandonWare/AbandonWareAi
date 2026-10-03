"""f01b_evidence_pack unittest — 스캐폴드 생성/보존 규칙.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f01b_evidence_pack.py")
SPEC = importlib.util.spec_from_file_location("f01b_evidence_pack", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("f01b_evidence_pack", MOD)
    SPEC.loader.exec_module(MOD)


class EvidencePackTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.out = self.root / "docs/diagnostics/f01b-narrow-jdbc-0929"

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def test_creates_all_files(self):
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        for name in ("README.md", "GATE0_PROBE.md", "FOR_CODEX.md",
                     "decision.json"):
            self.assertTrue((self.out / name).is_file(), name)
        decision = json.loads((self.out / "decision.json")
                              .read_text(encoding="utf-8"))
        self.assertEqual(decision["status"], "assist_scripts_ready")
        self.assertFalse(decision["task_ask"])
        self.assertEqual(decision["devin_product_java_diff"], 0)

    def test_existing_not_overwritten_exit_3(self):
        self.run_main("--root", str(self.root))
        marker = "# hand-edited\n"
        (self.out / "FOR_CODEX.md").write_text(marker, encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 3)
        self.assertEqual((self.out / "FOR_CODEX.md")
                         .read_text(encoding="utf-8"), marker)

    def test_force_overwrites(self):
        self.run_main("--root", str(self.root))
        (self.out / "FOR_CODEX.md").write_text("x", encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--force")
        self.assertEqual(code, 0)
        self.assertIn("FOR_CODEX",
                      (self.out / "FOR_CODEX.md").read_text(encoding="utf-8"))

    def test_fills_only_missing(self):
        self.out.mkdir(parents=True)
        (self.out / "decision.json").write_text('{"keep": 1}\n',
                                                encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        self.assertEqual((self.out / "decision.json")
                         .read_text(encoding="utf-8"), '{"keep": 1}\n')
        self.assertTrue((self.out / "README.md").is_file())

    def test_probe_json_embedded(self):
        probe_dir = self.root / "data/diagnostics/f01b-trace-access-0929"
        probe_dir.mkdir(parents=True)
        (probe_dir / "f01b_schema_gate.json").write_text(json.dumps(
            {"verdict": "GATE0_PARTIAL_EVIDENCE_NEEDED", "exitCode": 3,
             "generatedAtUtc": "2026-09-29T00:00:00Z",
             "file": {"checks": {"awx_jobs_create": True}},
             "live": {"reachable": True, "via": "fake",
                      "tables": {"awx_jobs": "ABSENT"},
                      "columns": {}, "index": {}}}), encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        probe_md = (self.out / "GATE0_PROBE.md").read_text(encoding="utf-8")
        self.assertIn("GATE0_PARTIAL_EVIDENCE_NEEDED", probe_md)
        self.assertIn("ABSENT", probe_md)


class EvidenceSummaryTest(unittest.TestCase):
    """--summary: SoT 스캔 → EVIDENCE_SUMMARY.md 한 장 (POST-TOOLS 항목 4)."""

    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.handoff = self.root / "data/agent-handoff/codex-autonomy/task-x"
        self.handoff.mkdir(parents=True)

    def put(self, name: str, payload):
        (self.handoff / name).write_text(json.dumps(payload),
                                        encoding="utf-8")

    def run_summary(self, *argv):
        return MOD.main(["--root", str(self.root), "--summary",
                         "--handoff-dir",
                         "data/agent-handoff/codex-autonomy/task-x",
                         *argv])

    def test_summary_one_pager(self):
        self.put("sliceA-red-suites.json",
                 [{"suite": "a", "tests": 3, "failures": 3,
                   "errors": 0, "skipped": 0}])
        self.put("sliceA-green-suites.json",
                 [{"suite": "a", "tests": 3, "failures": 0,
                   "errors": 0, "skipped": 0}])
        self.put("gate-suites.json",
                 [{"suite": "g", "tests": 6, "failures": 0,
                   "errors": 0, "skipped": 0}])
        self.put("gate0-after-apply.json",
                 {"verdict": "GATE0_PASS", "exitCode": 0,
                  "live": {"via": "auto"}})
        self.put("migration-durable-applied.json",
                 {"file": "V20260912__durable_jobs.sql", "statementCount": 2,
                  "approvedMigrationSha256": "ab" * 32, "applied": True,
                  "jdbcExit": 0})
        (self.handoff / "final").mkdir()
        (self.handoff / "final/completion-v4.json").write_text(json.dumps({
            "limitations": {"trace": False, "commit": False,
                            "liveProviderProof": "NOT_OBSERVED",
                            "affectedTests": {"tests": 418, "passed": 413,
                                              "failed": 5, "newFailures": 0},
                            "runtime": {"fullVerification": False,
                                        "target": "partial"}}}),
            encoding="utf-8")
        code = self.run_summary()
        self.assertEqual(0, code)
        md = (self.root / "docs/diagnostics/f01b-narrow-jdbc-0929"
              / "EVIDENCE_SUMMARY.md").read_text(encoding="utf-8")
        self.assertIn("RED→GREEN", md)
        self.assertIn("sliceA", md)
        self.assertIn("GATE0_PASS", md)
        self.assertIn("V20260912__durable_jobs.sql", md)
        self.assertIn("NOT_RUN", md)
        self.assertIn("liveProviderProof", md)

    def test_summary_skips_existing_without_force(self):
        md_dir = self.root / "docs/diagnostics/f01b-narrow-jdbc-0929"
        md_dir.mkdir(parents=True)
        (md_dir / "EVIDENCE_SUMMARY.md").write_text("keep", encoding="utf-8")
        code = self.run_summary()
        self.assertEqual(3, code)
        self.assertEqual("keep", (md_dir / "EVIDENCE_SUMMARY.md")
                         .read_text(encoding="utf-8"))

    def test_missing_handoff_is_empty_scan_not_crash(self):
        self.handoff.rename(self.root / "moved")
        code = MOD.main(["--root", str(self.root), "--summary",
                         "--handoff-dir", "moved/none"])
        self.assertEqual(0, code)


if __name__ == "__main__":
    unittest.main()
