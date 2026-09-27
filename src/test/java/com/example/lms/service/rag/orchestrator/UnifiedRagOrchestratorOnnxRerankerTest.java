package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRagOrchestratorOnnxRerankerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void onnxStageInvokesInjectedRerankerAndUsesItsOrder() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        RecordingReranker onnx = new RecordingReranker();
        ReflectionTestUtils.setField(orchestrator, "onnxReranker", onnx);
        assertSame(onnx, ReflectionTestUtils.getField(orchestrator, "onnxReranker"));

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "onnx stage query";
        request.seedCandidates = List.of(doc("alpha", 1), doc("beta", 2), doc("gamma", 3));
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = true;
        request.topK = 2;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, onnx.calls,
                () -> "debug=" + response.debug + " trace=" + TraceStore.getAll());
        assertEquals("onnx stage query", onnx.query);
        assertEquals(2, onnx.topN);
        // The reranker evaluates the wider fused candidate window (3 seeds),
        // not a list pre-cut to the public topK — this is what lets it promote
        // 'gamma' from beyond the final cut.
        assertEquals(3, onnx.candidateTexts.size());
        assertEquals(List.of("gamma", "beta"),
                response.results.stream().map(doc -> doc.id).toList());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.requested"));
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.executed"));
        assertEquals(3, TraceStore.get("rerank.onnx.input.count"));
        assertEquals(2, TraceStore.get("rerank.onnx.output.count"));
        assertEquals(null, TraceStore.get("rerank.onnx.skipReason"));
        assertEquals("ok", onnxEvent().get("status"));
    }

    @Test
    void onnxStageWithoutBeanRecordsSkippedAndPreservesOrder() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "onnx missing dependency query";
        request.seedCandidates = List.of(doc("alpha", 1), doc("beta", 2), doc("gamma", 3));
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = true;
        request.topK = 2;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(List.of("alpha", "beta"), response.results.stream().map(doc -> doc.id).toList());
        assertEquals("skipped:missing_dependency", response.debug.get("stage.onnx"));
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.requested"));
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.executed"));
        assertEquals(3, TraceStore.get("rerank.onnx.input.count"));
        assertEquals(2, TraceStore.get("rerank.onnx.output.count"));
        assertEquals("missing_dependency", TraceStore.get("rerank.onnx.skipReason"));
        Map<String, Object> event = onnxEvent();
        assertEquals("fallback", event.get("status"));
        assertEquals("missing_dependency", nested(event, "failure").get("reasonCode"));
    }

    @Test
    void onnxDisabledClearsCompatibilityTelemetry() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        TraceStore.put("rerank.onnx.orchestrator.executed", true);
        TraceStore.put("rerank.onnx.orchestrator.candidateCount", 9);
        TraceStore.put("rerank.onnx.orchestrator.selectedCount", 9);
        TraceStore.put("rerank.onnx.orchestrator.failureClass", "onnx_rerank_failed");

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "onnx disabled query";
        request.seedCandidates = List.of(doc("alpha", 1), doc("beta", 2));
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        request.topK = 2;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(List.of("alpha", "beta"), response.results.stream().map(doc -> doc.id).toList());
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.requested"));
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.executed"));
        assertEquals(0, TraceStore.get("rerank.onnx.input.count"));
        assertEquals(0, TraceStore.get("rerank.onnx.output.count"));
        assertEquals("disabled", TraceStore.get("rerank.onnx.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.orchestrator.executed"));
        assertEquals(0, TraceStore.get("rerank.onnx.orchestrator.candidateCount"));
        assertEquals(0, TraceStore.get("rerank.onnx.orchestrator.selectedCount"));
        assertEquals(null, TraceStore.get("rerank.onnx.orchestrator.failureClass"));
    }

    @Test
    void onnxStageNormalizesCancellationFailureClass() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "onnxReranker", new CancellingReranker());

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "onnx cancellation secret query";
        request.seedCandidates = List.of(doc("alpha", 1), doc("beta", 2));
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = true;
        request.topK = 2;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals("cancelled", TraceStore.get("rerank.onnx.orchestrator.failureClass"));
        assertEquals("cancelled", response.debug.get("stage.onnx.failureClass"));
        assertEquals(List.of("alpha", "beta"), response.results.stream().map(doc -> doc.id).toList());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.requested"));
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.executed"));
        assertEquals("cancelled", TraceStore.get("rerank.onnx.skipReason"));
        assertEquals("error", onnxEvent().get("status"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertFalse(String.valueOf(response.debug).contains("ownerToken"));
        assertFalse(String.valueOf(response.debug).contains("onnx cancellation secret query"));
    }

    @Test
    void onnxStageNormalizesNonCancellationFailureClass() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "onnxReranker", new FailingReranker());

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "onnx noncancel secret query";
        request.seedCandidates = List.of(doc("alpha", 1), doc("beta", 2));
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = true;
        request.topK = 2;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals("onnx_rerank_failed", TraceStore.get("rerank.onnx.orchestrator.failureClass"));
        assertEquals("onnx_rerank_failed", response.debug.get("stage.onnx.failureClass"));
        assertEquals(List.of("alpha", "beta"), response.results.stream().map(doc -> doc.id).toList());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.requested"));
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.executed"));
        assertEquals(2, TraceStore.get("rerank.onnx.input.count"));
        assertEquals(2, TraceStore.get("rerank.onnx.output.count"));
        assertEquals("onnx_rerank_failed", TraceStore.get("rerank.onnx.skipReason"));
        Map<String, Object> event = onnxEvent();
        assertEquals("error", event.get("status"));
        assertEquals("onnx_rerank_failed", nested(event, "failure").get("failureClass"));
        assertEquals("fail_soft_fallback", nested(event, "control").get("action"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("RuntimeException"));
        assertFalse(String.valueOf(response.debug).contains("RuntimeException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertFalse(String.valueOf(response.debug).contains("ownerToken"));
        assertFalse(String.valueOf(response.debug).contains("onnx noncancel secret query"));
    }

    private static UnifiedRagOrchestrator.Doc doc(String id, int rank) {
        UnifiedRagOrchestrator.Doc doc = new UnifiedRagOrchestrator.Doc();
        doc.id = id;
        doc.title = id;
        doc.snippet = "evidence " + id;
        doc.source = "WEB";
        doc.rank = rank;
        doc.score = 1.0d / rank;
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onnxEvent() {
        Object events = TraceStore.get("orch.events.v1");
        assertTrue(events instanceof List<?>);
        return ((List<?>) events).stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .filter(row -> "rag.pipeline".equals(row.get("kind")))
                .filter(row -> "rerank".equals(row.get("phase")))
                .filter(row -> "onnx".equals(row.get("stage")))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> event, String key) {
        Object value = event.get(key);
        assertTrue(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }

    private static final class RecordingReranker implements CrossEncoderReranker {
        int calls;
        String query;
        int topN;
        List<String> candidateTexts = List.of();

        @Override
        public List<Content> rerank(String query, List<Content> candidates, int topN) {
            this.calls++;
            this.query = query;
            this.topN = topN;
            this.candidateTexts = candidates == null
                    ? List.of()
                    : candidates.stream()
                            .map(content -> content.textSegment() == null ? "" : content.textSegment().text())
                            .toList();
            List<Content> out = new ArrayList<>(candidates == null ? List.of() : candidates);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    private static final class CancellingReranker implements CrossEncoderReranker {
        @Override
        public List<Content> rerank(String query, List<Content> candidates, int topN) {
            throw new CancellationException("cancelled ownerToken fake-token");
        }
    }

    private static final class FailingReranker implements CrossEncoderReranker {
        @Override
        public List<Content> rerank(String query, List<Content> candidates, int topN) {
            throw new RuntimeException("onnx backend unavailable ownerToken fake-token");
        }
    }
}
