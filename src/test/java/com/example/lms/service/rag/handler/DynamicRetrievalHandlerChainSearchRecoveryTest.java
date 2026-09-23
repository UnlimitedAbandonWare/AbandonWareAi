package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.service.rag.knowledge.UniversalLoreRegistry;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRetrievalHandlerChainSearchRecoveryTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void zeroPrimaryResultsTriggerSearchRecoveryReason() {
        DynamicRetrievalHandlerChain chain = chain();

        String reason = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(),
                0,
                0,
                0,
                0);

        assertEquals("zero_result", reason);
    }

    @Test
    void afterFilterStarvationOverridesGenericZeroResult() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("web.naver.returnedCount", 3);
        TraceStore.put("web.naver.afterFilterCount", 0);

        String reason = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(),
                0,
                0,
                0,
                0);

        assertEquals("after_filter_starvation", reason);
    }

    @Test
    void tavilyTraceKeysTriggerSearchRecoveryReason() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("web.tavily.returnedCount", 3);
        TraceStore.put("web.tavily.afterFilterCount", 0);

        String starved = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(content("primary evidence", "doc-1")),
                1,
                0,
                0,
                0);

        assertEquals("after_filter_starvation", starved);

        TraceStore.clear();
        TraceStore.put("web.tavily.zeroResults", true);
        String zeroResult = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(content("primary evidence", "doc-1")),
                1,
                0,
                0,
                0);

        assertEquals("zero_result", zeroResult);
    }

    @Test
    void recoveryPayloadUsesRedactedQueryDiagnostics() {
        DynamicRetrievalHandlerChain chain = chain();
        @SuppressWarnings("unchecked")
        List<String> subqueries = ReflectionTestUtils.invokeMethod(
                chain,
                "recoverySubqueries",
                "raw ownerToken abc123 query",
                new Query("rewritten api_key should not leak"));

        assertTrue(subqueries.size() <= 4);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = ReflectionTestUtils.invokeMethod(
                chain,
                "recoveryEventPayload",
                "rag-recovery-1",
                "zero_result",
                "selfask_queryburst_parallel_web_vector_memory_history",
                List.of("web", "vector"),
                0,
                2,
                subqueries,
                2,
                "raw ownerToken abc123 query",
                "rewritten api_key should not leak");

        assertInstanceOf(Map.class, payload.get("query_original"));
        assertInstanceOf(Map.class, payload.get("query_rewritten"));
        assertFalse(String.valueOf(payload).contains("abc123"));
        assertFalse(String.valueOf(payload).contains("api_key should not leak"));
        assertEquals("zero_result", payload.get("zero_result_reason"));
        assertEquals(2, payload.get("recovered_count"));
    }

    @Test
    void recoveryBreadcrumbUsesRagPipelineContractAndRedactedPayload() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("rag.recovery.queryOriginalHash", "abc123hash");
        Map<String, Object> payload = Map.of(
                "zero_result_reason", "zero_result",
                "breadcrumb_id", "rag-recovery-1",
                "recovered_count", 2,
                "subquery_count", 1);

        ReflectionTestUtils.invokeMethod(chain, "emitRecoveryBreadcrumb", "done", payload, null);

        Map<?, ?> event = firstOrchEvent();
        assertEquals("rag.pipeline", event.get("kind"));
        assertEquals("recovery", event.get("phase"));
        assertEquals("search_failure", event.get("stage"));
        assertEquals("done", event.get("step"));
        assertEquals("ok", event.get("status"));
        assertEquals("DynamicRetrievalHandlerChain.runSearchFailureRecovery", event.get("component"));
        assertFalse(String.valueOf(event).contains("ownerToken"));

        Map<?, ?> input = assertInstanceOf(Map.class, event.get("input"));
        assertEquals("active_gated", input.get("mode"));
        assertEquals("abc123hash", input.get("queryHash"));
        Map<?, ?> failure = assertInstanceOf(Map.class, event.get("failure"));
        assertEquals("zero_result", failure.get("reasonCode"));
        Map<?, ?> control = assertInstanceOf(Map.class, event.get("control"));
        assertEquals("recovery", control.get("action"));
        assertEquals("rag-recovery-1", control.get("breadcrumbId"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(chain, "emitRecoveryBreadcrumb", "detect", payload,
                new IllegalStateException("raw ownerToken abc123"));

        Map<?, ?> errorEvent = firstOrchEvent();
        assertEquals("rag.pipeline", errorEvent.get("kind"));
        assertEquals("error", errorEvent.get("status"));
        assertFalse(String.valueOf(errorEvent).contains("raw ownerToken abc123"));
    }

    @Test
    void recoveryTimeoutCancelsWithoutInterruptingWorker() throws Exception {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "searchFailureRecoveryTimeoutMs", 50L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CompletableFuture<?> future = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            return null;
        }, executor);

        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            ReflectionTestUtils.invokeMethod(
                    chain,
                    "collectRecoveryBuckets",
                    List.of(future),
                    "rag-recovery-timeout",
                    "selfask_queryburst_parallel_web_vector_memory_history");

            assertTrue(future.isCancelled());
            assertEquals("no_interrupt", TraceStore.get("rag.recovery.cancelMode"));
            assertEquals("future_timeout", TraceStore.get("rag.recovery.timeout.reason"));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fusionScorecardRecordsCountsAndKgRetentionWithoutContentLeakage() {
        DynamicRetrievalHandlerChain chain = chain();
        Content web = content("web public evidence", "web-1");
        Content kg = content("kg private evidence", "kg-1");

        ReflectionTestUtils.invokeMethod(
                chain,
                "traceFusionScorecard",
                List.of(web),
                List.of(),
                List.of(kg),
                List.of(),
                List.of(kg));

        Map<?, ?> scorecard = assertInstanceOf(Map.class, TraceStore.get("rag.fusion.scorecard"));
        assertEquals(1, scorecard.get("web"));
        assertEquals(0, scorecard.get("vector"));
        assertEquals(1, scorecard.get("kg"));
        assertEquals(0, scorecard.get("selfAsk"));
        assertEquals(2, scorecard.get("inputTotal"));
        assertEquals(1, scorecard.get("fusedCount"));
        assertEquals(0.5d, number(scorecard.get("kgInputShare")), 1e-9);
        assertEquals(1, scorecard.get("kgRetainedCount"));
        assertEquals(1.0d, number(scorecard.get("kgRetainedShare")), 1e-9);
        assertEquals(0, TraceStore.get("rag.fusion.sizes.selfask"));
        assertEquals(1, TraceStore.get("rag.fusion.sizes.web"));
        assertEquals(0, TraceStore.get("rag.fusion.sizes.vector"));
        assertEquals(1, TraceStore.get("rag.fusion.sizes.kg"));
        assertEquals(1.0d, number(TraceStore.get("rag.fusion.weights.kg")), 1e-9);
        assertEquals(1, TraceStore.get("rag.fusion.final.kgCount"));
        assertEquals(1, TraceStore.get("rag.fusion.final.totalCount"));
        assertFalse(String.valueOf(scorecard).contains("kg private evidence"));
        assertFalse(String.valueOf(scorecard).contains("web public evidence"));
    }

    @Test
    void fusionScorecardPreservesPerSourceScoreSignalsWithoutContentLeakage() {
        DynamicRetrievalHandlerChain chain = chain();
        Content web = content("web public evidence", "web-1", Map.of("score", 0.42d));
        Content vector = content("vector private evidence", "vector-1", Map.of("vector_score", 0.73d));
        Content kg = content("kg private evidence", "kg-1", Map.of("kg_score", 0.91d));

        ReflectionTestUtils.invokeMethod(
                chain,
                "traceFusionScorecard",
                List.of(web),
                List.of(vector),
                List.of(kg),
                List.of(),
                List.of(web, vector, kg));

        Map<?, ?> scorecard = assertInstanceOf(Map.class, TraceStore.get("rag.fusion.scorecard"));
        assertEquals(0.42d, number(scorecard.get("webScoreMean")), 1e-9);
        assertEquals(0.73d, number(scorecard.get("vectorScoreMean")), 1e-9);
        assertEquals(0.91d, number(scorecard.get("kgScoreMean")), 1e-9);
        assertEquals(0.42d, number(TraceStore.get("rag.fusion.score.mean.web")), 1e-9);
        assertEquals(0.73d, number(TraceStore.get("rag.fusion.score.mean.vector")), 1e-9);
        assertEquals(0.91d, number(TraceStore.get("rag.fusion.score.mean.kg")), 1e-9);
        assertFalse(String.valueOf(scorecard).contains("private evidence"));
    }

    @Test
    void fusionScoreTraceDropsNonFiniteScorecardValues() {
        Map<String, Object> scorecard = new java.util.LinkedHashMap<>();
        scorecard.put("webScoreMean", Double.POSITIVE_INFINITY);
        scorecard.put("vectorScoreMean", Double.NaN);
        scorecard.put("kgScoreMean", 0.91d);

        FusionScoreDiagnostics.traceScoreMeans(scorecard);

        assertEquals(0.0d, number(TraceStore.get("rag.fusion.score.mean.web")), 1e-9);
        assertEquals(0.0d, number(TraceStore.get("rag.fusion.score.mean.vector")), 1e-9);
        assertEquals(0.91d, number(TraceStore.get("rag.fusion.score.mean.kg")), 1e-9);
    }

    @Test
    void recoveryExecutorSourceUsesCancelShieldAndNoShutdownNow() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertTrue(source.contains("new ai.abandonware.nova.boot.exec.CancelShieldExecutorService"));
        assertTrue(source.contains("\"ragRecovery\""));
        assertTrue(source.contains("rag.recovery.executor.source"));
        assertFalse(source.contains("executor.shutdownNow()"));
    }

    @Test
    void dynamicRetrievalNumericParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertParserCatchNarrowed(source, "private static long traceLong(String key)");
        assertParserCatchNarrowed(source, "private static double traceDouble(String key)");
        assertParserCatchNarrowed(source, "private static double zero100LaneMultiplier(String lane)");
        assertParserCatchNarrowed(source, "private static int traceInt(String key, int defaultValue)");
        assertParserCatchNarrowed(source, "private static double traceDouble(String key, double defaultValue)");
        assertParserCatchNarrowed(source, "private static int metaInt(java.util.Map<String, Object> meta, String key, int def)");
        assertParserCatchNarrowed(source, "private static double metaDouble(java.util.Map<String, Object> meta, String key, double def)");
    }

    @Test
    void numericTraceAndMetadataHelpersDropNonFiniteNumbers() {
        TraceStore.put("chain.nonfinite", Double.POSITIVE_INFINITY);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "traceTruthy", "chain.nonfinite"));
        assertEquals(0L, (Long) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "traceLong", "chain.nonfinite"));
        assertEquals(0.0d, (Double) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "traceDouble", "chain.nonfinite"), 0.0d);

        Map<String, Object> meta = Map.of(
                "enabled", Double.POSITIVE_INFINITY,
                "topK", Double.NaN,
                "score", Double.NEGATIVE_INFINITY,
                "stringScore", "Infinity");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaBool", meta, "enabled", false));
        assertEquals(4, (Integer) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaInt", meta, "topK", 4));
        assertEquals(0.25d, (Double) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaDouble", meta, "score", 0.25d), 0.0d);
        assertEquals(0.25d, (Double) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaDouble", meta, "stringScore", 0.25d), 0.0d);
    }

    @Test
    void retrievalStageClassifiesCancellationSeparatelyFromSilentFailure() {
        DynamicRetrievalHandlerChain chain = chain();

        ReflectionTestUtils.invokeMethod(
                chain,
                "recordRetrievalStage",
                "web",
                true,
                0,
                new java.util.concurrent.CancellationException("cancelled ownerToken abc123"),
                true);

        assertEquals("cancelled", TraceStore.get("retrieval.stage.web.failureClass"));
        assertEquals("CancellationException", TraceStore.get("retrieval.stage.web.exceptionType"));
        assertFalse(String.valueOf(TraceStore.context()).contains("ownerToken abc123"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(
                chain,
                "recordRetrievalStage",
                "web",
                true,
                0,
                new InterruptedException("interrupted ownerToken abc123"),
                true);

        assertEquals("cancelled", TraceStore.get("retrieval.stage.web.failureClass"));
        assertEquals("InterruptedException", TraceStore.get("retrieval.stage.web.exceptionType"));
        assertFalse(String.valueOf(TraceStore.context()).contains("ownerToken abc123"));
    }

    @Test
    void dynamicRetrievalHandlerLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertTrue(rawThrowableLogLines.isEmpty(), rawThrowableLogLines.toString());
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
    }

    @Test
    void earlyFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("[Memory] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("NineTile alias correction failed, continuing with original query: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[OrderService] decide failed; using default: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[Memory] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[Alias] NineTile alias correction failed, continuing with original query. errorHash={} errorLength={}"));
        assertTrue(source.contains("[OrderService] decide failed; using default. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void adaptiveWebAndSseFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("[AdaptiveWeb] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SSE emit skipped: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[AdaptiveWeb] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("SSE emit skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void kallocLoreSelfAskAndAnalyzeFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("[KAlloc] resource hints skip: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[KAlloc] skip: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[Lore] fast-match skip: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[Lore] Failed to inject lore: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[SelfAskGate] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[SelfAsk] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[AnalyzeGate] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[Analyze] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[KAlloc] resource hints skip errorHash={} errorLength={}"));
        assertTrue(source.contains("[KAlloc] skip errorHash={} errorLength={}"));
        assertTrue(source.contains("[Lore] fast-match skip errorHash={} errorLength={}"));
        assertTrue(source.contains("[Lore] Failed to inject lore errorHash={} errorLength={}"));
        assertTrue(source.contains("[SelfAskGate] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[SelfAsk] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[AnalyzeGate] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[Analyze] fail-soft errorHash={} errorLength={}"));
    }

    @Test
    void recoveryReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.recovery.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"rag.recovery.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("TraceStore.put(\"rag.recovery.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void thumbnailRecallDomainTraceUsesHashAndLength() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("TraceStore.put(\"uaw.thumb.recall.domain\""));
        assertTrue(source.contains("TraceStore.put(\"uaw.thumb.recall.domainHash\""));
        assertTrue(source.contains("TraceStore.put(\"uaw.thumb.recall.domainLength\""));
        assertTrue(source.contains("SafeRedactor.hashValue(recallDomain)"));
    }

    @Test
    void finalFusedOutputInvokesFinalSigmoidGateAndTracesDecision() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "finalSigmoidGate",
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"));
        List<Content> fused = List.of(
                content("Official evidence supports this answer.", "https://official.example/a"),
                content("Secondary evidence also supports it.", "https://official.example/b"));

        List<Content> out = new java.util.ArrayList<>(fused);
        chain.handle(new Query("safe query"), out);

        assertEquals(fused, out);
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.finalSigmoidGate.applied"));
        assertEquals("PASS", TraceStore.get("retrieval.finalSigmoidGate.result"));
    }

    @Test
    void finalSigmoidGateBlockTrimsAccumulatorAndTracesCount() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "finalSigmoidGate",
                new FixedGate(FinalSigmoidGate.GateResult.BLOCK));
        List<Content> accumulator = new java.util.ArrayList<>(List.of(
                content("evidence a", "https://unverified-a.example/doc"),
                content("evidence b", "https://unverified-b.example/doc"),
                content("evidence c", "https://unverified-c.example/doc"),
                content("evidence d", "https://unverified-d.example/doc"),
                content("evidence e", "https://unverified-e.example/doc"),
                content("evidence f", "https://unverified-f.example/doc")));

        chain.handle(new Query("unsafe sparse query"), accumulator);

        assertEquals("BLOCK", TraceStore.get("retrieval.finalSigmoidGate.result"));
        assertEquals(3, accumulator.size());
        assertEquals(3, TraceStore.get("retrieval.finalSigmoidGate.blockTrim.count"));
    }

    @Test
    void artPlateAliasFeedsKAllocTailSignals() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "kallocEnabled", true);
        ReflectionTestUtils.setField(chain, "kallocMaxTotalK", 24);
        ReflectionTestUtils.setField(chain, "kallocMinPerSource", 2);
        ReflectionTestUtils.setField(chain, "kallocKStep", 4);
        ReflectionTestUtils.setField(chain, "kallocMaxSourceShare", 0.65d);
        TraceStore.put("artplate.gate.moeStrategy.alias", "AP3_VEC_DENSE");
        java.util.Map<String, Object> md = new java.util.HashMap<>();

        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan plan =
                ReflectionTestUtils.invokeMethod(chain, "__decideKPlan", "research", "vector recall", false, md);

        assertTrue(plan.vectorK > plan.webK);
        assertEquals("AP3_VEC_DENSE", TraceStore.get("retrieval.kalloc.artplate.alias"));
        assertEquals("AP3_VEC_DENSE", md.get("resource.artplate.alias"));
    }

    @Test
    void riskEmergentReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("putIfChanged(md, \"resource.riskEmergentReason\", risk.emergentAdjustment().reason())"));
        assertFalse(source.contains("TraceStore.put(\"resource.riskEmergentReason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("\"reason\", risk.emergentAdjustment().reason()"));
        assertFalse(source.contains(
                "SafeRedactor.safeMessage(risk.emergentAdjustment().reason(), 120)"));
        assertFalse(source.contains(
                "metadata.put(\"branch_quality_reason\", SafeRedactor.safeMessage(branchMetric.reason(), 120));"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), \"unknown\")"));
        assertTrue(source.contains("putIfChanged(md, \"resource.riskEmergentReason\", safeEmergentReason)"));
        assertTrue(source.contains("TraceStore.put(\"resource.riskEmergentReason\", safeEmergentReason);"));
        assertTrue(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", safeEmergentReason);"));
        assertTrue(source.contains("\"reason\", safeEmergentReason"));
        assertTrue(source.contains("metadata.put(\"branch_quality_reason\","));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(branchMetric.reason(), \"unknown\")"));
    }

    @Test
    void loreFastMatchKeepsEntryWhenPrimaryEntityNameIsMissing() {
        UniversalLoreRegistry registry = new UniversalLoreRegistry() {
            @Override
            public List<DomainKnowledge> findLoreInText(String text) {
                return List.of(new DomainKnowledge(
                        "Genshin",
                        java.util.Arrays.asList(null, "makiba"),
                        "stable lore payload"));
            }
        };
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, registry, null);
        List<Content> accumulator = new java.util.ArrayList<>();

        chain.handle(new Query("makiba"), accumulator);

        assertEquals(1, accumulator.size());
        assertTrue(accumulator.get(0).textSegment().text().contains("stable lore payload"));
        assertEquals("makiba", accumulator.get(0).textSegment().metadata().getString("entity"));
    }

    private static Map<?, ?> firstOrchEvent() {
        Object eventsObj = TraceStore.get("orch.events.v1");
        assertTrue(eventsObj instanceof List<?>);
        return assertInstanceOf(Map.class, ((List<?>) eventsObj).get(0));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedOrderControls() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String plan : List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1",
                "ap9_cost_saver.v1", "document_evidence.v1", "kg_first.v1"))
            for (String control : List.of("authored", "reverse", "all_lanes", "removed", "legacy", "caller",
                    "no_override", "deny_web", "deny_rag", "deny_both", "trimmed", "alias_corrected", "kalloc", "heuristic_fail"))
                cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, control));
        assertEquals(84, cases.size()); return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} retrieval order={1}")
    @org.junit.jupiter.params.provider.MethodSource("shippedOrderControls")
    void shippedOrderDrivesActualLaneCallsAfterAdmissionAndQueryRebuild(String planId, String control) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
        List<String> authored = switch (planId) {
            case "ap1_auth_web.v1" -> List.of("web"); case "ap3_vec_dense.v1" -> List.of("vector");
            case "document_evidence.v1" -> List.of("vector", "web", "kg"); case "kg_first.v1" -> List.of("kg", "vector", "web");
            default -> List.of("web", "vector");
        };
        var raw = new java.util.ArrayList<String>();
        original.path("retrieval").path("order").forEach(v -> raw.add(v.asText().toLowerCase(java.util.Locale.ROOT)));
        assertEquals(authored, raw);
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var retrieval = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval");
        List<String> projected = new java.util.ArrayList<>(authored);
        if (control.equals("reverse")) java.util.Collections.reverse(projected);
        if (control.equals("all_lanes") || control.startsWith("deny_")) projected = List.of("kg", "web", "vector");
        if (control.equals("removed")) projected = planId.startsWith("ap") ? authored : List.of();
        if (control.equals("legacy")) projected = List.of("vector", "kg", "web");
        if (control.equals("caller") || control.equals("no_override")) { projected = List.of(); modified.remove("chain"); }
        if (List.of("removed", "legacy", "caller", "no_override").contains(control)) retrieval.remove("order");
        else retrieval.set("order", mapper.valueToTree(projected));
        if (control.equals("legacy")) modified.set("retrievalOrder", mapper.valueToTree(projected));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval")).set("order", original.path("retrieval").path("order"));
        restored.remove("retrievalOrder");
        if (original.has("chain")) restored.set("chain", original.path("chain"));
        assertEquals(original, restored, "only order and explicit legacy-chain fallback controls change");
        byte[] yaml = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) return new org.springframework.core.io.ByteArrayResource(yaml) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(control.equals("authored")
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        if (projected.isEmpty()) projected = planId.startsWith("ap") ? authored : List.of("web", "vector", "kg");
        projected = new java.util.ArrayList<>(projected);
        if (planId.equals("ap1_auth_web.v1")) projected.remove("vector");
        if (planId.equals("ap3_vec_dense.v1")) projected.remove("web");
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertEquals(projected, plan.retrievalOrder());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (control.equals("caller")) metadata.put("retrieval.order", List.of("kg", "web"));
        applier.applyToHintsAndMeta(plan, hints, metadata);
        List<String> override = projected; // Plan/default projection wins over a pre-existing caller order.
        assertEquals(override.isEmpty() ? null : override, metadata.get("retrieval.order"));
        if (control.equals("no_override")) { metadata.remove("retrieval.order"); override = List.of(); }
        if (control.startsWith("deny_")) {
            metadata.put("allowWeb", !List.of("deny_web", "deny_both").contains(control));
            metadata.put("allowRag", !List.of("deny_rag", "deny_both").contains(control));
        }
        metadata.put("enableSelfAsk", false); metadata.put("enableAnalyze", false);
        var expected = new java.util.ArrayList<>(override.isEmpty() ? List.of("web", "kg", "vector") : override);
        boolean allowWeb = Boolean.parseBoolean(String.valueOf(metadata.get("allowWeb")));
        boolean allowRag = Boolean.parseBoolean(String.valueOf(metadata.get("allowRag")));
        if (!allowWeb) expected.remove("web");
        if (!allowRag) expected.removeAll(List.of("vector", "kg"));
        // The RAG deny cap covers both VECTOR and KG, including explicit order and positive KG allocation.
        var calls = new java.util.ArrayList<String>(); var received = new java.util.ArrayList<Query>();
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        var fuser = org.mockito.Mockito.mock(com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser.class);
        var vector = (dev.langchain4j.rag.content.retriever.ContentRetriever) q -> {
            calls.add("vector"); received.add(q); return List.of(content("vector order marker", "vector"));
        };
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("web"); received.add(a.getArgument(0)); return List.of(content("web order marker", "web"));
        });
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("kg"); received.add(a.getArgument(0)); return List.of(content("kg order marker", "kg"));
        });
        org.mockito.Mockito.when(rag.asContentRetriever("order-index")).thenReturn(vector);
        if (control.equals("heuristic_fail")) org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("synthetic order failure"));
        else org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(com.example.lms.strategy.RetrievalOrderService.Source.WEB,
                        com.example.lms.strategy.RetrievalOrderService.Source.KG, com.example.lms.strategy.RetrievalOrderService.Source.VECTOR));
        org.mockito.Mockito.when(fuser.fuse(org.mockito.ArgumentMatchers.<List<Content>>anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> ((List<List<Content>>) a.getArgument(0)).stream().flatMap(List::stream).toList());
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "order-index"); ReflectionTestUtils.setField(chain, "topK", 10);
        if (control.equals("kalloc")) ReflectionTestUtils.setField(chain, "kallocEnabled", true);
        if (control.equals("alias_corrected")) {
            var alias = org.mockito.Mockito.mock(com.example.lms.config.alias.NineTileAliasCorrector.class);
            org.mockito.Mockito.when(alias.correct(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                    .thenReturn("corrected order marker"); ReflectionTestUtils.setField(chain, "aliasCorrector", alias);
        }
        String text = control.equals("trimmed") ? "  order marker  " : "order marker";
        var query = com.example.lms.service.rag.QueryUtils.buildQuery(text, metadata);
        var output = new java.util.ArrayList<Content>(); chain.handle(query, output);
        System.out.printf("TBL07_ORDER plan=%s control=%s expected=%s calls=%s%n", planId, control, expected, calls);
        assertEquals(expected, calls, "actual retrieval invocation order, after explicit lane admission");
        assertEquals(new java.util.HashSet<>(expected), output.stream().map(c -> c.textSegment().metadata().getString("doc_id"))
                .collect(java.util.stream.Collectors.toSet()), "fusion membership is separate from invocation order");
        assertEquals(expected.size(), output.size());
        assertEquals(metadata, com.example.lms.service.rag.QueryUtils.metadata(query), "request metadata remains owned by input query");
        for (Query q : received) {
            var downstream = com.example.lms.service.rag.QueryUtils.metadata(q);
            assertEquals(metadata.get("retrieval.order"), downstream.get("retrieval.order"));
            assertEquals(metadata.get("allowWeb"), downstream.get("allowWeb")); assertEquals(metadata.get("allowRag"), downstream.get("allowRag"));
            assertEquals(control.equals("alias_corrected") ? "corrected order marker" : "order marker", q.text());
        }
        org.mockito.Mockito.verify(order).decideOrder(control.equals("alias_corrected") ? "corrected order marker" : "order marker");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true,none", "false,true,none", "missing,true,none",
            "true,false,none", "false,false,none", "missing,false,none",
            "true,true,false", "false,true,true", "true,false,true", "false,false,true"})
    @org.junit.jupiter.api.Timeout(10)
    void zeroBreakDppMetadataControlsActualRerankWithinOwnerEnableCap(
            String raw, boolean ownerEnabled, String callerOverride) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero_break.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "diversity.dpp.enabled";
        var originalKnobs = original.path("plan").path("overrides").path("knobs");
        assertTrue(originalKnobs.path(key).asBoolean());
        var modified = original.deepCopy();
        var changedKnobs = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("plan").path("overrides").path("knobs");
        if ("missing".equals(raw)) changedKnobs.remove(key); else changedKnobs.put(key, Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides").path("knobs"))
                .set(key, originalKnobs.path(key));
        assertEquals(original, restored, "only the complete plan's nested DPP flag changes");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero_break.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero_break.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load("zero_break");
        assertEquals("zero_break.v1", plan.planId());
        assertEquals("missing".equals(raw) ? null : Boolean.valueOf(raw), ((Map<?, ?>) plan.raw().get("knobs")).get(key));
        var ctx = new com.example.lms.service.guard.GuardContext();
        applier.applyToGuardContext(plan, ctx);
        var metadata = new java.util.LinkedHashMap<String, Object>();
        if (!"none".equals(callerOverride)) metadata.put(key, Boolean.valueOf(callerOverride));
        applier.applyToHintsAndMeta(plan, com.example.lms.orchestration.OrchestrationHints.defaults(), metadata);
        Object projected = "missing".equals(raw) ? null : Boolean.valueOf(raw);
        assertEquals(projected, ctx.getPlanOverride(key));
        assertEquals("none".equals(callerOverride) ? projected : Boolean.valueOf(callerOverride), metadata.get(key));
        boolean requested = "none".equals(callerOverride) ? !"false".equals(raw) : Boolean.parseBoolean(callerOverride);
        boolean expected = ownerEnabled && requested;
        assertTrue(com.example.lms.plan.PlanHintApplier.dslUnwiredKeys(plan).contains("plan.pipeline"));
        assertEquals(3, ctx.planInt("expand.selfAsk.count", -1));
        assertEquals(18, ctx.planInt("expand.queryBurst.count", -1));
        assertTrue(ctx.planBool("overdrive.enabled", false));
        assertTrue(ctx.planBool("extremeZ.enabled", false));
        List<Content> candidates = List.of(content("zeta research evidence", "zeta"),
                content("alpha reference evidence", "alpha"), content("beta independent evidence", "beta"));
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenReturn(candidates);
        org.mockito.Mockito.when(rag.asContentRetriever("dpp-fixture"))
                .thenReturn(q -> List.of());
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenReturn(List.of());
        org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(com.example.lms.strategy.RetrievalOrderService.Source.WEB));
        var fuser = new com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser(60, null, "");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var returned = new java.util.ArrayList<Content>();
        var reranker = new com.example.lms.service.rag.rerank.DppDiversityReranker() {
            @Override public <T> List<T> rerank(Config config, List<T> input, String query, int k,
                    java.util.function.Function<? super T, String> textOf,
                    java.util.function.ToDoubleFunction<? super T> relevanceOf) {
                calls.incrementAndGet();
                org.junit.jupiter.api.Assertions.assertSame(ctx, com.example.lms.service.guard.GuardContextHolder.get());
                assertEquals(candidates, input, "real RRF supplies all three unique candidates in rank order");
                assertEquals(3, k);
                List<T> result = super.rerank(config, input, query, k, textOf, relevanceOf);
                for (T item : result) returned.add((Content) item);
                return result;
            }
        };
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "dpp-fixture");
        ReflectionTestUtils.setField(chain, "topK", 3);
        ReflectionTestUtils.setField(chain, "mmrLambda", 0.7d);
        ReflectionTestUtils.setField(chain, "diversityEnabled", ownerEnabled);
        ReflectionTestUtils.setField(chain, "dppDiversityReranker", reranker);
        ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor");
        com.example.lms.service.guard.GuardContextHolder.set(ctx);
        try {
            var query = com.example.lms.service.rag.QueryUtils.buildQuery("local DPP boundary fixture", metadata);
            var output = new java.util.ArrayList<Content>();
            chain.handle(query, output);
            assertEquals(expected ? 1 : 0, calls.get(), "request metadata controls DPP inside the existing owner enable cap");
            assertEquals(expected ? "injected" : "disabled", TraceStore.get("dpp.reranker.source"));
            assertEquals(3, TraceStore.get("rrf.input.count"));
            assertEquals(3, TraceStore.get("rrf.output.count"));
            assertEquals(3, output.size());
            assertEquals(new java.util.HashSet<>(candidates), new java.util.HashSet<>(output),
                    "all eligible members remain when k equals the fused population");
            if (expected) {
                assertEquals(returned, output, "the handler returns the actual reranker result");
                assertFalse(candidates.equals(output), "real DPP changes ordering in this fixed population");
                assertEquals(3, TraceStore.get("dpp.rerank.inputCount"));
                assertEquals(3, TraceStore.get("dpp.rerank.outputCount"));
            } else {
                assertEquals(candidates, output);
                org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("dpp.rerank.inputCount"));
            }
            assertEquals(metadata, com.example.lms.service.rag.QueryUtils.metadata(query));
            org.mockito.Mockito.verify(web, org.mockito.Mockito.times(1)).retrieve(org.mockito.ArgumentMatchers.any(Query.class));
            System.out.printf("TBL07_DPP_CHAIN raw=%s ownerEnabled=%s callerOverride=%s effectiveEnabled=%s calls=%d results=%d membershipChanged=false orderChanged=%s externalRequests=0%n",
                    raw, ownerEnabled, callerOverride, expected, calls.get(), output.size(), !candidates.equals(output));
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear();
            TraceStore.clear();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        org.junit.jupiter.api.Assertions.assertNull(com.example.lms.service.guard.GuardContextHolder.get());
        assertTrue(executor.isTerminated());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "vectorCap raw={0} rag={1} caller={2} order={3}")
    @org.junit.jupiter.params.provider.CsvSource({"true,true,none,plan", "false,true,none,plan", "missing,true,none,plan",
            "true,false,none,plan", "false,false,none,plan", "missing,false,none,plan",
            "true,true,false,plan", "false,true,true,plan", "true,false,true,plan", "false,false,true,plan",
            "false,true,none,default", "missing,true,none,default"})
    @org.junit.jupiter.api.Timeout(10)
    void documentVectorMetadataCapsActualLaneCallsWithoutDisablingKg(String raw, boolean allowRag,
            String callerOverride, String orderMode) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/document_evidence.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var vectorParams = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params").path("retrieval").path("vector");
        if ("missing".equals(raw)) vectorParams.remove("enabled"); else vectorParams.put("enabled", Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params").path("retrieval").path("vector"))
                .set("enabled", original.path("params").path("retrieval").path("vector").path("enabled"));
        assertEquals(original, restored, "only nested vector enable changes in the plan");
        assertTrue(modified.path("plan").path("overrides").path("properties").path("retrieval.vector.enabled").asBoolean());
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/document_evidence.v1.yaml".equals(location)) return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return "document_evidence.v1.yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (!"none".equals(callerOverride)) metadata.put("retrieval.vector.enabled", Boolean.valueOf(callerOverride));
        applier.applyToHintsAndMeta(applier.load("document_evidence.v1"),
                com.example.lms.orchestration.OrchestrationHints.defaults(), metadata);
        Object projected = "missing".equals(raw) ? null : Boolean.valueOf(raw);
        assertEquals("none".equals(callerOverride) ? projected : Boolean.valueOf(callerOverride), metadata.get("retrieval.vector.enabled"));
        metadata.put("allowRag", allowRag);
        metadata.put("enableSelfAsk", false); metadata.put("enableAnalyze", false);
        if ("default".equals(orderMode)) metadata.remove("retrieval.order");
        boolean requested = "none".equals(callerOverride) ? !"false".equals(raw) : Boolean.parseBoolean(callerOverride);
        var expected = new java.util.ArrayList<>("default".equals(orderMode)
                ? List.of("web", "kg", "vector") : List.of("vector", "web", "kg"));
        if (!allowRag) expected.removeAll(List.of("vector", "kg"));
        if (!requested) expected.remove("vector");
        var calls = new java.util.ArrayList<String>();
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        var fuser = org.mockito.Mockito.mock(com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser.class);
        var vector = (dev.langchain4j.rag.content.retriever.ContentRetriever) q -> {
            calls.add("vector"); assertEquals(metadata.get("retrieval.vector.enabled"), com.example.lms.service.rag.QueryUtils.metadata(q).get("retrieval.vector.enabled"));
            return List.of(content("local vector enable marker", "vector"));
        };
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("web"); return List.of(content("local web enable marker", "web"));
        });
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("kg"); return List.of(content("local KG enable marker", "kg"));
        });
        org.mockito.Mockito.when(rag.asContentRetriever("vector-enable-index")).thenReturn(vector);
        org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
                com.example.lms.strategy.RetrievalOrderService.Source.WEB,
                com.example.lms.strategy.RetrievalOrderService.Source.KG,
                com.example.lms.strategy.RetrievalOrderService.Source.VECTOR));
        org.mockito.Mockito.when(fuser.fuse(org.mockito.ArgumentMatchers.<List<Content>>anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> ((List<List<Content>>) a.getArgument(0)).stream().flatMap(List::stream).toList());
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "vector-enable-index"); ReflectionTestUtils.setField(chain, "topK", 10);
        var executor = (java.util.concurrent.ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor");
        assertTrue(executor != null);
        try {
            var query = com.example.lms.service.rag.QueryUtils.buildQuery("  vector enable marker  ", metadata);
            var output = new java.util.ArrayList<Content>(); chain.handle(query, output);
            System.out.printf("TBL07_VECTOR_CAP raw=%s allowRag=%s callerOverride=%s order=%s expected=%s calls=%s externalRequests=0%n",
                    raw, allowRag, callerOverride, orderMode, expected, calls);
            assertEquals(expected, calls, "vector flag caps actual vector invocation independently of KG");
            assertEquals(new java.util.HashSet<>(expected), output.stream().map(c -> c.textSegment().metadata().getString("doc_id"))
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(expected.size(), output.size());
            assertEquals(metadata, com.example.lms.service.rag.QueryUtils.metadata(query));
            org.mockito.Mockito.verify(order).decideOrder("vector enable marker");
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear(); TraceStore.clear();
            executor.shutdown(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isTerminated());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> apChainControls() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String plan : List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1")) {
            var controls = new java.util.ArrayList<>(List.of("shipped", "order_fallback"));
            if (List.of("ap1_auth_web.v1", "ap3_vec_dense.v1").contains(plan)) controls.add("caps_relaxed");
            for (String control : controls) for (boolean alternate : List.of(false, true))
                cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, control, alternate));
        }
        assertEquals(20, cases.size()); return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "APchain plan={0} control={1} alternate={2}")
    @org.junit.jupiter.params.provider.MethodSource("apChainControls")
    @org.junit.jupiter.api.Timeout(10)
    void apChainFallbackChangesActualLaneCallsOnlyWhenExplicitOrderAndCapsPermit(
            String planId, String control, boolean alternate) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        boolean vectorPlan = planId.equals("ap3_vec_dense.v1");
        boolean webPlan = planId.equals("ap1_auth_web.v1");
        List<String> authored = vectorPlan ? List.of("vector") : webPlan ? List.of("web") : List.of("web", "vector");
        var shippedOrder = new java.util.ArrayList<String>();
        original.path("retrieval").path("order").forEach(v -> shippedOrder.add(v.asText()));
        assertEquals(authored, shippedOrder);
        String alternateStage = vectorPlan ? "retrieve_web" : "retrieve_vector";
        var nonRetrieval = new java.util.ArrayList<String>();
        original.path("chain").forEach(v -> { if (!v.asText().startsWith("retrieve_")) nonRetrieval.add(v.asText()); });
        if (alternate) {
            var changedChain = mapper.createArrayNode(); boolean inserted = false;
            for (var stage : original.path("chain")) {
                if (!stage.asText().startsWith("retrieve_")) changedChain.add(stage);
                else if (!inserted) { changedChain.add(alternateStage); inserted = true; }
            }
            assertTrue(inserted); modified.set("chain", changedChain);
            assertFalse(original.path("chain").equals(changedChain));
        }
        var preservedStages = new java.util.ArrayList<String>();
        modified.path("chain").forEach(v -> { if (!v.asText().startsWith("retrieve_")) preservedStages.add(v.asText()); });
        assertEquals(nonRetrieval, preservedStages, "non-retrieval stages remain authored; this does not prove their execution");
        if (!control.equals("shipped")) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval")).remove("order");
        if (control.equals("caps_relaxed")) {
            var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
            if (webPlan) params.put("allowRag", true);
            else { assertTrue(vectorPlan); params.put("vectorOnly", false); params.put("allowWeb", true); }
            if (vectorPlan) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("web")).put("enabled", true);
            if (webPlan) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("vector")).put("enabled", true);
        }
        var restored = modified.deepCopy(); restored.set("chain", original.path("chain"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval")).set("order", original.path("retrieval").path("order"));
        if (control.equals("caps_relaxed")) {
            var params = (com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params");
            for (String key : webPlan ? List.of("allowRag") : List.of("vectorOnly", "allowWeb")) params.set(key, original.path("params").path(key));
            if (webPlan) ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("vector"))
                    .set("enabled", original.path("retrieval").path("vector").path("enabled"));
            if (vectorPlan) ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("web"))
                    .set("enabled", original.path("retrieval").path("web").path("enabled"));
        }
        assertEquals(original, restored, "only retrieval chain, order, and explicitly labelled cap controls change");
        byte[] yaml = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) return new org.springframework.core.io.ByteArrayResource(yaml) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertFalse(plan.isEmpty());
        List<String> expected = authored;
        if (alternate && !control.equals("shipped") && ((!webPlan && !vectorPlan) || control.equals("caps_relaxed")))
            expected = vectorPlan ? List.of("web") : List.of("vector");
        assertEquals(expected, plan.retrievalOrder());
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, com.example.lms.orchestration.OrchestrationHints.defaults(), metadata);
        assertEquals(expected, metadata.get("retrieval.order"));
        metadata.put("enableSelfAsk", false); metadata.put("enableAnalyze", false);
        var calls = new java.util.ArrayList<String>(); var received = new java.util.ArrayList<Query>();
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        var fuser = org.mockito.Mockito.mock(com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser.class);
        var vector = (dev.langchain4j.rag.content.retriever.ContentRetriever) q -> {
            calls.add("vector"); received.add(q); return List.of(content("AP vector marker", "vector"));
        };
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("web"); received.add(a.getArgument(0)); return List.of(content("AP web marker", "web"));
        });
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("kg"); received.add(a.getArgument(0)); return List.of(content("AP KG marker", "kg"));
        });
        org.mockito.Mockito.when(rag.asContentRetriever("ap-chain-index")).thenReturn(vector);
        org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
                com.example.lms.strategy.RetrievalOrderService.Source.KG, com.example.lms.strategy.RetrievalOrderService.Source.WEB,
                com.example.lms.strategy.RetrievalOrderService.Source.VECTOR));
        org.mockito.Mockito.when(fuser.fuse(org.mockito.ArgumentMatchers.<List<Content>>anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> ((List<List<Content>>) a.getArgument(0)).stream().flatMap(List::stream).toList());
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "ap-chain-index"); ReflectionTestUtils.setField(chain, "topK", 10);
        var executor = (ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor"); assertTrue(executor != null);
        try {
            var query = com.example.lms.service.rag.QueryUtils.buildQuery("  AP chain marker  ", metadata);
            var output = new java.util.ArrayList<Content>(); chain.handle(query, output);
            System.out.printf("TBL07_AP_CHAIN plan=%s control=%s alternate=%s projected=%s calls=%s externalRequests=0%n",
                    planId, control, alternate, plan.retrievalOrder(), calls);
            assertEquals(expected, calls, "actual lane calls after order precedence, fallback inference, and admission");
            assertEquals(new java.util.HashSet<>(expected), output.stream().map(c -> c.textSegment().metadata().getString("doc_id"))
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(expected.size(), output.size()); assertEquals(metadata, com.example.lms.service.rag.QueryUtils.metadata(query));
            for (Query q : received) {
                var downstream = com.example.lms.service.rag.QueryUtils.metadata(q);
                for (String key : List.of("retrieval.order", "allowWeb", "allowRag")) assertEquals(metadata.get(key), downstream.get(key));
                assertEquals("AP chain marker", q.text());
            }
            org.mockito.Mockito.verify(order).decideOrder("AP chain marker");
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear(); TraceStore.clear();
            executor.shutdown(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isTerminated());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "aliasIntake raw={0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "false", "missing"})
    @org.junit.jupiter.api.Timeout(10)
    void oneShippedRootAliasFlagControlsActualCorrection(String raw) throws Exception {
        assertRootAliasContract("ap11_finance_special.v1", raw, "plan");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> rootAliasControls() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String plan : List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1",
                "ap9_cost_saver.v1", "kg_first.v1", "rulebreak.v1")) {
            for (String raw : List.of("true", "false", "missing")) cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, raw, "plan"));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "true", "caller_false"));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "false", "caller_true"));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "true", "no_bean"));
        }
        for (String control : List.of("invalid", "params_true", "knobs_false", "alias_failure"))
            cases.add(org.junit.jupiter.params.provider.Arguments.of("ap11_finance_special.v1",
                    control.equals("params_true") ? "false" : control.equals("invalid") ? "invalid" : "true", control));
        assertEquals(40, cases.size()); return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "rootAlias plan={0} raw={1} control={2}")
    @org.junit.jupiter.params.provider.MethodSource("rootAliasControls")
    @org.junit.jupiter.api.Timeout(10)
    void allShippedRootAliasFlagsPreserveCallerAndOptionalBeanBehavior(String planId, String raw, String control) throws Exception {
        assertRootAliasContract(planId, raw, control);
    }

    private void assertRootAliasContract(String planId, String raw, String control) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(original.path("alias").path("corrector").path("enabled").booleanValue());
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var flag = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("alias").path("corrector");
        if (raw.equals("missing")) flag.remove("enabled");
        else if (raw.equals("invalid")) flag.put("enabled", "invalid");
        else flag.put("enabled", Boolean.parseBoolean(raw));
        if (control.equals("params_true")) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put("alias.corrector.enabled", true);
        if (control.equals("knobs_false")) modified.putObject("plan").putObject("overrides").putObject("knobs").put("alias.corrector.enabled", false);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("alias").path("corrector"))
                .set("enabled", original.path("alias").path("corrector").path("enabled"));
        if (control.equals("params_true")) restored.set("params", original.path("params"));
        if (control.equals("knobs_false")) { assertFalse(original.has("plan")); restored.remove("plan"); }
        assertEquals(original, restored, "only the exact flag and labelled existing override controls change");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertFalse(plan.isEmpty());
        String key = "alias.corrector.enabled";
        Boolean rootValue = List.of("missing", "invalid").contains(raw) ? null : Boolean.valueOf(raw);
        Boolean projected = control.equals("params_true") ? Boolean.TRUE : control.equals("knobs_false") ? Boolean.FALSE : rootValue;
        Boolean effective = control.startsWith("caller_") ? Boolean.valueOf(control.equals("caller_true")) : projected;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (control.startsWith("caller_")) metadata.put(key, effective);
        applier.applyToHintsAndMeta(plan, com.example.lms.orchestration.OrchestrationHints.defaults(), metadata);
        var context = new com.example.lms.service.guard.GuardContext();
        if (control.startsWith("caller_")) context.putPlanOverride(key, effective);
        applier.applyToGuardContext(plan, context);
        metadata.put("enableSelfAsk", false); metadata.put("enableAnalyze", false);
        var alias = org.mockito.Mockito.mock(com.example.lms.config.alias.NineTileAliasCorrector.class);
        var aliasCalls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(alias.correct(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(a -> { aliasCalls.incrementAndGet(); assertEquals("alias original marker", a.getArgument(0));
                    if (control.equals("alias_failure")) throw new IllegalStateException("synthetic alias failure");
                    return "alias corrected marker"; });
        var received = new java.util.ArrayList<Query>();
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        var fuser = org.mockito.Mockito.mock(com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser.class);
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            received.add(a.getArgument(0)); return List.of(content("alias web result", "web")); });
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            received.add(a.getArgument(0)); return List.of(content("alias KG result", "kg")); });
        dev.langchain4j.rag.content.retriever.ContentRetriever vector = q -> { received.add(q); return List.of(content("alias vector result", "vector")); };
        org.mockito.Mockito.when(rag.asContentRetriever("alias-index")).thenReturn(vector);
        org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
                com.example.lms.strategy.RetrievalOrderService.Source.WEB, com.example.lms.strategy.RetrievalOrderService.Source.VECTOR,
                com.example.lms.strategy.RetrievalOrderService.Source.KG));
        org.mockito.Mockito.when(fuser.fuse(org.mockito.ArgumentMatchers.<List<Content>>anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> ((List<List<Content>>) a.getArgument(0)).stream().flatMap(List::stream).toList());
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        if (!control.equals("no_bean")) ReflectionTestUtils.setField(chain, "aliasCorrector", alias);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "alias-index"); ReflectionTestUtils.setField(chain, "topK", 10);
        var executor = (ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor"); assertTrue(executor != null);
        int expectedCalls = control.equals("no_bean") || Boolean.FALSE.equals(effective) ? 0 : 1;
        String expectedText = expectedCalls == 1 && !control.equals("alias_failure") ? "alias corrected marker" : "alias original marker";
        try {
            var query = com.example.lms.service.rag.QueryUtils.buildQuery("  alias original marker  ", metadata);
            var output = new java.util.ArrayList<Content>(); chain.handle(query, output);
            System.out.printf("TBL07_ROOT_ALIAS plan=%s raw=%s control=%s metadata=%s context=%s aliasCalls=%d expectedCalls=%d downstream=%d corrected=%s externalRequests=0%n",
                    planId, raw, control, metadata.get(key), context.getPlanOverride(key), aliasCalls.get(), expectedCalls,
                    received.size(), received.stream().allMatch(q -> q.text().equals("alias corrected marker")));
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(effective, metadata.get(key), "exact normalized flag reaches metadata with caller precedence"),
                    () -> assertEquals(effective, context.getPlanOverride(key), "context projection preserves existing request override"),
                    () -> assertEquals(expectedCalls, aliasCalls.get(), "effective false skips the existing alias invocation"),
                    () -> assertFalse(received.isEmpty()),
                    () -> assertTrue(received.stream().allMatch(q -> expectedText.equals(q.text())), "actual retrieval queries carry corrected or preserved text")
            );
            assertFalse(output.isEmpty()); assertEquals(metadata, com.example.lms.service.rag.QueryUtils.metadata(query));
            for (Query q : received) assertEquals(effective, com.example.lms.service.rag.QueryUtils.metadata(q).get(key));
            org.mockito.Mockito.verify(order).decideOrder(expectedText);
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear(); TraceStore.clear();
            executor.shutdown(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isTerminated());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> rootWebControls() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String plan : List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1")) {
            for (String raw : List.of("true", "false", "missing"))
                cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, raw, "shipped"));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "true", "caller_false"));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "false", "caller_true"));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "true", "deny_web"));
        }
        for (String raw : List.of("true", "false", "missing"))
            cases.add(org.junit.jupiter.params.provider.Arguments.of("ap3_vec_dense.v1", raw, "admissible"));
        cases.add(org.junit.jupiter.params.provider.Arguments.of("ap11_finance_special.v1", "true", "params_false"));
        cases.add(org.junit.jupiter.params.provider.Arguments.of("ap11_finance_special.v1", "false", "knobs_true"));
        cases.add(org.junit.jupiter.params.provider.Arguments.of("ap11_finance_special.v1", "invalid", "shipped"));
        assertEquals(30, cases.size()); return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "rootWeb plan={0} raw={1} control={2}")
    @org.junit.jupiter.params.provider.MethodSource("rootWebControls")
    @org.junit.jupiter.api.Timeout(10)
    void rootWebFlagCapsActualCallsWhilePreservingIndependentAdmission(String planId, String raw, String control) throws Exception {
        String key = "retrieval.web.enabled";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(!planId.equals("ap3_vec_dense.v1"), original.path("retrieval").path("web").path("enabled").asBoolean());
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var webNode = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("web");
        if (raw.equals("missing")) webNode.remove("enabled");
        else if (raw.equals("invalid")) webNode.put("enabled", "invalid-fixture-boolean");
        else webNode.put("enabled", Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("web"))
                .set("enabled", original.path("retrieval").path("web").path("enabled"));
        assertEquals(original, restored, "initial fixture modifies only the exact shipped root key");
        if (control.equals("admissible")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put("vectorOnly", false).put("allowWeb", true);
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval"))
                    .set("order", mapper.valueToTree(List.of("web", "vector")));
        }
        if (control.equals("params_false")) modified.with("params").put(key, "false");
        if (control.equals("knobs_true")) modified.with("plan").with("overrides").with("knobs").put(key, "true");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                assertEquals("classpath:plans/" + planId + ".yaml", location);
                return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
            }
        });
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertFalse(plan.isEmpty());
        Boolean projected = List.of("missing", "invalid").contains(raw) ? null : Boolean.valueOf(raw);
        if (control.equals("params_false")) projected = false;
        if (control.equals("knobs_true")) projected = true;
        Boolean effective = control.startsWith("caller_") ? Boolean.valueOf(control.equals("caller_true")) : projected;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        var guard = new com.example.lms.service.guard.GuardContext();
        if (control.startsWith("caller_")) { metadata.put(key, effective); guard.putPlanOverride(key, effective); }
        applier.applyToHintsAndMeta(plan, com.example.lms.orchestration.OrchestrationHints.defaults(), metadata);
        applier.applyToGuardContext(plan, guard);
        if (control.equals("deny_web")) metadata.put("allowWeb", false);
        metadata.put("enableSelfAsk", false); metadata.put("enableAnalyze", false);
        var expected = new java.util.ArrayList<>(plan.retrievalOrder());
        if (Boolean.FALSE.equals(metadata.get("allowWeb")) || Boolean.FALSE.equals(effective)) expected.remove("web");
        if (Boolean.FALSE.equals(metadata.get("allowRag"))) expected.removeAll(List.of("vector", "kg"));
        var calls = new java.util.ArrayList<String>();
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        var fuser = org.mockito.Mockito.mock(com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser.class);
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("web"); return List.of(content("root web marker", "web")); });
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("kg"); return List.of(content("root KG marker", "kg")); });
        org.mockito.Mockito.when(rag.asContentRetriever("root-web-index")).thenReturn(q -> {
            calls.add("vector"); return List.of(content("root vector marker", "vector")); });
        org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
                com.example.lms.strategy.RetrievalOrderService.Source.WEB, com.example.lms.strategy.RetrievalOrderService.Source.VECTOR));
        org.mockito.Mockito.when(fuser.fuse(org.mockito.ArgumentMatchers.<List<Content>>anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> ((List<List<Content>>) a.getArgument(0)).stream().flatMap(List::stream).toList());
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "root-web-index"); ReflectionTestUtils.setField(chain, "topK", 10);
        var executor = (ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor");
        assertTrue(executor != null);
        try {
            var query = com.example.lms.service.rag.QueryUtils.buildQuery("  root web fixture  ", metadata);
            var output = new java.util.ArrayList<Content>(); chain.handle(query, output);
            System.out.printf("TBL07_ROOT_WEB plan=%s raw=%s control=%s projected=%s context=%s expected=%s calls=%s externalRequests=0%n",
                    planId, raw, control, metadata.get(key), guard.getPlanOverride(key), expected, calls);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(effective, metadata.get(key), "normalized exact key preserves caller precedence"),
                    () -> assertEquals(effective, guard.getPlanOverride(key), "context preserves caller precedence"),
                    () -> assertEquals(expected, calls, "raw WEB cap reaches actual calls inside existing admission"));
            assertEquals(new java.util.HashSet<>(expected), output.stream().map(c -> c.textSegment().metadata().getString("doc_id"))
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(metadata, com.example.lms.service.rag.QueryUtils.metadata(query));
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear(); TraceStore.clear();
            executor.shutdown(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isTerminated());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "APvector raw={0} caller={1}")
    @org.junit.jupiter.params.provider.CsvSource({"true,none", "false,none", "missing,none",
            "true,false", "false,true"})
    @org.junit.jupiter.api.Timeout(10)
    void apRootVectorFlagReachesActualDynamicCallsAndPreservesWebAndKg(String raw, String caller) throws Exception {
        String planId = "ap11_finance_special.v1", key = "retrieval.vector.enabled";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(original.path("retrieval").path("vector").path("enabled").asBoolean());
        var modified = original.deepCopy();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("vector");
        if (raw.equals("missing")) node.remove("enabled"); else node.put("enabled", Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("vector"))
                .set("enabled", original.path("retrieval").path("vector").path("enabled"));
        assertEquals(original, restored, "only the exact AP root vector key changes");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                assertEquals("classpath:plans/" + planId + ".yaml", location);
                return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
            }
        });
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertFalse(plan.isEmpty());
        Boolean projected = raw.equals("missing") ? null : Boolean.valueOf(raw);
        Boolean effective = caller.equals("none") ? projected : Boolean.valueOf(caller);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        var guard = new com.example.lms.service.guard.GuardContext();
        if (!caller.equals("none")) { metadata.put(key, effective); guard.putPlanOverride(key, effective); }
        applier.applyToHintsAndMeta(plan, com.example.lms.orchestration.OrchestrationHints.defaults(), metadata);
        applier.applyToGuardContext(plan, guard);
        // Fixed caller order exposes KG independently of the shipped AP order.
        metadata.put("retrieval.order", List.of("web", "vector", "kg"));
        metadata.put("enableSelfAsk", false); metadata.put("enableAnalyze", false);
        var expected = Boolean.FALSE.equals(effective) ? List.of("web", "kg") : List.of("web", "vector", "kg");
        var calls = new java.util.ArrayList<String>();
        var web = org.mockito.Mockito.mock(com.example.lms.service.rag.WebSearchRetriever.class);
        var rag = org.mockito.Mockito.mock(com.example.lms.service.rag.LangChainRAGService.class);
        var kg = org.mockito.Mockito.mock(KnowledgeGraphHandler.class);
        var order = org.mockito.Mockito.mock(com.example.lms.strategy.RetrievalOrderService.class);
        var fuser = org.mockito.Mockito.mock(com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser.class);
        org.mockito.Mockito.when(web.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("web"); return List.of(content("AP vector WEB control", "web")); });
        org.mockito.Mockito.when(kg.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(a -> {
            calls.add("kg"); return List.of(content("AP vector KG control", "kg")); });
        org.mockito.Mockito.when(rag.asContentRetriever("ap-vector-index")).thenReturn(q -> {
            calls.add("vector"); return List.of(content("AP vector marker", "vector")); });
        org.mockito.Mockito.when(order.decideOrder(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
                com.example.lms.strategy.RetrievalOrderService.Source.WEB, com.example.lms.strategy.RetrievalOrderService.Source.VECTOR));
        org.mockito.Mockito.when(fuser.fuse(org.mockito.ArgumentMatchers.<List<Content>>anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> ((List<List<Content>>) a.getArgument(0)).stream().flatMap(List::stream).toList());
        var chain = new DynamicRetrievalHandlerChain(null, null, null, null, web, null, rag, null, null, kg,
                order, fuser, null, null, null);
        ReflectionTestUtils.setField(chain, "pineconeIndexName", "ap-vector-index"); ReflectionTestUtils.setField(chain, "topK", 10);
        var executor = (ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor"); assertTrue(executor != null);
        try {
            var output = new java.util.ArrayList<Content>();
            chain.handle(com.example.lms.service.rag.QueryUtils.buildQuery("  AP vector fixture  ", metadata), output);
            System.out.printf("TBL07_AP_ROOT_VECTOR raw=%s caller=%s projected=%s metadata=%s context=%s expected=%s calls=%s externalRequests=0%n",
                    raw, caller, plan.raw().get(key), metadata.get(key), guard.getPlanOverride(key), expected, calls);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(projected, plan.raw().get(key), "exact root producer"),
                    () -> assertEquals(effective, metadata.get(key), "caller metadata precedence"),
                    () -> assertEquals(effective, guard.getPlanOverride(key), "caller context precedence"),
                    () -> assertEquals(expected, calls, "VECTOR cap preserves WEB and KG actual calls"));
            assertEquals(new java.util.HashSet<>(expected), output.stream().map(c -> c.textSegment().metadata().getString("doc_id"))
                    .collect(java.util.stream.Collectors.toSet()));
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear(); TraceStore.clear();
            executor.shutdown(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isTerminated());
    }

    private static Content content(String text, String id) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("doc_id", id, "source", id))));
    }

    private static Content content(String text, String id, Map<String, Object> metadata) {
        java.util.Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("doc_id", id);
        all.put("source", id);
        all.putAll(metadata);
        return Content.from(TextSegment.from(text, Metadata.from(all)));
    }

    private static double number(Object value) {
        assertTrue(value instanceof Number);
        return ((Number) value).doubleValue();
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }

    private static DynamicRetrievalHandlerChain chain() {
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(chain, "searchFailureRecoveryMaxSubqueries", 4);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCitationMin", 3);
        return chain;
    }

    private static final class FixedGate extends FinalSigmoidGate {
        private final GateResult result;

        private FixedGate(GateResult result) {
            super(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "hard");
            this.result = result;
        }

        @Override
        public GateResult check(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
            return result;
        }
    }
}
