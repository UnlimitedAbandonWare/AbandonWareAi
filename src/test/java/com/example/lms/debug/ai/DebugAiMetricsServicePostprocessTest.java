package com.example.lms.debug.ai;

import com.example.lms.api.DebugAiMetricsController;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventTracePromotionService;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugAiMetricsServicePostprocessTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void snapshotCallsPopulateBoundedHistoryNewestFirst() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO, Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        for (int i = 0; i < 52; i++) {
            service.snapshot(10, 60_000);
        }

        List<DebugAiMetricSnapshot> history = service.snapshotHistory(100);

        assertEquals(48, history.size());
        for (int i = 1; i < history.size(); i++) {
            assertFalse(history.get(i - 1).generatedAt().isBefore(history.get(i).generatedAt()));
        }
    }

    @Test
    void compactSnapshotHonorsRequestedWindowMs() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now - 120_000, DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO, Map.of("result", "old")));
        store.events.add(event(now - 1_000, DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO, Map.of("result", "recent")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        Map<String, Object> oneMinute = service.compactSnapshot(10, 60_000);
        Map<String, Object> threeMinutes = service.compactSnapshot(10, 180_000);

        assertEquals(60_000L, oneMinute.get("windowMs"));
        assertEquals(1L, ((Number) oneMinute.get("totalEvents")).longValue());
        assertEquals(180_000L, threeMinutes.get("windowMs"));
        assertEquals(2L, ((Number) threeMinutes.get("totalEvents")).longValue());
    }

    @Test
    void breakerAndGenericProbesUseStableLayersAndSpringContextTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.NIGHTMARE_BREAKER, DebugEventLevel.WARN,
                Map.of("failureClass", "breaker_open")));
        store.events.add(event(now, DebugProbeType.GENERIC, DebugEventLevel.INFO,
                Map.of("failureClass", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);

        assertTrue(snapshot.layerCounts().containsKey("breaker"));
        assertTrue(snapshot.layerCounts().containsKey("spring.context"));
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        assertEquals(2L, springContext.eventCount());
    }

    @Test
    void allOperationalProbeTypesUseStableLayerNames() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.HTTP, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.EXECUTOR, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.REACTOR, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.AUTOLEARN, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.CONTEXT_PROPAGATION, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.GUARD_CONTEXT, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.RULE_BREAK, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.FAULT_MASK, DebugEventLevel.WARN, Map.of("failureClass", "masked")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        Map<String, Long> layers = service.snapshot(20, 60_000).layerCounts();

        assertTrue(layers.containsKey("http"));
        assertTrue(layers.containsKey("executor"));
        assertTrue(layers.containsKey("reactor"));
        assertTrue(layers.containsKey("learning.autolearn"));
        assertTrue(layers.containsKey("context.propagation"));
        assertTrue(layers.containsKey("guard.context"));
        assertTrue(layers.containsKey("guard.ruleBreak"));
        assertTrue(layers.containsKey("failsoft.faultMask"));
    }

    @Test
    void agentReportProbesStayOnAgentToolUsageTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.AGENT_REPORT_CFVM, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now + 1, DebugProbeType.AGENT_REPORT_TRACE, DebugEventLevel.INFO, Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile agentToolUsage = snapshot.tiles().stream()
                .filter(tile -> "AGENT_TOOL_USAGE".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(2L, agentToolUsage.eventCount());
        assertTrue(snapshot.layerCounts().containsKey("agent.report.cfvm"));
        assertTrue(snapshot.layerCounts().containsKey("agent.report.trace"));
    }

    @Test
    void scorecardIncludesHistoryBasedWarnAndErrorTrends() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.WEB_SEARCH, DebugEventLevel.WARN, Map.of("failureClass", "timeout")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        service.snapshot(10, 60_000);
        store.events.add(event(now + 1, DebugProbeType.WEB_SEARCH, DebugEventLevel.ERROR, Map.of("failureClass", "rate-limit")));

        Map<String, Object> scorecard = service.snapshot(10, 60_000).scorecard();

        assertEquals("flat", scorecard.get("warnTrend"));
        assertEquals("up", scorecard.get("errorTrend"));
        assertEquals(0L, ((Number) scorecard.get("warnDelta")).longValue());
        assertEquals(1L, ((Number) scorecard.get("errorDelta")).longValue());
    }

    @Test
    void mixedWindowSnapshotsDoNotProduceComparableDelta() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.WEB_SEARCH, DebugEventLevel.WARN,
                Map.of("failureClass", "timeout")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        service.snapshot(10, 60_000L);
        store.events.add(event(now + 1, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        store.events.add(event(now + 2, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        store.events.add(event(now + 3, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));

        Map<String, Object> scorecard = service.snapshot(80, 300_000L).scorecard();

        assertEquals(Boolean.FALSE, scorecard.get("historyComparisonComparable"));
        assertEquals("sampling_policy_mismatch", scorecard.get("historyComparisonReason"));
        assertEquals(60_000L, scorecard.get("previousWindowMs"));
        assertEquals(300_000L, scorecard.get("currentWindowMs"));
        assertEquals(10, scorecard.get("previousSampleLimit"));
        assertEquals(80, scorecard.get("currentSampleLimit"));
        assertEquals(0L, scorecard.get("warnDelta"));
        assertEquals(0L, scorecard.get("errorDelta"));
        assertEquals("none", scorecard.get("warnTrend"));
        assertEquals("none", scorecard.get("errorTrend"));
        assertEquals(Boolean.FALSE, scorecard.get("anomalyTriggered"));
        assertEquals(Boolean.FALSE, TraceStore.get("debug.ai.metrics.anomaly.historyComparisonComparable"));
        assertEquals("sampling_policy_mismatch",
                TraceStore.get("debug.ai.metrics.anomaly.historyComparisonReason"));
    }

    @Test
    void scorecardTriggersHistoryBasedAnomalyForLlmErrorSpike() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.PROMPT, DebugEventLevel.INFO, Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        service.snapshot(10, 60_000);

        store.events.add(event(now + 1, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        store.events.add(event(now + 2, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        store.events.add(event(now + 3, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));

        Map<String, Object> scorecard = service.snapshot(10, 60_000).scorecard();

        assertEquals(true, scorecard.get("anomalyTriggered"));
        assertEquals("error_delta_threshold", scorecard.get("anomalyReason"));
        assertEquals("LLM_MODEL_GUARD", scorecard.get("anomalyTile"));
        assertEquals("llm_upstream_retry_exhausted", scorecard.get("anomalyFailureClass"));
        assertTrue(((Number) scorecard.get("anomalyScore")).doubleValue() >= 0.7d);
        assertEquals(true, TraceStore.get("debug.ai.metrics.anomaly.triggered"));
        assertEquals("error_delta_threshold", TraceStore.get("debug.ai.metrics.anomaly.reason"));
    }

    @Test
    void tavilyProviderFailuresStayOnWebSearchTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.GENERIC, DebugEventLevel.WARN,
                Map.of("failureClass", "web.tavily.providerDisabled")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiRawTile webSearch = service.snapshot(10, 60_000).tiles().stream()
                .filter(tile -> "WEB_SEARCH".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, webSearch.eventCount());
        assertEquals(1L, webSearch.warnCount());
    }

    @Test
    void imageJobSignalsUseImageJobTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.GENERIC, DebugEventLevel.WARN,
                Map.of("layer", "image.job", "failureClass", "IMAGE_JOB_RESULT_WITHOUT_PUBLIC_URL")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);

        DebugAiRawTile imageJob = snapshot.tiles().stream()
                .filter(tile -> "IMAGE_JOB".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, imageJob.eventCount());
        assertEquals(1L, imageJob.warnCount());
        assertEquals(0L, springContext.eventCount());
    }

    @Test
    void promotedVerificationFailSoftUsesVerificationTileWithoutInventingVerificationUsage() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService promotion = new DebugEventTracePromotionService(store);
        String rawPayload = "ownerToken=private-verification-payload";
        promotion.promoteChatTrace("final", Map.of(
                "mla.breadcrumb.step.verification", Map.of(
                        "stage", "verification",
                        "status", "fail_soft",
                        "failureClass", "catch",
                        "reasonCode", "judge_call_failed",
                        "judgeLane", "fact_status_classifier",
                        "judgeFailSoftLaneCount", 1,
                        "judgeCallAttempted", true,
                        "verificationOutcomeKnown", false,
                        "redacted", true,
                        "rawPrompt", rawPayload)),
                "ChatApiController.stream.final");
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile verification = snapshot.tiles().stream()
                .filter(tile -> "VERIFICATION_BUILD".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, snapshot.layerCounts().get("verification.judge"));
        assertEquals(1L, verification.eventCount());
        assertEquals(1L, verification.warnCount());
        assertEquals("catch", verification.topFailureClass());
        assertEquals("warn", verification.status());
        assertEquals(0L, springContext.eventCount());
        assertEquals(0L, ((Number) snapshot.scorecard().get("verificationUsageCount")).longValue());
        assertFalse(String.valueOf(snapshot).contains(rawPayload));
    }

    @Test
    void queryRewriteSuperTokenEventsStayVisibleAsStructuredTransformerSignal() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.QUERY_TRANSFORMER, DebugEventLevel.INFO,
                Map.of(
                        "stage", "query_rewrite",
                        "superCount", 3,
                        "branchCount", 3,
                        "subModelCount", 3,
                        "branchTitleCount", 3,
                        "branchAxisCount", 3,
                        "paddedCount", 2,
                        "titlePresent", true,
                        "branchTitleHashes", List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "ownerToken=private-title-hash"),
                        "titleHash12", "abc123safehash")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile queryTransformer = snapshot.tiles().stream()
                .filter(tile -> "QUERY_TRANSFORMER".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, queryTransformer.eventCount());
        assertEquals("query_rewrite.super_tokens", queryTransformer.topFailureClass());
        assertEquals(1L, snapshot.failureClassCounts().get("query_rewrite.super_tokens"));
        assertTrue(snapshot.layerCounts().containsKey("query.transformer"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteSubModelCount"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteBranchTitleCount"));
        assertEquals(2L, snapshot.scorecard().get("queryRewriteBranchTitleHashCount"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteBranchAxisCount"));
        assertEquals(2L, snapshot.scorecard().get("queryRewritePaddedCount"));
        assertFalse(String.valueOf(snapshot).contains("abc123safehash"));
    }

    @Test
    void queryRewriteIncompleteCoverageWarnsInStructuredTransformerTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.QUERY_TRANSFORMER, DebugEventLevel.WARN,
                Map.of(
                        "stage", "query_rewrite",
                        "failureClass", "query_rewrite.super_tokens.incomplete_coverage",
                        "superCount", 2,
                        "branchCount", 2,
                        "subModelCount", 2,
                        "branchAxisCount", 2,
                        "missingAxisCount", 1,
                        "outputCoverageComplete", false,
                        "titleHash12", "abc123safehash")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile queryTransformer = snapshot.tiles().stream()
                .filter(tile -> "QUERY_TRANSFORMER".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, queryTransformer.eventCount());
        assertEquals(1L, queryTransformer.warnCount());
        assertEquals("query_rewrite.super_tokens.incomplete_coverage", queryTransformer.topFailureClass());
        assertEquals(1L, snapshot.failureClassCounts().get("query_rewrite.super_tokens.incomplete_coverage"));
        assertEquals(2L, snapshot.scorecard().get("queryRewriteSubModelCount"));
        assertEquals(2L, snapshot.scorecard().get("queryRewriteBranchAxisCount"));
        assertFalse(String.valueOf(snapshot).contains("abc123safehash"));
    }

    @Test
    void traceMemoryCheckpointFromTraceStoreBecomesVirtualDebugSlot() {
        TraceStore.put("traceMemory.triggered", true);
        TraceStore.put("traceMemory.trigger.reason", "loader_starvation");
        TraceStore.put("traceMemory.recovery.action", "FALLBACK");
        TraceStore.put("traceMemory.recovery.failureClass", "DATA");
        TraceStore.put("traceMemory.recovery.quarantine", false);
        TraceStore.put("traceMemory.errorBreak.risk", "WARN");
        TraceStore.put("traceMemory.fingerprint.current", "hash:safe-trace-memory-fingerprint");
        TraceStore.put("traceMemory.checkpoint.stage", "first_refinement");
        TraceStore.put("traceMemory.virtualCheckpoint.latestKey", "second_refinement.post");
        TraceStore.put("traceMemory.virtualCheckpoint.latestStage", "second_refinement");
        TraceStore.put("traceMemory.virtualCheckpoint.latestPhase", "post_load");
        TraceStore.put("traceMemory.delta.changedCount", 2);
        DebugAiMetricsService service = new DebugAiMetricsService(new StaticDebugEventStore());

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        Map<String, Object> compact = service.compactSnapshot(10, 60_000);

        assertEquals(1L, snapshot.probeCounts().get("TRACE_MEMORY"));
        assertEquals(1L, snapshot.layerCounts().get("memory.postprocess"));
        assertEquals(1L, snapshot.failureClassCounts().get("trace_memory.loader_starvation"));
        assertTrue(snapshot.planUsage().stream()
                .anyMatch(row -> "trace.memory.virtual.second_refinement".equals(row.get("planId"))));
        Map<?, ?> diagnostics = (Map<?, ?>) compact.get("traceMemoryDiagnostics");
        assertEquals("second_refinement.post", diagnostics.get("virtualCheckpointKey"));
        assertEquals("second_refinement", diagnostics.get("virtualCheckpointStage"));
        assertEquals("post_load", diagnostics.get("virtualCheckpointPhase"));
        assertFalse(String.valueOf(snapshot).contains("private student memory"));
        assertFalse(String.valueOf(compact).contains("private student memory"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void virtualMatrixScorecardBuildsThreeHundredWeightedChunksWithoutRawDebugValues() {
        String rawSecret = "ownerToken=private-debug-matrix-secret";
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.WEB_SEARCH, DebugEventLevel.WARN,
                Map.of("failureClass", "timeout", "result", rawSecret)));
        store.events.add(event(now + 1, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted", "latencyMs", 1200)));
        store.events.add(event(now + 2, DebugProbeType.QUERY_TRANSFORMER, DebugEventLevel.INFO,
                Map.of("stage", "query_rewrite", "subModelCount", 3, "branchAxisCount", 3)));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        Map<String, Object> scorecard = service.snapshot(20, 60_000).scorecard();

        assertEquals(300, scorecard.get("virtualMatrixCount"));
        assertEquals(10, scorecard.get("virtualMatrixChunkSize"));
        assertEquals(30, scorecard.get("virtualMatrixChunkCount"));
        assertTrue(((Number) scorecard.get("virtualMatrixWeightedScore")).doubleValue() > 0.0d);
        assertTrue(List.class.isAssignableFrom(scorecard.get("virtualMatrixChunks").getClass()));
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) scorecard.get("virtualMatrixChunks");
        assertEquals(30, chunks.size());
        assertTrue(chunks.stream().allMatch(row -> row.containsKey("chunkIndex")
                && row.containsKey("weight")
                && row.containsKey("riskScore")
                && row.containsKey("dominantTile")
                && row.containsKey("decision")));
        assertFalse(String.valueOf(scorecard).contains(rawSecret));
        assertEquals(300, TraceStore.get("debug.ai.metrics.virtualMatrix.count"));
        assertEquals(30, TraceStore.get("debug.ai.metrics.virtualMatrix.chunkCount"));
        assertTrue(((Number) TraceStore.get("debug.ai.metrics.virtualMatrix.weightedScore")).doubleValue() > 0.0d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleErrorDoesNotAuthorizeMitigationWithoutProbeFacts() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(
                System.currentTimeMillis(),
                DebugProbeType.MODEL_GUARD,
                DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        Map<String, Object> scorecard = service.snapshot(20, 60_000).scorecard();
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) scorecard.get("virtualMatrixChunks");
        long probeRequiredChunkCount = chunks.stream()
                .filter(row -> "probe_required".equals(row.get("decision")))
                .count();

        assertEquals("evidence", scorecard.get("virtualMatrixScoreRole"));
        assertEquals(Boolean.FALSE, scorecard.get("virtualMatrixScoreTrusted"));
        assertEquals("probe_required", scorecard.get("virtualMatrixDecision"),
                "weightedScore=" + scorecard.get("virtualMatrixWeightedScore")
                        + " probeRequiredChunkCount=" + probeRequiredChunkCount);
        assertEquals(Boolean.FALSE, scorecard.get("virtualMatrixActionAllowed"));
        assertTrue(chunks.stream().noneMatch(row -> "mitigate_now".equals(row.get("decision"))));
        assertEquals("evidence", TraceStore.get("debug.ai.metrics.virtualMatrix.scoreRole"));
        assertEquals(Boolean.FALSE, TraceStore.get("debug.ai.metrics.virtualMatrix.scoreTrusted"));
        assertEquals(Boolean.FALSE, TraceStore.get("debug.ai.metrics.virtualMatrix.actionAllowed"));
    }

    @Test
    void riskRewriteTraceStoreSignalsBecomeQueryTransformerSlotWithoutRawQuery() {
        String rawQuery = "private query ownerToken=debug-risk-rewrite-secret";
        TraceStore.put("ml.risk.rewrite.band", "MEDIUM");
        TraceStore.put("ml.risk.rewrite.score", "0.66");
        TraceStore.put("ml.risk.rewrite.currentScore", "0.62");
        TraceStore.put("ml.risk.rewrite.temperature", "0.28");
        TraceStore.put("ml.risk.rewrite.policy", "risk_weighted");
        TraceStore.put("ml.risk.rewrite.primaryFactor", "contradiction");
        TraceStore.put("ml.risk.rewrite.components", Map.of(
                "providerFailure", 0.05d,
                "afterFilterStarvation", 0.08d,
                "contradiction", 0.14d,
                "latencyPressure", 0.09d));
        TraceStore.put("selfask.3way.requery.required", true);
        TraceStore.put("selfask.3way.requery.confirmed", true);
        TraceStore.put("selfask.3way.weights", Map.of("BQ", 0.45d, "ER", 0.30d, "RC", 0.25d));
        TraceStore.put("extremeZ.burstExpand.laneProfiles",
                List.of("conservative", "evidence-first", "contradiction-check"));
        TraceStore.put("raw.query.fixture", rawQuery);
        DebugAiMetricsService service = new DebugAiMetricsService(new StaticDebugEventStore());

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);

        assertEquals(1L, snapshot.probeCounts().get("TRACE_RISK_REWRITE"));
        assertEquals(1L, snapshot.layerCounts().get("query_transformer.risk_rewrite"));
        assertEquals(1L, snapshot.failureClassCounts().get("risk_rewrite.contradiction"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteSubModelCount"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteBranchAxisCount"));
        assertTrue(snapshot.planUsage().stream()
                .anyMatch(row -> "selfask.risk_rewrite.risk_weighted".equals(row.get("planId"))));
        DebugAiRawTile queryTransformer = snapshot.tiles().stream()
                .filter(tile -> "QUERY_TRANSFORMER".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, queryTransformer.eventCount());
        assertFalse(String.valueOf(snapshot).contains(rawQuery));
    }

    @Test
    void riskRewriteUsesLaneVariantProfilesWhenProfileListIsMissing() {
        TraceStore.put("ml.risk.rewrite.primaryFactor", "contradiction");
        TraceStore.put("extremeZ.burstExpand.laneVariantProfiles", List.of(
                Map.of("profile", "conservative", "role", "STRICT", "queryHash12", "a1"),
                Map.of("profile", "evidence-first", "role", "VERIFY", "queryHash12", "b2"),
                Map.of("profile", "contradiction-check", "role", "COUNTEREXAMPLE", "queryHash12", "c3")));
        DebugAiMetricsService service = new DebugAiMetricsService(new StaticDebugEventStore());

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);

        assertEquals(1L, snapshot.probeCounts().get("TRACE_RISK_REWRITE"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteBranchAxisCount"));
    }

    @Test
    void externalEvidenceEventsUseTheirOwnOpsConsoleTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.EXTERNAL_EVIDENCE, DebugEventLevel.WARN,
                Map.of(
                        "lanePolicy", "external_evidence",
                        "failureClass", "external_evidence_lane",
                        "laneCount", 4,
                        "readOnly", true,
                        "executionThread", false)));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile externalEvidence = snapshot.tiles().stream()
                .filter(tile -> "EXTERNAL_EVIDENCE".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, externalEvidence.eventCount());
        assertEquals(1L, externalEvidence.warnCount());
        assertEquals("external_evidence_lane", externalEvidence.topFailureClass());
        assertEquals(0L, springContext.eventCount());
        assertTrue(snapshot.layerCounts().containsKey("external.evidence"));
    }

    @Test
    void scheduledHistoryRecorderRecordsSnapshots() throws Exception {
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(System.currentTimeMillis(), DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO,
                Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        DebugAiMetricsHistoryScheduler scheduler = new DebugAiMetricsHistoryScheduler(service);

        scheduler.recordHistorySnapshot();

        assertFalse(service.snapshotHistory(1).isEmpty());
        assertTrue(DebugAiMetricsHistoryScheduler.class
                .getDeclaredMethod("recordHistorySnapshot")
                .isAnnotationPresent(Scheduled.class));
    }

    @Test
    void controllerExposesCompactAndHistoryEndpoints() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(System.currentTimeMillis(), DebugProbeType.PROMPT, DebugEventLevel.INFO,
                Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        DebugAiMetricsController controller = new DebugAiMetricsController(service);

        Map<String, Object> compact = controller.compact(5, 300_000);
        controller.snapshot(5, 300_000);

        assertEquals(300_000L, compact.get("windowMs"));
        assertEquals(300, compact.get("virtualMatrixCount"));
        assertEquals(30, compact.get("virtualMatrixChunkCount"));
        assertTrue(((Number) compact.get("virtualMatrixWeightedScore")).doubleValue() >= 0.0d);
        assertEquals("evidence", compact.get("virtualMatrixScoreRole"));
        assertEquals(Boolean.FALSE, compact.get("virtualMatrixScoreTrusted"));
        assertEquals(Boolean.FALSE, compact.get("virtualMatrixActionAllowed"));
        assertTrue(List.class.isAssignableFrom(compact.get("virtualMatrixHotChunks").getClass()));
        assertFalse(controller.history(5).isEmpty());
    }

    @Test
    void longValueParseFailureLeavesFixedStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/debug/ai/DebugAiMetricsService.java"));

        assertTrue(source.contains("traceSuppressed(\"debugAiMetrics.longValue\", ignore);"));
        assertTrue(source.contains(
                "TraceStore.put(\"debug.ai.metrics.suppressed.\" + safeStage, true);"));
    }

    @Test
    void invalidLongMetricUsesStableReasonCodeWithoutRawValue() {
        String rawMetric = "private latency ownerToken=fake-token";
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(System.currentTimeMillis(), DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO,
                Map.of("latencyMs", rawMetric, "result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        service.snapshot(5, 60_000);

        assertEquals("invalid_number",
                TraceStore.get("debug.ai.metrics.suppressed.debugAiMetrics.longValue.errorType"));
        assertEquals("debugAiMetrics.longValue", TraceStore.get("debug.ai.metrics.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("debug.ai.metrics.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawMetric));
    }

    private static DebugEvent event(long tsMs,
                                    DebugProbeType probe,
                                    DebugEventLevel level,
                                    Map<String, Object> data) {
        return new DebugEvent(
                "event-" + tsMs + "-" + probe,
                Instant.ofEpochMilli(tsMs),
                tsMs,
                level,
                probe,
                "fingerprint-" + probe + "-" + tsMs,
                "message",
                null,
                null,
                null,
                "test",
                "test",
                data,
                null,
                null);
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static final class StaticDebugEventStore extends DebugEventStore {
        private final List<DebugEvent> events = new ArrayList<>();

        @Override
        public List<DebugEvent> list(int limit) {
            int safeLimit = Math.max(1, Math.min(limit, events.size()));
            return List.copyOf(events.subList(0, safeLimit));
        }
    }
}
