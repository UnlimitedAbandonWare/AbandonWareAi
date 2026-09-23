package com.example.lms.service.rag.handler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.lms.search.TraceStore;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRetrievalHandlerChainTraceKeyRedactionTest {

    @Test
    void normalizeStageNamePreservesStableInternalLabels() throws Exception {
        assertEquals("selfask.lane", normalizeStageName("selfask.lane"));
        assertEquals("retrieval-stage", normalizeStageName("Retrieval-Stage"));
    }

    @Test
    void normalizeStageNameDoesNotPromoteRawSensitiveFreeFormLabels() throws Exception {
        String rawStage = "ownertoken " + "sk-" + "12345678901234567890";

        String normalized = normalizeStageName(rawStage);

        assertFalse(normalized.contains("ownertoken"), normalized);
        assertFalse(normalized.contains("sk-" + "12345678901234567890"), normalized);
        assertTrue(normalized.startsWith("hash_"), normalized);
    }

    @Test
    void dynamicRetrievalHandlerChainDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "retrieval handler chain fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void dynamicRetrievalHandlerEarlyFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertDynamicChainStage(source, "session.metadata");
        assertDynamicChainStage(source, "memory.load");
        assertDynamicChainStage(source, "alias.correct");
        assertDynamicChainStage(source, "effectiveQuery.rebuild");
        assertDynamicChainStage(source, "resourceHints");
        assertDynamicChainStage(source, "kplan.intentMetadata");
        assertDynamicChainStage(source, "kalloc.plan");
        assertDynamicChainStage(source, "loreNer.gateMetadata");
        assertDynamicChainStage(source, "lore.nerInject");
        assertDynamicChainStage(source, "selfAsk.gate");
        assertDynamicChainStage(source, "selfAsk.gateMetadata");
        assertDynamicChainStage(source, "selfAsk.retrieve");
        assertDynamicChainStage(source, "analyze.gate");
        assertDynamicChainStage(source, "analyze.gateMetadata");
        assertDynamicChainStage(source, "analyze.retrieve");
        assertDynamicChainStage(source, "web.adaptive");
        assertDynamicChainStage(source, "order.decide");
        assertDynamicChainStage(source, "retrieval.orderOverride");
        assertDynamicChainStage(source, "retrieval.allowSwitches");
        assertDynamicChainStage(source, "order.sse");
        assertDynamicChainStage(source, "thumbnail.recall");
        assertDynamicChainStage(source, "thumbnail.entities.metadata");
        assertDynamicChainStage(source, "thumbnail.entities.trace");
        assertDynamicChainStage(source, "thumbnail.seenText");
        assertDynamicChainStage(source, "ocr.seenText");
        assertDynamicChainStage(source, "ocr.retrieve");
        assertDynamicChainStage(source, "vector.seenText");
        assertDynamicChainStage(source, "source.retrieve");
        assertDynamicChainStage(source, "riskDecel.sse");
        assertDynamicChainStage(source, "riskDecel.compute");
        assertDynamicChainStage(source, "fusionApplied.sse");
        assertDynamicChainStage(source, "repair.retrieve");
        assertDynamicChainStage(source, "cfvm.sampleTrace");
        assertDynamicChainStage(source, "recovery.signals");
        assertDynamicChainStage(source, "recovery.future");
        assertDynamicChainStage(source, "recovery.futureTimeout");
        assertDynamicChainStage(source, "recovery.futureJoin");
        assertDynamicChainStage(source, "recovery.cancelTrace");
        assertDynamicChainStage(source, "recovery.annotateDoc");
        assertDynamicChainStage(source, "recovery.event");
        assertDynamicChainStage(source, "query.text");
        assertDynamicChainStage(source, "traceLong");
        assertDynamicChainStage(source, "traceDouble");
        assertDynamicChainStage(source, "zero100.consensusLane");
        assertDynamicChainStage(source, "zero100.laneMultiplier");
        assertDynamicChainStage(source, "traceInt");
        assertDynamicChainStage(source, "traceDouble.default");
        assertDynamicChainStage(source, "sigmoid.promptGate");
        assertDynamicChainStage(source, "sigmoid.tailProbe");
        assertDynamicChainStage(source, "lane.metadata");
        assertDynamicChainStage(source, "blackboxPrior.trace");
        assertDynamicChainStage(source, "branchQuality.trace");
        assertDynamicChainStage(source, "fusion.scorecard");
        assertDynamicChainStage(source, "ragEvent.trace");
        assertDynamicChainStage(source, "debugEvent.emit");
        assertDynamicChainStage(source, "metaInt.parse");
        assertDynamicChainStage(source, "metaDouble.parse");
        assertDynamicChainStage(source, "hypernovaPlan.trace");
        assertDynamicChainStage(source, "rewriteRisk.trace");
        assertDynamicChainStage(source, "queryComplexity.assess");
        assertDynamicChainStage(source, "cfvm.kalloc.metadata");
        assertDynamicChainStage(source, "faultMask.record");
        assertTrue(source.contains("RetrievalHandlerTraceSuppressions.traceSuppressed(log, \"DynamicRetrievalHandlerChain\""));
    }

    @Test
    void dynamicRetrievalHandlerTracesPlannedStepCountForCfvmMetrics() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertTrue(source.contains("TraceStore.put(\"chain.steps.planned\", ExecutionPlan.DEFAULT_STAGES.size())"));
    }

    @Test
    void retrievalStageFailureRecordsDrhcHandlerBreadcrumbs() {
        TraceStore.clear();
        DynamicRetrievalHandlerChain chain = chain();

        ReflectionTestUtils.invokeMethod(chain, "recordRetrievalStage",
                "KG", true, 0, new IllegalStateException("ownerToken raw-secret"), true);

        assertEquals(Boolean.TRUE, TraceStore.get("drhc.handler.kg.failed"));
        assertEquals("silent-failure", TraceStore.get("drhc.handler.kg.failureClass"));
        assertEquals(1L, TraceStore.getLong("drhc.execute.stagesAttempted"));
        assertEquals(0L, TraceStore.getLong("drhc.execute.stagesSucceeded"));
        assertEquals(1L, TraceStore.getLong("chain.steps.executed"));
        assertEquals(1L, TraceStore.getLong("chain.steps.failed"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"));
        assertFalse(trace.contains("raw-secret"));
        TraceStore.clear();
    }

    @Test
    void toMapDropsCredentialAndRawContentMetadataKeys() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("score", 0.88d);
        raw.put("source", "web");
        raw.put("apiKey", "api-key-must-not-leak");
        raw.put("ownerToken", "owner-token-must-not-leak");
        raw.put("rawQuery", "raw query must not leak");
        raw.put("content", "raw content must not leak");

        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "toMap", raw);

        assertEquals(0.88d, (Double) mapped.get("score"), 1.0e-9d);
        assertEquals("web", mapped.get("source"));
        String publicMeta = String.valueOf(mapped);
        assertFalse(publicMeta.contains("apiKey"), publicMeta);
        assertFalse(publicMeta.contains("ownerToken"), publicMeta);
        assertFalse(publicMeta.contains("rawQuery"), publicMeta);
        assertFalse(publicMeta.contains("content"), publicMeta);
        assertFalse(publicMeta.contains("api-key-must-not-leak"), publicMeta);
        assertFalse(publicMeta.contains("owner-token-must-not-leak"), publicMeta);
        assertFalse(publicMeta.contains("raw query must not leak"), publicMeta);
        assertFalse(publicMeta.contains("raw content must not leak"), publicMeta);
    }

    private static void assertDynamicChainStage(String source, String stage) {
        assertTrue(source.contains("traceSuppressed(log, \"DynamicRetrievalHandlerChain\", \"" + stage + "\""),
                "DynamicRetrievalHandlerChain fail-soft path needs stage breadcrumb: " + stage);
    }

    private static String normalizeStageName(String value) throws Exception {
        Method method = DynamicRetrievalHandlerChain.class.getDeclaredMethod("normalizeStageName", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static DynamicRetrievalHandlerChain chain() {
        return new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
    }
}
