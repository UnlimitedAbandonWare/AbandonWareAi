"""Fixture checks for the MAX-PUSH Clean rail. No product source writes."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SKIP = load("max_push_skip_inactive")
SCAN = load("max_push_executor_log_scan")
GUARD = load("max_push_done_guard")

APP = """
retrieval:
  kg:
    neo4j:
      enabled: ${RETRIEVAL_KG_NEO4J_ENABLED:false}
graphdb:
  manual-learning:
    enabled: ${GRAPHDB_MANUAL_LEARNING_ENABLED:false}
"""
GRAPH = "indexing:\n      enabled: ${RAG_BRAIN_STATE_INDEXING_ENABLED:true}\n"
CONTROLLER = "for (String c : chunk(visibleFinalText, 60)) {\n"


class SkipInactiveTest(unittest.TestCase):
    def test_defaults_are_skip_inactive(self):
        report = SKIP.classify(APP, GRAPH, "local,meta-display", CONTROLLER, [],
                               "unset", "unset", "unset", False)
        verdicts = {item["id"]: item["verdict"] for item in report["items"]}
        self.assertEqual("SKIP_INACTIVE", verdicts["F05"])
        self.assertEqual("SKIP_INACTIVE", verdicts["F06"])
        self.assertEqual("SKIP_INACTIVE", verdicts["F11"])
        self.assertEqual("SKIP_INACTIVE", verdicts["F12"])
        self.assertTrue(report["enablesNothing"])

    def test_graph_profile_and_bm25_bean_stay_candidates(self):
        f05 = SKIP.classify_f05(GRAPH, "local,graph-rag")
        f12 = SKIP.classify_f12([
            "main/java/com/example/lms/service/service/rag/bm25/Bm25Index.java",
            "main/java/com/example/lms/service/Other.java",
        ], bean_declared=True)
        optional = SKIP.classify_f12([
            "main/java/com/example/lms/service/service/rag/bm25/Bm25Index.java",
            "main/java/com/example/lms/service/Other.java",
        ], bean_declared=False)
        self.assertEqual("ACTIVE_PROFILE", f05["verdict"])
        self.assertEqual("ACTIVE_CANDIDATE", f12["verdict"])
        self.assertEqual("SKIP_INACTIVE", optional["verdict"])
        self.assertTrue(optional["doNotEnable"])
        self.assertEqual(["main/java/com/example/lms/service/Other.java"], optional["otherMainJavaHits"])

    def test_missing_profile_is_evidence_needed(self):
        self.assertEqual("EVIDENCE_NEEDED", SKIP.classify_f05(GRAPH, "not_observed")["verdict"])

    def test_uri_present_does_not_enable(self):
        item = SKIP.classify_f06(APP, "unset", "unset", "unset", True)
        self.assertEqual("EVIDENCE_NEEDED", item["verdict"])
        self.assertTrue(item["doNotEnable"])
        self.assertNotIn("bolt://", json.dumps(item))


class ExecutorScanTest(unittest.TestCase):
    def test_counts_without_line_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_dir = root / "var" / "rag-launcher"
            log_dir.mkdir(parents=True)
            (log_dir / "boot.out").write_text(
                "RejectedExecutionException queue full\n"
                "user question about a private topic stays out\n",
                encoding="utf-8")
            report = SCAN.scan_dir(log_dir, 5, root)
            rendered = json.dumps(report)
            self.assertEqual(1, report["counts"]["RejectedExecutionException"])
            self.assertNotIn("private topic", rendered)
            self.assertEqual("observed", report["status"])

    def test_missing_dir_is_not_observed(self):
        report = SCAN.scan_dir(Path("C:/no/such/max-push-logs"), 5, Path("C:/no/such"))
        self.assertEqual("not_observed", report["status"])


class DoneGuardTest(unittest.TestCase):
    def test_focused_claim_passes(self):
        self.assertEqual([], GUARD.claim_blocked(
            "Kit A focused tests exited 0. Full suite was not run."))

    def test_forbidden_done_claims(self):
        self.assertIn("full-suite-done", GUARD.claim_blocked("전체 검증 통과"))
        self.assertIn("admin-browser-done", GUARD.claim_blocked(
            "admin 로그인 5시나리오 = Done"))

    def test_prohibition_line_is_not_a_claim(self):
        self.assertEqual([], GUARD.claim_blocked(
            "admin 로그인 5시나리오 = Done 은 금지"))


if __name__ == "__main__":
    unittest.main()
