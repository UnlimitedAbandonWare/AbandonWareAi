#!/usr/bin/env python3
"""Tests for scripts/plan5_core_truth_probe.py — fake Java fragments only."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "scripts" / "plan5_core_truth_probe.py"

sys.path.insert(0, str(ROOT))
from scripts import plan5_core_truth_probe as probe  # noqa: E402


SPEC_BASE = """package com.example.lms.plan;
public class PlanExecutionSpec {
    private static StageEntry resolveStage(String stage, Binding b,
                                           java.util.Map<String,Object> ev, StageFlags f) {
        java.util.Map<String,Object> dbg = ev == null ? java.util.Map.of() : ev;
        return switch (b) {
            case SELF_ASK -> { yield new StageEntry(stage, StageStatus.DECLARED, "", "x"); }
            case RETRIEVAL -> {
                for (String k : java.util.List.of("stage.web", "stage.vector", "stage.kg", "stage.bm25")) {
                    Object v = dbg.get(k);
                    if (v != null && !"disabled".equals(v)) {
                        yield new StageEntry(stage, StageStatus.EXECUTED, k, "");
                    }
                }
                yield new StageEntry(stage, StageStatus.DECLARED, "", "no_retrieval_marker");
            }
            default -> new StageEntry(stage, StageStatus.UNAVAILABLE, "", "no_binding");
        };
    }
}
"""

SPEC_FIXED = SPEC_BASE.replace(
    'if (v != null && !"disabled".equals(v)) {',
    'if (v != null && !"disabled".equals(v) '
    '&& !String.valueOf(v).startsWith("missing_")) {'
)

SPEC_FIXED_DEP = SPEC_BASE.replace(
    'yield new StageEntry(stage, StageStatus.DECLARED, "", "no_retrieval_marker");',
    'yield new StageEntry(stage, StageStatus.SKIPPED_DEPENDENCY, "", "no_retrieval_marker");'
)

ORCH_BASE = """package com.example.lms.service.rag.orchestrator;
public class UnifiedRagOrchestrator {
    private java.util.List<Doc> toDocsOrEmpty(Bm25Index index, String query, int topK, String sourceTag) {
        if (index == null) { return java.util.List.of(); }
        try {
            return index.searchDocs(query, topK);
        } catch (Exception e) {
            log.warn("source retrieval failed failureReason={}", "source-retrieval-error");
            return java.util.List.of();
        }
    }
    void leg(Request req, java.util.Map<String,Object> dbg, java.util.List<Doc> pool) {
        if (bm25Index == null) {
            dbg.putIfAbsent("stage.bm25", "missing_bm25Index");
        }
        if (req.useBm25 && bm25Index != null) {
            java.util.List<Doc> bm25Docs = toDocsOrEmpty(bm25Index, req.query, req.topK, "BM25");
            if (bm25Docs.isEmpty()) {
                dbg.put("stage.bm25", "empty");
            } else {
                dbg.put("stage.bm25", "ok:" + bm25Docs.size());
                pool.addAll(bm25Docs);
            }
        }
    }
}
"""

ORCH_FIXED = ORCH_BASE.replace(
    'java.util.List<Doc> bm25Docs = toDocsOrEmpty(bm25Index, req.query, req.topK, "BM25");',
    'Bm25Result r = toDocsOrEmpty2(bm25Index, req.query, req.topK, "BM25");\n'
    '            java.util.List<Doc> bm25Docs = r.docs();\n'
    '            if (r.failed()) { dbg.put("stage.bm25", "failed:" + r.reason()); continue; }'
)

NARROW_BASE = """package com.example.lms.service.rag.overdrive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
public class AngerOverdriveNarrower {
    private final CrossEncoderReranker reranker;
    @Autowired
    public AngerOverdriveNarrower(ObjectProvider<CrossEncoderReranker> rerankerProvider) {
        this(resolveReranker(rerankerProvider));
    }
    public AngerOverdriveNarrower(@Qualifier("crossEncoderReranker") CrossEncoderReranker reranker) {
        this.reranker = reranker;
    }
    private static void traceNarrow(int inputCount, int outputCount, boolean failSoft,
                                    String reason, String source) {
        String safeReason = reason;
        TraceStore.put("overdrive.anchor.error", safeReason);
        TraceStore.put("overdrive.anchor.skipReason", failSoft ? safeReason : "");
    }
}
"""

NARROW_FIXED_P3 = NARROW_BASE.replace(
    "public AngerOverdriveNarrower(ObjectProvider<CrossEncoderReranker> rerankerProvider)",
    'public AngerOverdriveNarrower(@Qualifier("crossEncoderReranker") '
    'ObjectProvider<CrossEncoderReranker> rerankerProvider)'
)

NARROW_FIXED_P4 = NARROW_BASE.replace(
    'TraceStore.put("overdrive.anchor.error", safeReason);',
    'TraceStore.put("overdrive.anchor.error", failSoft ? safeReason : "");'
)

NARROW_FIXED_P4B = NARROW_BASE.replace(
    'TraceStore.put("overdrive.anchor.error", safeReason);',
    'if (failSoft) {\n            TraceStore.put("overdrive.anchor.error", safeReason);\n        }'
)

SIGNAL_BASE = """package com.example.lms.api;
public class ChatStreamSignalBuilder {
    private static String anchorStatus(java.util.Map<String, Object> meta, boolean complete) {
        if (truthy(value(meta, "overdrive.activated"))) {
            return "done";
        }
        if (firstNonBlank(safeString(value(meta, "overdrive.anchor.error"))) != null) {
            return "warn";
        }
        return complete ? "skipped" : "queued";
    }
}
"""

SIGNAL_FIXED = SIGNAL_BASE.replace(
    'if (truthy(value(meta, "overdrive.activated"))) {',
    'boolean failSoft = truthy(value(meta, "overdrive.narrow.failSoft"));\n'
    '        if (failSoft) { return "warn"; }\n'
    '        if (truthy(value(meta, "overdrive.activated"))) {'
)

TRACE_BASE = """package com.example.lms.trace;
public final class TraceLogger {
    public static boolean enabled = Boolean.parseBoolean(System.getProperty("lms.trace.enabled", "true"));
    public static double sample = parseDoubleProperty(System.getProperty("lms.trace.sample", "1.0"), 1.0d);
    public static int PREVIEW = parseIntProperty(System.getProperty("lms.trace.preview", "240"), 240);
}
"""

TRACE_FIXED = TRACE_BASE + """
class TraceLoggerEnvBridge {
    TraceLoggerEnvBridge(org.springframework.core.env.Environment env) {
        TraceLogger.sample = Double.parseDouble(env.getProperty("lms.trace.sample", "1.0"));
        TraceLogger.enabled = Boolean.parseBoolean(env.getProperty("lms.trace.enabled", "true"));
    }
}
"""

INGEST_BASE = """package com.example.lms.conversation.archive;
public class ConversationArchiveIngestService {
    void run(java.util.List<StagedChunk> stagedChunks, Stats stats) {
        for (StagedChunk chunk : stagedChunks) {
            vectorStoreService.enqueue(chunk.id(), chunk.sessionId(), chunk.text(), chunk.metadata());
            stats.ingestedCount++;
        }
    }
}
"""

INGEST_FIXED = INGEST_BASE.replace(
    "vectorStoreService.enqueue(chunk.id(), chunk.sessionId(), chunk.text(), chunk.metadata());\n            stats.ingestedCount++;",
    "if (vectorStoreService.enqueueWithReceipt(chunk.id(), chunk.sessionId(), chunk.text(), chunk.metadata()).accepted()) {\n"
    "                stats.ingestedCount++;\n            }"
)

DBQ_BASE = """package com.example.lms.api;
import java.sql.Connection;
import java.sql.SQLException;
public class MetaDisplayDbQueryController {
    private Connection readOnlyConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setReadOnly(true);
        return c;
    }
}
"""

DBQ_FIXED = DBQ_BASE.replace(
    "Connection c = dataSource.getConnection();\n        c.setReadOnly(true);\n        return c;",
    "Connection c = dataSource.getConnection();\n"
    "        try {\n            c.setReadOnly(true);\n        } catch (SQLException e) {\n"
    "            c.close();\n            throw e;\n        }\n        return c;"
)


def make_root(files: dict[str, str | bytes]) -> Path:
    td = Path(tempfile.mkdtemp(prefix="p5probe_"))
    for rel, content in files.items():
        fp = td / rel
        fp.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            fp.write_bytes(content)
        else:
            fp.write_text(content, encoding="utf-8")
    return td


def run_probe(root: Path) -> dict:
    return probe.probe_all(root)


def status_of(result: dict, item_id: str) -> str:
    for it in result["items"]:
        if it["id"] == item_id:
            return it["status"]
    return "MISSING"


class ProbeItemTests(unittest.TestCase):

    def test_p1_still_present(self):
        r = make_root({probe.F_SPEC: SPEC_BASE})
        self.assertEqual(status_of(run_probe(r), "P1"), "STILL_PRESENT")

    def test_p1_fixed_missing_guard(self):
        r = make_root({probe.F_SPEC: SPEC_FIXED})
        self.assertEqual(status_of(run_probe(r), "P1"), "FIXED_HINT")

    def test_p1_fixed_skipped_dependency(self):
        r = make_root({probe.F_SPEC: SPEC_FIXED_DEP})
        self.assertEqual(status_of(run_probe(r), "P1"), "FIXED_HINT")

    def test_p1_unknown_no_case(self):
        r = make_root({probe.F_SPEC: "class PlanExecutionSpec {}\n"})
        self.assertEqual(status_of(run_probe(r), "P1"), "UNKNOWN")

    def test_p2_still_present(self):
        r = make_root({probe.F_ORCH: ORCH_BASE})
        self.assertEqual(status_of(run_probe(r), "P2"), "STILL_PRESENT")

    def test_p2_fixed_failed_marker(self):
        r = make_root({probe.F_ORCH: ORCH_FIXED})
        self.assertEqual(status_of(run_probe(r), "P2"), "FIXED_HINT")

    def test_p2_unknown_no_bm25(self):
        r = make_root({probe.F_ORCH: "class UnifiedRagOrchestrator {}\n"})
        self.assertEqual(status_of(run_probe(r), "P2"), "UNKNOWN")

    def test_p3_still_present(self):
        r = make_root({probe.F_NARROW: NARROW_BASE})
        self.assertEqual(status_of(run_probe(r), "P3"), "STILL_PRESENT")

    def test_p3_fixed_qualifier(self):
        r = make_root({probe.F_NARROW: NARROW_FIXED_P3})
        self.assertEqual(status_of(run_probe(r), "P3"), "FIXED_HINT")

    def test_p3_unknown_no_autowired(self):
        r = make_root({probe.F_NARROW: "class AngerOverdriveNarrower {\n"
                       "  AngerOverdriveNarrower() {}\n}\n"})
        self.assertEqual(status_of(run_probe(r), "P3"), "UNKNOWN")

    def test_p4_still_present(self):
        r = make_root({probe.F_NARROW: NARROW_BASE})
        self.assertEqual(status_of(run_probe(r), "P4"), "STILL_PRESENT")

    def test_p4_fixed_ternary(self):
        r = make_root({probe.F_NARROW: NARROW_FIXED_P4})
        self.assertEqual(status_of(run_probe(r), "P4"), "FIXED_HINT")

    def test_p4_fixed_if_guard(self):
        r = make_root({probe.F_NARROW: NARROW_FIXED_P4B})
        self.assertEqual(status_of(run_probe(r), "P4"), "FIXED_HINT")

    def test_p4_unknown_no_tracenarrow(self):
        r = make_root({probe.F_NARROW: "class AngerOverdriveNarrower {}\n"})
        self.assertEqual(status_of(run_probe(r), "P4"), "UNKNOWN")

    def test_p5_still_present(self):
        r = make_root({probe.F_SIGNAL: SIGNAL_BASE})
        self.assertEqual(status_of(run_probe(r), "P5"), "STILL_PRESENT")

    def test_p5_fixed_failsoft(self):
        r = make_root({probe.F_SIGNAL: SIGNAL_FIXED})
        self.assertEqual(status_of(run_probe(r), "P5"), "FIXED_HINT")

    def test_p5_unknown_no_method(self):
        r = make_root({probe.F_SIGNAL: "class ChatStreamSignalBuilder {}\n"})
        self.assertEqual(status_of(run_probe(r), "P5"), "UNKNOWN")

    def test_p6_still_present(self):
        r = make_root({probe.F_TRACELOG: TRACE_BASE})
        self.assertEqual(status_of(run_probe(r), "P6"), "STILL_PRESENT")

    def test_p6_fixed_external_applier(self):
        r = make_root({
            probe.F_TRACELOG: TRACE_BASE,
            "main/java/com/example/lms/trace/TraceLoggerEnvBridge.java":
                TRACE_FIXED.split("class TraceLoggerEnvBridge", 1)[1]
                .join(["class TraceLoggerEnvBridge", ""]),
        })
        self.assertEqual(status_of(run_probe(r), "P6"), "FIXED_HINT")

    def test_p6_unknown_no_reads(self):
        r = make_root({probe.F_TRACELOG: "final class TraceLogger {\n"
                       "  public static boolean enabled = true;\n}\n"})
        self.assertEqual(status_of(run_probe(r), "P6"), "UNKNOWN")

    def test_p7_still_present(self):
        r = make_root({probe.F_INGEST: INGEST_BASE})
        self.assertEqual(status_of(run_probe(r), "P7"), "STILL_PRESENT")

    def test_p7_fixed_receipt(self):
        r = make_root({probe.F_INGEST: INGEST_FIXED})
        self.assertEqual(status_of(run_probe(r), "P7"), "FIXED_HINT")

    def test_p7_unknown_no_enqueue(self):
        r = make_root({probe.F_INGEST: "class ConversationArchiveIngestService {}\n"})
        self.assertEqual(status_of(run_probe(r), "P7"), "UNKNOWN")

    def test_p8_still_present(self):
        r = make_root({probe.F_DBQ: DBQ_BASE})
        self.assertEqual(status_of(run_probe(r), "P8"), "STILL_PRESENT")

    def test_p8_fixed_catch_close(self):
        r = make_root({probe.F_DBQ: DBQ_FIXED})
        self.assertEqual(status_of(run_probe(r), "P8"), "FIXED_HINT")

    def test_p8_unknown_no_method(self):
        r = make_root({probe.F_DBQ: "class MetaDisplayDbQueryController {}\n"})
        self.assertEqual(status_of(run_probe(r), "P8"), "UNKNOWN")

    def test_p9_still_present(self):
        r = make_root({probe.F_ORCH: b"class X {\x00}\n"})
        res = run_probe(r)
        self.assertEqual(status_of(res, "P9"), "STILL_PRESENT")

    def test_p9_fixed_no_nul(self):
        r = make_root({probe.F_ORCH: "class UnifiedRagOrchestrator {}\n"})
        self.assertEqual(status_of(run_probe(r), "P9"), "FIXED_HINT")

    def test_p9_unknown_missing_file(self):
        r = make_root({})
        self.assertEqual(status_of(run_probe(r), "P9"), "UNKNOWN")


class ProbeCliTests(unittest.TestCase):

    def test_cli_json_and_schema(self):
        r = make_root({probe.F_SPEC: SPEC_BASE})
        proc = subprocess.run(
            [sys.executable, "-B", str(PROBE), "--root", str(r), "--json"],
            capture_output=True, timeout=60)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode("utf-8", "replace"))
        payload = json.loads(proc.stdout.decode("utf-8").strip().splitlines()[-1])
        self.assertEqual(payload["schemaVersion"], "awx.plan5-core-truth-probe.v1")
        self.assertEqual(payload["kind"], "STATIC_HINT")
        self.assertEqual(len(payload["items"]), 9)

    def test_cli_out_writes_file(self):
        r = make_root({probe.F_SPEC: SPEC_BASE})
        out = r / "out" / "probe.json"
        proc = subprocess.run(
            [sys.executable, "-B", str(PROBE), "--root", str(r),
             "--out", str(out)], capture_output=True, timeout=60)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode("utf-8", "replace"))
        self.assertTrue(out.is_file())
        payload = json.loads(out.read_text(encoding="utf-8"))
        self.assertIn("summary", payload)

    def test_cli_bad_root_exit2(self):
        proc = subprocess.run(
            [sys.executable, "-B", str(PROBE), "--root", "no/such/dir-zzz"],
            capture_output=True, timeout=60)
        self.assertEqual(proc.returncode, 2)

    def test_evidence_never_contains_code(self):
        r = make_root({probe.F_SPEC: SPEC_BASE})
        res = run_probe(r)
        blob = json.dumps(res, ensure_ascii=False)
        self.assertNotIn("yield", blob)
        self.assertNotIn("StageEntry(", blob)


if __name__ == "__main__":
    unittest.main()
