"""Fixture tests for retrieval_facts_guard.py. No network."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "retrieval_facts_guard.py"
REPO = Path(__file__).resolve().parents[1]

BM25_PROPS = "main/java/com/abandonware/ai/agent/config/Bm25Props.java"
PINECONE = "main/java/com/example/lms/service/vector/PineconeVectorStoreAdapter.java"
BM25_CONFIG = "main/java/com/example/lms/config/Bm25Config.java"
CHAIN = (
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/service/rag/HybridRetriever.java",
    "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java",
    "main/java/com/example/lms/config/RetrieverChainConfig.java",
)


def write_text(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def scaffold(root: Path, props_default: str = "true", status: bool = True,
             properties: bytes | None = None) -> None:
    write_text(root, BM25_PROPS, '@Value("${bm25.enabled:%s}")\n' % props_default)
    write_text(
        root,
        PINECONE,
        '"cosine".equals(index.path("metric").asText())\n'
        '"dense".equals(index.path("vector_type").asText("dense"))\n',
    )
    write_text(
        root,
        BM25_CONFIG,
        "public class Bm25Config {\n"
        '    public boolean enabled = Boolean.parseBoolean('
        'System.getProperty("retrieval.bm25.enabled", "false"));\n'
        "}\n",
    )
    for rel in CHAIN:
        class_name = Path(rel).stem
        write_text(root, rel, "public class %s {}\n" % class_name)
    blob = properties if properties is not None else b"rag.hybrid.weight.vector=0.6\n"
    prop_path = root / "main/resources/application.properties"
    prop_path.parent.mkdir(parents=True, exist_ok=True)
    prop_path.write_bytes(blob)
    if status:
        write_text(root, "docs/RAG_SPARSE_STATUS.md", "# status\n")


def run_guard(root: Path):
    proc = subprocess.run(
        [sys.executable, "-B", str(SCRIPT), "--root", str(root), "--json"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    payload = json.loads(proc.stdout)
    return proc.returncode, payload, proc.stderr


class RetrievalFactsGuardTests(unittest.TestCase):
    def test_pass_when_facts_hold(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root)
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 0)
        self.assertEqual(payload["verdict"], "PASS")
        self.assertEqual(payload["exitCode"], 0)
        by_id = {item["id"]: item["status"] for item in payload["checks"]}
        self.assertEqual(by_id["F1"], "PASS")
        self.assertEqual(by_id["F2"], "PASS")
        self.assertEqual(by_id["F3"], "PASS")
        self.assertEqual(by_id["F4"], "PASS")
        self.assertEqual(by_id["F5"], "PASS")
        self.assertEqual(by_id["F6"], "PASS")
        self.assertEqual(by_id["F7"], "INFO")
        self.assertTrue(payload["info"]["f7"]["weightPresent"])
        self.assertEqual(payload["info"]["f7"]["javaConsumerCount"], 0)
        self.assertEqual(payload["findings"], [])

    def test_drift_when_bm25props_default_false(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root, props_default="false")
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 2)
        self.assertEqual(payload["verdict"], "DRIFT")
        f1 = next(item for item in payload["findings"] if item["id"] == "F1")
        self.assertEqual(f1["status"], "DRIFT")
        self.assertEqual(f1["path"], BM25_PROPS)

    def test_stale_when_wrong_phrase_present(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root)
            write_text(root, ".grok/rules/bad-off.md", "BM25 기본 OFF\n")
            write_text(root, ".grok/rules/bad-main.md", "retrieval.bm25.enabled가 메인 스위치다\n")
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 2)
        self.assertEqual(payload["verdict"], "STALE")
        hits = [item for item in payload["findings"] if item["status"] == "STALE"]
        self.assertGreaterEqual(len(hits), 2)
        self.assertTrue(any(item["line"] == 1 and item["path"].endswith("bad-off.md") for item in hits))
        self.assertTrue(any(item["line"] == 1 and item["path"].endswith("bad-main.md") for item in hits))

    def test_allow_marker_ignores_wrong_phrase(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root)
            write_text(root, ".grok/rules/allowed.md", "BM25 기본 OFF facts-guard:allow\n")
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 0)
        self.assertEqual(payload["verdict"], "PASS")
        self.assertFalse(any(item["status"] == "STALE" for item in payload["findings"]))

    def test_pending_when_status_doc_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root, status=False)
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 0)
        self.assertEqual(payload["verdict"], "PENDING_CODEX")
        self.assertEqual(payload["exitCode"], 0)
        f6 = next(item for item in payload["findings"] if item["id"] == "F6")
        self.assertEqual(f6["status"], "PENDING_CODEX")
        self.assertFalse(any(item["status"] in {"DRIFT", "STALE"} for item in payload["findings"]))

    def test_cp949_properties_do_not_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            blob = "메모=가나다\n".encode("cp949") + b"rag.hybrid.weight.keyword=0.4\n"
            scaffold(root, properties=blob)
            code, payload, err = run_guard(root)
        self.assertEqual(code, 0)
        self.assertNotIn("Traceback", err)
        self.assertEqual(payload["verdict"], "PASS")
        self.assertTrue(payload["info"]["f7"]["weightPresent"])
        self.assertGreaterEqual(payload["info"]["f7"]["occurrences"], 1)

    def test_drift_when_bm25config_gains_component(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root)
            write_text(
                root,
                BM25_CONFIG,
                "@Component\npublic class Bm25Config {}\n",
            )
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 2)
        f4 = next(item for item in payload["findings"] if item["id"] == "F4")
        self.assertEqual(f4["status"], "DRIFT")
        self.assertEqual(f4["line"], 1)

    def test_shipped_pointers_are_not_stale(self) -> None:
        rule_path = REPO / ".grok/rules/demo1-retrieval-sparse-facts.md"
        skill_path = REPO / ".agents/skills/demo1-retrieval-sparse-facts/SKILL.md"
        rule = rule_path.read_text(encoding="utf-8")
        skill = skill_path.read_text(encoding="utf-8")
        self.assertLessEqual(len(rule.splitlines()), 5)
        self.assertLessEqual(len(skill.splitlines()), 40)
        self.assertIn("docs/RAG_SPARSE_STATUS.md", rule)
        self.assertIn("docs/RAG_SPARSE_STATUS.md", skill)
        self.assertIn("name: demo1-retrieval-sparse-facts", skill)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scaffold(root)
            write_text(root, ".grok/rules/demo1-retrieval-sparse-facts.md", rule)
            write_text(root, ".agents/skills/demo1-retrieval-sparse-facts/SKILL.md", skill)
            code, payload, _err = run_guard(root)
        self.assertEqual(code, 0)
        self.assertEqual(payload["verdict"], "PASS")
        self.assertFalse(any(item["status"] == "STALE" for item in payload["findings"]))


if __name__ == "__main__":
    unittest.main()
