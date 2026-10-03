#!/usr/bin/env python3
"""Tests for scripts/plan5_core_truth_verify.py — fake roots + fixtures."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest
import unittest.mock

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts" / "plan5_core_truth_verify.py"

sys.path.insert(0, str(ROOT))
from scripts import plan5_core_truth_verify as verify  # noqa: E402
from scripts import plan5_core_truth_probe as probe  # noqa: E402

SPEC_OK = """package com.example.lms.plan;
public class PlanExecutionSpec {
    static Object resolve(java.util.Map<String,Object> dbg) {
        return switch (b()) {
            case RETRIEVAL -> {
                for (String k : KEYS) {
                    Object v = dbg.get(k);
                    if (v != null && !"disabled".equals(v)) {
                        if (String.valueOf(v).startsWith("missing_")) {
                            yield new E(StageStatus.SKIPPED_DEPENDENCY, k);
                        }
                        yield new E(StageStatus.EXECUTED, k);
                    }
                }
                yield new E(StageStatus.DECLARED, "");
            }
            default -> new E(StageStatus.UNAVAILABLE, "");
        };
    }
}
"""

ORCH_OK = """package com.example.lms.service.rag.orchestrator;
public class UnifiedRagOrchestrator {
    private java.util.List<Doc> toDocsOrEmpty(Bm25Index i, String q, int k, String s) {
        try { return i.searchDocs(q, k); }
        catch (Exception e) { lastError = "failed:" + e.getClass().getSimpleName(); return java.util.List.of(); }
    }
    void leg(java.util.Map<String,Object> dbg) {
        java.util.List<Doc> docs = toDocsOrEmpty(idx, q, 5, "BM25");
        if (lastError != null) dbg.put("stage.bm25", "failed:" + lastError);
        else if (docs.isEmpty()) dbg.put("stage.bm25", "empty");
    }
}
"""

NARROW_OK = """public class AngerOverdriveNarrower {
    @Autowired
    public AngerOverdriveNarrower(@Qualifier("crossEncoderReranker") ObjectProvider<CrossEncoderReranker> p) {
        this(p.getIfUnique());
    }
    private static void traceNarrow(int in, int out, boolean failSoft, String reason, String source) {
        TraceStore.put("overdrive.anchor.error", failSoft ? reason : "");
    }
}
"""

SIGNAL_OK = """public class ChatStreamSignalBuilder {
    private static String anchorStatus(java.util.Map<String,Object> meta, boolean complete) {
        if (truthy(value(meta, "overdrive.narrow.failSoft"))) return "warn";
        if (truthy(value(meta, "overdrive.activated"))) return "done";
        return complete ? "skipped" : "queued";
    }
}
"""

TRACE_OK = """public final class TraceLogger {
    public static double sample = parseDoubleProperty(System.getProperty("lms.trace.sample", "1.0"), 1.0d);
}
"""

TRACE_BRIDGE = """package com.example.lms.trace;
import org.springframework.core.env.Environment;
public class TraceLoggerEnvBridge {
    public TraceLoggerEnvBridge(Environment env) {
        TraceLogger.sample = Double.parseDouble(env.getProperty("lms.trace.sample", "1.0"));
    }
}
"""

INGEST_OK = """public class ConversationArchiveIngestService {
    void run(java.util.List<C> chunks) {
        for (C c : chunks) {
            if (vs.enqueueWithReceipt(c.id(), c.sid(), c.text(), c.meta()).accepted()) n++;
        }
    }
}
"""

DBQ_OK = """public class MetaDisplayDbQueryController {
    private Connection readOnlyConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        try { c.setReadOnly(true); } catch (SQLException e) { c.close(); throw e; }
        return c;
    }
}
"""

SNAPSHOT_EMPTY = {"schemaVersion": "awx.foreign-hunk-preserve.v1",
                  "files": []}


def xml_for(fqcn: str, fail: bool = False) -> str:
    case = ('<testcase name="works" classname="{}"><failure m="x"/></testcase>'
            if fail else '<testcase name="works" classname="{}"/>')
    return ('<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="s" tests="1" failures="{}">{}</testsuite>'
            .format(1 if fail else 0, case.format(fqcn)))


def make_root(fixed: bool = True, chat_body: bytes = b"chat-js-body") -> Path:
    td = Path(tempfile.mkdtemp(prefix="p5verify_"))
    files = {
        probe.F_SPEC: SPEC_OK if fixed else SPEC_OK
            .replace('String.valueOf(v).startsWith("missing_")', 'false')
            .replace('StageStatus.SKIPPED_DEPENDENCY', 'StageStatus.DECLARED'),
        probe.F_ORCH: ORCH_OK,
        probe.F_NARROW: NARROW_OK,
        probe.F_SIGNAL: SIGNAL_OK,
        probe.F_TRACELOG: TRACE_OK,
        "main/java/com/example/lms/trace/TraceLoggerEnvBridge.java": TRACE_BRIDGE,
        probe.F_INGEST: INGEST_OK,
        probe.F_DBQ: DBQ_OK,
        "main/resources/static/js/chat.js": chat_body,
        "main/resources/application.properties":
            "chat.settings.routing.enabled=false\n",
    }
    for rel, content in files.items():
        fp = td / rel
        fp.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            fp.write_bytes(content)
        else:
            fp.write_text(content, encoding="utf-8")
    return td


def sha8(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()[:8]


def write_xmls(root: Path, fail_class: str | None = None,
               classes: list[str] | None = None) -> str:
    xd = root / "xresults"
    xd.mkdir(parents=True, exist_ok=True)
    for i, fqcn in enumerate(classes or verify.REQUIRED_CLASSES):
        (xd / f"TEST-{i}.xml").write_text(
            xml_for(fqcn, fail=(fqcn == fail_class)), encoding="utf-8")
    return "xresults"


def write_snapshot(root: Path, payload: dict) -> str:
    fp = root / "snap.json"
    fp.write_text(json.dumps(payload), encoding="utf-8")
    return str(fp)


class VerifyRunnerTests(unittest.TestCase):

    def test_ready_for_review(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, [xd], snap, sha8(body))
        self.assertEqual(res["verdict"], "READY_FOR_REVIEW", res)
        self.assertFalse(res["incompleteReasons"])
        self.assertFalse(res["notReadyReasons"])

    def test_not_ready_class_fail(self):
        r = make_root()
        xd = write_xmls(r, fail_class=verify.REQUIRED_CLASSES[0])
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, [xd], snap, sha8(b"chat-js-body"))
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertTrue(any("class-fail" in x for x in res["notReadyReasons"]))

    def test_incomplete_missing_xml_dir(self):
        r = make_root()
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, ["no-such-dir"], snap,
                                      sha8(b"chat-js-body"))
        self.assertEqual(res["verdict"], "INCOMPLETE_EVIDENCE")
        self.assertTrue(any("xml-dir-missing" in x
                            for x in res["incompleteReasons"]))
        self.assertTrue(any("class-missing" in x
                            for x in res["incompleteReasons"]))

    def test_incomplete_no_snapshot(self):
        r = make_root()
        xd = write_xmls(r)
        res = verify.run_verification(r, [xd], None, sha8(b"chat-js-body"))
        self.assertEqual(res["verdict"], "INCOMPLETE_EVIDENCE")
        self.assertIn("foreign-snapshot-not-provided",
                      res["incompleteReasons"])

    def test_not_ready_probe_still_present(self):
        body = b"chat-js-body"
        r = make_root(fixed=False, chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, [xd], snap, sha8(body))
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertTrue(any(x.startswith("probe-P1-still-present")
                            for x in res["notReadyReasons"]))

    def test_not_ready_nul_found(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        (r / probe.F_ORCH).write_bytes(ORCH_OK.encode("utf-8") + b"\x00\n")
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, [xd], snap, sha8(body))
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertTrue(any(x.startswith("nul-found:")
                            for x in res["notReadyReasons"]))

    def test_not_ready_chatjs_mismatch(self):
        r = make_root()
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, [xd], snap, "ffffffff")
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertIn("chatjs-sha-mismatch", res["notReadyReasons"])

    def test_not_ready_routing_true(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        (r / "main/resources/application.properties").write_text(
            "chat.settings.routing.enabled=true\n", encoding="utf-8")
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        res = verify.run_verification(r, [xd], snap, sha8(body))
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertTrue(any("routing-flag-not-false" in x
                            for x in res["notReadyReasons"]))

    def test_incomplete_foreign_error(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, {"files": [{"path": "x.java", "hunks": [
            {"header": "@@ -1 +1 @@", "added": ["0" * 64], "deleted": []}]}]})
        res = verify.run_verification(r, [xd], snap, sha8(body))
        self.assertEqual(res["verdict"], "INCOMPLETE_EVIDENCE")
        self.assertIn("foreign-check-error", res["incompleteReasons"])

    def _lost_foreign(self):
        return {"run": "done", "exit": 4, "files": 1,
                "lost": [{"path": "main/java/x/Foo.java",
                          "header": "@@ -2413,0 +2898 @@",
                          "missingAdded": 1, "missingDeleted": 0}]}

    def test_allow_lost_hunk_ready(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        with unittest.mock.patch.object(
                verify, "foreign_step", return_value=self._lost_foreign()):
            res = verify.run_verification(
                r, [xd], snap, sha8(body),
                ["main/java/x/Foo.java:@@ -2413,0 +2898 @@"])
        self.assertEqual(res["verdict"], "READY_FOR_REVIEW", res)
        self.assertEqual(res["foreignHunks"]["intendedReplacements"][0]["header"],
                         "@@ -2413,0 +2898 @@")
        self.assertFalse(res["foreignHunks"]["lost"])

    def test_allow_lost_hunk_absent_stays_not_ready(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        with unittest.mock.patch.object(
                verify, "foreign_step", return_value=self._lost_foreign()):
            res = verify.run_verification(r, [xd], snap, sha8(body))
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertTrue(any("foreign-hunk-lost" in x
                            for x in res["notReadyReasons"]))
        self.assertEqual(len(res["foreignHunks"]["intendedReplacements"]), 0)

    def test_allow_lost_hunk_wrong_header_still_blocks(self):
        body = b"chat-js-body"
        r = make_root(chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        with unittest.mock.patch.object(
                verify, "foreign_step", return_value=self._lost_foreign()):
            res = verify.run_verification(
                r, [xd], snap, sha8(body),
                ["main/java/x/Foo.java:@@ -9999,0 +1 @@"])
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertTrue(any("foreign-hunk-lost" in x
                            for x in res["notReadyReasons"]))
        self.assertEqual(len(res["foreignHunks"]["lost"]), 1)

    def test_cli_json_exit3_on_not_ready(self):
        body = b"chat-js-body"
        r = make_root(fixed=False, chat_body=body)
        xd = write_xmls(r)
        snap = write_snapshot(r, SNAPSHOT_EMPTY)
        proc = subprocess.run(
            [sys.executable, "-B", str(RUNNER), "--root", str(r),
             "--xml-dir", xd, "--snapshot", snap,
             "--expect-chatjs-sha8", sha8(body), "--json"],
            capture_output=True, timeout=300)
        self.assertEqual(proc.returncode, 3,
                         proc.stderr.decode("utf-8", "replace"))
        payload = json.loads(proc.stdout.decode("utf-8").strip()
                             .splitlines()[-1])
        self.assertEqual(payload["schemaVersion"],
                         "awx.plan5-core-truth-verify.v1")
        self.assertEqual(payload["verdict"], "NOT_READY")


if __name__ == "__main__":
    unittest.main()
