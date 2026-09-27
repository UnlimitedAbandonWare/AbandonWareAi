package com.example.lms.resilience;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.learning.virtualpoint.VirtualPoint;
import com.example.lms.learning.virtualpoint.VirtualPointService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFailureBlackboxServiceTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void scalarValueHelpersLiveOutsideBlackboxLargeFile() throws Exception {
        Path servicePath = Path.of("main/java/com/example/lms/resilience/RagFailureBlackboxService.java");
        Path helperPath = Path.of("main/java/com/example/lms/resilience/RagFailureBlackboxValues.java");

        String service = Files.readString(servicePath);

        assertTrue(Files.exists(helperPath), "scalar helper should reduce RagFailureBlackboxService file size");
        String helper = Files.readString(helperPath);
        assertTrue(service.contains("import static com.example.lms.resilience.RagFailureBlackboxValues.*;"));
        assertFalse(service.contains("private static long toLong("));
        assertFalse(service.contains("private static double asDouble("));
        assertFalse(service.contains("private static String safeLabel("));
        assertFalse(service.contains("private static String safePublicLabel("));
        assertFalse(service.contains("private static double clamp01("));
        assertTrue(helper.contains("final class RagFailureBlackboxValues"));
        assertTrue(helper.contains("static String safePublicLabel"));
    }

    @Test
    void blackboxNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String values = Files.readString(Path.of(
                "main/java/com/example/lms/resilience/RagFailureBlackboxValues.java"));
        String scorecard = Files.readString(Path.of(
                "main/java/com/example/lms/resilience/RagConstitutionalScorecard.java"));
        String service = Files.readString(Path.of(
                "main/java/com/example/lms/resilience/RagFailureBlackboxService.java"));

        assertParserCatchNarrowed(values, "static long toLong(Object value)");
        assertParserCatchNarrowed(values, "static double asDouble(Object value, double fallback)");
        assertTrue(values.contains("traceSkipped(\"values_long_parse\""),
                "values long parser fallback should leave a scanner-visible breadcrumb");
        assertTrue(values.contains("traceSkipped(\"values_double_parse\""),
                "values double parser fallback should leave a scanner-visible breadcrumb");
        assertTrue(values.contains("[AWX][blackbox][values] trace skipped"),
                "values parser fallback log should be redacted and stage-specific");
        assertParserCatchNarrowed(scorecard, "private static long toLong(Object value)");
        assertParserCatchNarrowed(scorecard, "private static double asDouble(Object value, double fallback)");
        assertTrue(scorecard.contains("traceSkipped(\"scorecard_long_parse\""),
                "scorecard long parser fallback should leave a scanner-visible breadcrumb");
        assertTrue(scorecard.contains("traceSkipped(\"scorecard_double_parse\""),
                "scorecard double parser fallback should leave a scanner-visible breadcrumb");
        assertTrue(scorecard.contains("[AWX][blackbox][scorecard] trace skipped"),
                "scorecard parser fallback log should be redacted and stage-specific");
        assertParserCatchNarrowed(service, "private static int parseTile(Object raw)");
    }

    @Test
    void parseTileFallbackLeavesStableRedactedTraceBreadcrumb() throws Exception {
        Method method = RagFailureBlackboxService.class.getDeclaredMethod("parseTile", Object.class);
        method.setAccessible(true);
        String raw = "townerToken=raw-secret";

        Object parsed = method.invoke(null, raw);

        assertEquals(0, parsed);
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.suppressed.parse_tile"));
        assertEquals("invalid_number", TraceStore.get("blackbox.suppressed.parse_tile.errorType"));
        assertEquals("matrix_tile", TraceStore.get("blackbox.suppressed.parse_tile.detail"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(raw), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
    }

    @Test
    void cooldownSignalProbeFailureLeavesRedactedBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/resilience/RagFailureBlackboxService.java"));

        assertTrue(source.contains("traceSkipped(\"cooldown_signal\""),
                "cooldown signal probe fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"risk_trace_projection\""),
                "risk trace projection fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"virtual_point_write\""),
                "virtual point persistence fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"graph_recommendation_trace\""),
                "graph recommendation trace fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"graph_persistence\""),
                "graph persistence fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"graph_recommendation_lookup\""),
                "graph recommendation lookup fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"virtual_point_prior_lookup\""),
                "virtual point prior lookup fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"virtual_point_prior_trace\""),
                "virtual point prior trace fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"virtual_point_current_signal_trace\""),
                "virtual point current-signal trace fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"pattern_id\""),
                "pattern id fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"snapshot_trace\""),
                "TraceStore snapshot fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"parse_tile\""),
                "matrix tile parser fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("traceSkipped(\"debug_emit_rate_limit\""),
                "debug emit rate-limit fallback should leave a scanner-visible breadcrumb");
        assertTrue(source.contains("[AWX][blackbox] trace skipped"),
                "cooldown signal fallback log should use redacted stage/detail/errorType fields");
    }

    @Test
    void kgNeo4jDegradationSignalLivesOutsideBlackboxLargeFile() throws Exception {
        Path servicePath = Path.of("main/java/com/example/lms/resilience/RagFailureBlackboxService.java");
        Path signalPath = Path.of("main/java/com/example/lms/resilience/KgNeo4jDegradationSignal.java");

        String service = Files.readString(servicePath);

        assertTrue(Files.exists(signalPath), "KG degradation signal should reduce RagFailureBlackboxService file size");
        String signal = Files.readString(signalPath);
        assertFalse(service.contains("private record KgNeo4jDegradationSignal"));
        assertTrue(signal.contains("record KgNeo4jDegradationSignal"));
        assertTrue(signal.contains("static KgNeo4jDegradationSignal none()"));
    }

    @Test
    void projectsAblationTopContributorToBlackboxTrace() {
        TraceStore.put("ablation.events.count", 3);
        TraceStore.put("ablation.probabilities", List.of(
                Map.of("step", "web.await", "guard", "missing_future", "p", 0.82d, "delta", 0.08d),
                Map.of("step", "final", "guard", "citation", "p", 0.18d, "delta", 0.03d)));

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("test");

        assertEquals("missing_future", snapshot.dominantFailure());
        assertEquals("web_await_bypass", snapshot.restoreAction());
        assertEquals("missing_future", TraceStore.get("blackbox.risk.dominantFailure"));
        assertEquals("web_await_bypass", TraceStore.get("blackbox.risk.restoreAction"));
        assertTrue(TraceStore.get("blackbox.risk.topContributor") instanceof Map<?, ?>);
        assertTrue(((Number) TraceStore.get("blackbox.risk.riskScore")).doubleValue() > 0.65d);
    }

    @Test
    void traceAnchorDropProjectsRouteCorrectionAndBlackboxTrace() {
        Map<String, Object> anchor = Map.of(
                "component", "web",
                "stage", "web.await",
                "lane", "WEB",
                "anchorHash", "abc123",
                "evidenceDigestHash", "def456",
                "matrixTile", 1,
                "routeHint", "fail_soft_fallback",
                "p", 0.95d,
                "delta", 0.75d,
                "expectedDelta", 0.7125d);
        TraceStore.put("ablation.traceAnchor.rows", List.of(anchor));
        TraceStore.put("ablation.traceAnchor.top", anchor);
        TraceStore.put("ablation.traceAnchor.maxExpectedDelta", 0.7125d);
        TraceStore.put("ablation.traceAnchor.maxP", 0.95d);
        TraceStore.put("ablation.traceAnchor.routeCorrectionNeed", 0.7125d);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("trace-anchor-test");

        assertEquals("provider_disabled", snapshot.dominantFailure());
        assertEquals("disable_provider_failsoft", snapshot.restoreAction());
        assertTrue(((Number) snapshot.matrix().get("q_anchor_drop_pressure")).doubleValue() >= 0.70d);
        assertTrue(((Number) snapshot.matrix().get("q_anchor_lane_pressure")).doubleValue() >= 0.90d);
        assertTrue(((Number) snapshot.matrix().get("q_route_correction_need")).doubleValue() >= 0.70d);
        assertTrue(TraceStore.get("blackbox.risk.traceAnchor") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> projected = (Map<String, Object>) TraceStore.get("blackbox.risk.traceAnchor");
        assertEquals("abc123", projected.get("anchorHash"));
        assertEquals("def456", projected.get("evidenceDigestHash"));
        assertEquals("fail_soft_fallback", projected.get("routeHint"));
        assertFalse(projected.toString().contains("raw query"));
    }

    @Test
    void ranksProviderDisabledOverAfterFilterAndNeverProjectsRawSecrets() {
        TraceStore.put("rawQuery", "raw query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("test");

        assertEquals("provider_disabled", snapshot.dominantFailure());
        assertEquals("disable_provider_failsoft", snapshot.restoreAction());
        assertEquals("QUARANTINE", snapshot.vectorDecision());
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
        assertTrue(snapshot.rank().stream()
                .anyMatch(row -> "after_filter_starvation".equals(row.get("failureClass"))));
    }

    @Test
    void existingProjectionSanitizesPublicMapKeyLabelsAndValues() {
        TraceStore.put("blackbox.risk.riskScore", 0.72d);
        TraceStore.put("blackbox.risk.dominantFailure", "provider_disabled");
        TraceStore.put("blackbox.risk.restoreAction", "disable_provider_failsoft");
        TraceStore.put("blackbox.risk.matrix", Map.of(
                "ownerToken", "owner-token-must-not-leak",
                "apiKey", "api-key-must-not-leak",
                "safeCount", 2));

        RagFailureBlackboxService service = new RagFailureBlackboxService(null, null, null);
        ReflectionTestUtils.setField(service, "enabled", true);
        RagFailureBlackboxService.Snapshot snapshot = service.currentOrRefresh("existing-projection-test");

        String publicPayload = String.valueOf(snapshot.matrix());
        assertFalse(publicPayload.contains("ownerToken"), publicPayload);
        assertFalse(publicPayload.contains("apiKey"), publicPayload);
        assertFalse(publicPayload.contains("owner-token-must-not-leak"), publicPayload);
        assertFalse(publicPayload.contains("api-key-must-not-leak"), publicPayload);
        assertEquals(2, snapshot.matrix().get("safeCount"));
        assertTrue(snapshot.matrix().keySet().stream().anyMatch(key -> key.startsWith("hash:")), publicPayload);
    }

    @Test
    void projectsProviderCancellationAsSeparateWebFailureClass() {
        TraceStore.put("rawQuery", "raw cancelled query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("web.brave.cancelled", true);
        TraceStore.put("web.brave.exceptionType", "cancelled");
        TraceStore.put("web.brave.timeout", false);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("cancelled-provider-test");

        assertEquals("cancelled", snapshot.dominantFailure());
        assertEquals("cooldown_reorder", snapshot.restoreAction());
        assertEquals(1L, ((Number) snapshot.matrix().get("cancelled.count")).longValue());
        assertEquals("cancelled", TraceStore.get("blackbox.risk.dominantFailure"));
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw cancelled query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsTavilyProviderTimeoutAsSeparateWebFailureClass() {
        TraceStore.put("rawQuery", "raw timeout query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("web.tavily.timeout", true);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("tavily-timeout-test");

        assertEquals("timeout", snapshot.dominantFailure());
        assertEquals("cooldown_reorder", snapshot.restoreAction());
        assertTrue(((Number) snapshot.matrix().get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertEquals("timeout", TraceStore.get("blackbox.risk.dominantFailure"));
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw timeout query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsLlmRetryBudgetUpstream5xxWithEvidenceGateContext() {
        TraceStore.put("rawQuery", "raw failed chat query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("llm.error.code", "UPSTREAM_5XX");
        TraceStore.put("llm.retryBudget.exceeded", true);
        TraceStore.put("llm.retryBudget.exceeded.count", 2);
        TraceStore.put("llm.retryBudget.elapsedMs", 211438L);
        TraceStore.put("llm.retryBudget.budgetMs", 180600L);
        TraceStore.put("rag.evidence.promotion.promotedCount", 0);
        TraceStore.put("rag.evidence.promotion.evidenceGatePassed", false);
        TraceStore.put("rag.evidence.promotion.disabledReason", "evidence_gate_blocked");

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("fs-log-replay");

        assertEquals("llm_upstream_retry_exhausted", snapshot.dominantFailure());
        assertEquals("llm_route_degrade", snapshot.restoreAction());
        assertEquals("llm_route", snapshot.hotspot());
        assertTrue(snapshot.highRisk());
        assertTrue(((Number) snapshot.matrix().get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(snapshot.rank().stream()
                .anyMatch(row -> "evidence_gate".equals(row.get("failureClass"))));
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw failed chat query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsBlankLocalLlmResponseAsRouteDegradeSignal() {
        TraceStore.put("rawQuery", "raw blank chat query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("llm.call.blank", true);
        TraceStore.put("llm.error.code", "BLANK_RESPONSE");
        TraceStore.put("llm.call.blank.count", 1);
        TraceStore.put("llm.upstream.pressure", 1.0d);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("blank-llm-replay");

        assertEquals("silent_failure", snapshot.dominantFailure());
        assertEquals("llm_route_degrade", snapshot.restoreAction());
        assertEquals("llm_route", snapshot.hotspot());
        assertTrue(snapshot.highRisk());
        assertTrue(((Number) snapshot.matrix().get("q_llm_upstream_pressure")).doubleValue() > 0.0d);
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw blank chat query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsFastLlmClientBlankAsRouteDegradeSignal() {
        TraceStore.put("rawQuery", "raw fast blank query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("llm.client.blank", true);
        TraceStore.put("llm.client.stage", "disambiguation:clarify");
        TraceStore.put("llm.client.outputLength", 0);
        TraceStore.put("llm.upstream.pressure", 1.0d);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("fast-client-blank-replay");

        assertEquals("silent_failure", snapshot.dominantFailure());
        assertEquals("llm_route_degrade", snapshot.restoreAction());
        assertEquals("llm_route", snapshot.hotspot());
        assertTrue(snapshot.highRisk());
        assertTrue(((Number) snapshot.matrix().get("q_llm_upstream_pressure")).doubleValue() > 0.0d);
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw fast blank query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsFastLlmClientFailureAsRouteDegradeSignal() {
        TraceStore.put("rawQuery", "raw fast failure query must not leak");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("llm.client.failed", true);
        TraceStore.put("llm.client.stage", "disambiguation:clarify");
        TraceStore.put("llm.client.errorType", "UnsupportedOperationException");
        TraceStore.put("llm.client.errorHash", "hash:abcdef123456");
        TraceStore.put("llm.client.errorLength", 15);
        TraceStore.put("llm.upstream.pressure", 1.0d);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("fast-client-failure-replay");

        assertEquals("silent_failure", snapshot.dominantFailure());
        assertEquals("llm_route_degrade", snapshot.restoreAction());
        assertEquals("llm_route", snapshot.hotspot());
        assertTrue(snapshot.highRisk());
        assertTrue(((Number) snapshot.matrix().get("q_llm_upstream_pressure")).doubleValue() > 0.0d);
        String publicPayload = snapshot.toString() + TraceStore.get("blackbox.risk.rank");
        assertFalse(publicPayload.contains("raw fast failure query must not leak"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
        assertFalse(publicPayload.contains("UnsupportedOperationException"));
    }

    @Test
    void projectsTavilyProviderZeroAndAfterFilterSignals() {
        TraceStore.put("web.tavily.providerDisabled", true);
        RagFailureBlackboxService.Snapshot disabled =
                RagFailureBlackboxService.projectCurrentTrace("tavily-disabled-test");
        assertEquals("provider_disabled", disabled.dominantFailure());
        assertTrue(((Number) disabled.matrix().get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(disabled.rank().stream()
                .anyMatch(row -> "provider_disabled".equals(row.get("failureClass"))));

        TraceStore.clear();
        TraceStore.put("web.tavily.zeroResults", true);
        RagFailureBlackboxService.Snapshot zero =
                RagFailureBlackboxService.projectCurrentTrace("tavily-zero-test");
        assertEquals("web_starvation", zero.dominantFailure());
        assertTrue(((Number) zero.matrix().get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(zero.rank().stream()
                .anyMatch(row -> "web_starvation".equals(row.get("failureClass"))));

        TraceStore.clear();
        TraceStore.put("web.tavily.returnedCount", 4);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot starved =
                RagFailureBlackboxService.projectCurrentTrace("tavily-starved-test");
        assertEquals("after_filter_starvation", starved.dominantFailure());
        assertTrue(((Number) starved.matrix().get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertEquals(1.0d, ((Number) starved.matrix().get("m1_source_mix_web")).doubleValue());
        assertTrue(starved.rank().stream()
                .anyMatch(row -> "after_filter_starvation".equals(row.get("failureClass"))));
    }

    @Test
    void canonicalStarvationFallbackTraceClassifiesAfterFilterStarvation() {
        RagFailureBlackboxService.Snapshot snapshot = RagFailureBlackboxService.analyze(Map.of(
                "starvationFallback.used", true,
                "starvationFallback.count", 2));

        assertEquals("after_filter_starvation", snapshot.dominantFailure());
        assertTrue(((Number) snapshot.matrix().get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(snapshot.rank().stream()
                .anyMatch(row -> "after_filter_starvation".equals(row.get("failureClass"))));
    }

    @Test
    void mapsContaminationToVectorQuarantine() {
        TraceStore.put("learning.validation.contaminationSignals",
                List.of("context_contamination_score"));

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("test");

        assertEquals("context_contamination", snapshot.dominantFailure());
        assertEquals("vector_quarantine", snapshot.restoreAction());
        assertEquals("QUARANTINE", TraceStore.get("blackbox.risk.vectorDecision"));
    }

    @Test
    void mapsHistoryContaminationToCorrectionTraceWithoutRawMemory() {
        TraceStore.put("prompt.memory.compressor.activated", true);
        TraceStore.put("prompt.memory.compressor.contaminationScore", 0.52d);
        TraceStore.put("prompt.memory.compressor.lineDropCount", 3);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("history-test");

        assertEquals("context_contamination", snapshot.dominantFailure());
        assertEquals("memory_history", snapshot.hotspot());
        assertEquals("vector_quarantine", snapshot.restoreAction());
        assertEquals("history_context_contamination", snapshot.decisionReason());
        assertEquals(0.52d, ((Number) TraceStore.get("blackbox.risk.historyContaminationScore")).doubleValue(),
                0.0001d);
        assertEquals("history_context_requery", TraceStore.get("blackbox.risk.historyCorrectionAction"));
    }

    @Test
    void projectsQuantitativeMatrixFromExistingTraceEvidence() {
        TraceStore.put("rag.eval.normalized", Map.of("balancedScore", 0.82d));
        TraceStore.put("rag.eval.thresholdBreaks", List.of(Map.of(
                "label", "stage_drop_high",
                "metric", "stageDrop",
                "value", 0.75d)));
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        TraceStore.put("citation.gate.failed", true);
        TraceStore.put("overdrive.narrow.input.count", 16);
        TraceStore.put("overdrive.narrow.output.count", 4);
        TraceStore.put("learning.feedback.vectorDecision", "QUARANTINE");
        TraceStore.put("learning.validation.contaminationSignals", List.of("context_contamination_score"));

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("matrix-test");

        Map<String, Object> matrix = snapshot.matrix();
        assertEquals("rag-matrix-v2", matrix.get("matrix.schemaVersion"));
        assertEquals(0.82d, ((Number) matrix.get("q_retrieval_health")).doubleValue(), 0.0001d);
        assertTrue(((Number) matrix.get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(((Number) matrix.get("q_gate_quality")).doubleValue() < 1.0d);
        assertEquals(0.75d, ((Number) matrix.get("q_stage_drop")).doubleValue(), 0.0001d);
        assertEquals(0.75d, ((Number) matrix.get("q_compression_efficiency")).doubleValue(), 0.0001d);
        assertTrue(((Number) matrix.get("q_learning_promotion_risk")).doubleValue() > 0.0d);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) matrix.get("_order");
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void projectsGpuGatewayAdmissionBlockToCooldownAndMatrix() {
        TraceStore.put("uaw.gpu-gateway.admission.blocked", true);
        TraceStore.put("uaw.gpu-gateway.admission.blocked.count", 3);
        TraceStore.put("uaw.gpu-gateway.admission.status", "unreachable");
        TraceStore.put("uaw.idle.skip.reason", "gpu_gateway_unreachable");
        TraceStore.put("uaw.idle.last", "uaw.idle.gpu-gateway.blocked");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("gpu-gateway-test");

        assertEquals("gpu_gateway_unreachable", snapshot.dominantFailure());
        assertEquals("cooldown_reorder", snapshot.restoreAction());
        assertEquals("gpu_gateway", snapshot.hotspot());
        assertEquals("desktop_gpu_gateway_unreachable", snapshot.decisionReason());
        assertEquals(1.0d, ((Number) snapshot.matrix().get("q_gpu_gateway_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.matrix().get("q_failsoft_pressure")).doubleValue(), 0.0001d);
        assertFalse(String.valueOf(snapshot).contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsGpuHardwareAdmissionPressureToDemotionAndMatrix() {
        TraceStore.put("uaw.gpu-hardware.status", "ok");
        TraceStore.put("uaw.gpu-hardware.detectedCount", 2);
        TraceStore.put("uaw.gpu-hardware.hasRtx3090", true);
        TraceStore.put("uaw.gpu-hardware.hasRtx3060", true);
        TraceStore.put("uaw.gpu-hardware.admission.status", "degraded");
        TraceStore.put("uaw.gpu-hardware.admission.reason", "gpu_memory_pressure");
        TraceStore.put("uaw.gpu-hardware.admission.pressureLevel", "warn");
        TraceStore.put("uaw.gpu-hardware.admission.retrainBlocked", true);
        TraceStore.put("uaw.gpu-hardware.admission.retrainAllowed", false);
        TraceStore.put("uaw.gpu-hardware.admission.rerankAllowed", true);
        TraceStore.put("ownerToken", "owner-token-must-not-leak");

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("gpu-hardware-test");

        assertEquals("gpu_hardware_pressure", snapshot.dominantFailure());
        assertEquals("demote_heavy_work", snapshot.restoreAction());
        assertEquals("gpu_hardware", snapshot.hotspot());
        assertEquals("gpu_hardware_gpu_memory_pressure", snapshot.decisionReason());
        assertEquals(0.55d, ((Number) snapshot.matrix().get("q_gpu_hardware_pressure")).doubleValue(), 0.0001d);
        assertTrue(((Number) snapshot.matrix().get("q_failsoft_pressure")).doubleValue() >= 0.55d);
        assertFalse(String.valueOf(snapshot).contains("owner-token-must-not-leak"));
    }

    @Test
    void projectsKgNeo4jDegradationToBlackboxRiskAndMatrix() {
        TraceStore.put("rag.eval.kgAxis.signals", List.of("kg_neo4j_failed", "kg_neo4j_degraded"));
        TraceStore.put("rag.eval.kgAxis.neo4jStatus", "failed");
        TraceStore.put("rag.eval.kgAxis.neo4jDisabledReason", "neo4j_query_failed");
        TraceStore.put("rag.eval.kgAxis.neo4jFailureClass", "silent-failure");
        TraceStore.put("retrieval.kg.neo4j.failureClass", "silent-failure");
        TraceStore.put("rawQuery", "raw kg query must not leak");

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("kg-neo4j-test");

        assertEquals("kg_neo4j_degraded", snapshot.dominantFailure());
        assertEquals("kg_dependency_fallback", snapshot.restoreAction());
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.kgNeo4jDegraded"));
        assertEquals("silent-failure", TraceStore.get("blackbox.risk.kgNeo4jFailureClass"));
        assertEquals(1.0d, ((Number) snapshot.matrix().get("q_kg_degradation_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.matrix().get("q_graph_dependency_pressure")).doubleValue(), 0.0001d);
        assertFalse(String.valueOf(snapshot).contains("raw kg query must not leak"));
    }

    @Test
    void projectsSparseNodeKgAxisFailureToBlackboxRiskAndMatrixWithoutRawQuery() {
        TraceStore.put("rag.eval.kgAxis.signals", List.of("kg_sparse_node_failed", "kg_sparse_node_no_graph_path"));
        TraceStore.put("rag.eval.kgAxis.sparseNodeStatus", "no_graph_path");
        TraceStore.put("rag.eval.kgAxis.sparseNodeDisabledReason", "missing_graph_path");
        TraceStore.put("rag.eval.kgAxis.sparseNodeFailureClass", "sparse_node_no_graph_path");
        TraceStore.put("rawQuery", "raw sparse node query must not leak");

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("kg-sparse-node-test");

        assertEquals("kg_neo4j_degraded", snapshot.dominantFailure());
        assertEquals("kg_dependency_fallback", snapshot.restoreAction());
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.kgNeo4jDegraded"));
        assertEquals("sparse_node_no_graph_path", TraceStore.get("blackbox.risk.kgNeo4jFailureClass"));
        assertEquals(1.0d, ((Number) snapshot.matrix().get("q_kg_degradation_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.matrix().get("q_graph_dependency_pressure")).doubleValue(), 0.0001d);
        String publicPayload = String.valueOf(snapshot)
                + TraceStore.get("blackbox.risk.rank")
                + TraceStore.get("blackbox.risk.kgNeo4jFailureClass");
        assertFalse(publicPayload.contains("raw sparse node query must not leak"));
    }

    @Test
    void matrixPrefersRagEvalSourceDiversityAndStageDropMap() {
        TraceStore.put("rag.eval.sourceDiversity", Map.of(
                "WEB", 3,
                "VECTOR-FALLBACK", 1,
                "KG", 2,
                "BM25", 9));
        TraceStore.put("web.naver.returnedCount", 99);
        TraceStore.put("vector.returnedCount", 99);
        TraceStore.put("rag.eval.stageDrop", Map.of(
                "pool->fused", 0.70d,
                "fused->final", 0.20d));
        TraceStore.put("rag.eval.stageSourceDiversity", Map.of(
                "pool", Map.of("WEB", 2, "VECTOR", 2, "KG", 2),
                "fused", Map.of("WEB", 4),
                "final", Map.of("WEB", 1)));
        TraceStore.put("overdrive.narrow.failSoft", true);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("matrix-source-test");

        Map<String, Object> matrix = snapshot.matrix();
        assertEquals(0.50d, ((Number) matrix.get("m1_source_mix_web")).doubleValue(), 0.0001d);
        assertEquals(1.0d / 6.0d, ((Number) matrix.get("m1_source_mix_vector")).doubleValue(), 0.0001d);
        assertEquals(2.0d / 6.0d, ((Number) matrix.get("m1_source_mix_kg")).doubleValue(), 0.0001d);
        assertEquals(0.70d, ((Number) matrix.get("q_stage_drop")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) matrix.get("q_stage_source_collapse")).doubleValue(), 0.0001d);
        assertTrue(((Number) matrix.get("q_failsoft_pressure")).doubleValue() > 0.0d);
    }

    @Test
    void classifiesModelGuardEvidenceBeforeTrueModelRequired() {
        RagFailureBlackboxService.Snapshot evidence =
                RagFailureBlackboxService.analyze(Map.of(
                        "ablation.probabilities", List.of(Map.of(
                                "step", "model_guard",
                                "guard", "citation escalation",
                                "p", 0.90d,
                                "delta", 0.10d))));

        assertEquals("evidence_gate", evidence.dominantFailure());
        assertEquals("evidence_gate_strict", evidence.restoreAction());

        RagFailureBlackboxService.Snapshot model =
                RagFailureBlackboxService.analyze(Map.of(
                        "ablation.probabilities", List.of(Map.of(
                                "step", "qtx.llm",
                                "guard", "MODEL_REQUIRED blank_model",
                                "p", 0.90d,
                                "delta", 0.10d))));

        assertEquals("model_required", model.dominantFailure());
        assertEquals("llm_route_degrade", model.restoreAction());
    }

    @Test
    void projectsJbCbAndLossContributionFromAblationAnchorStack() {
        TraceStore.put("ablation.anchor.primaryStack", List.of(
                Map.of("component", "gate", "stage", "model_guard", "lane", "GATE",
                        "eventCount", 1, "p", 0.80d, "expectedDelta", 0.20d,
                        "topGuard", "citation", "topAnchorHash", "abc123"),
                Map.of("component", "web", "stage", "web.await", "lane", "WEB",
                        "eventCount", 1, "p", 0.70d, "expectedDelta", 0.10d,
                        "topGuard", "provider_disabled", "topAnchorHash", "def456")));
        TraceStore.put("ablation.anchor.secondaryStack", List.of(
                Map.of("component", "gate", "step", "model_guard", "guard", "citation",
                        "p", 0.80d, "expectedDelta", 0.20d, "anchorHash", "abc123"),
                Map.of("component", "web", "step", "web.await", "guard", "provider_disabled",
                        "p", 0.70d, "expectedDelta", 0.10d, "anchorHash", "def456")));
        TraceStore.put("web.naver.providerDisabled", true);

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("jb-cb-test");

        assertTrue(((Number) TraceStore.get("blackbox.risk.jbPressure")).doubleValue() >= 0.70d);
        assertTrue(((Number) TraceStore.get("blackbox.risk.cbPressure")).doubleValue() >= 0.70d);
        assertEquals(0.20d, ((Number) snapshot.matrix().get("m6_loss_contribution")).doubleValue(), 0.0001d);
        assertTrue(TraceStore.get("blackbox.risk.anchorStack") instanceof List<?>);
    }

    @Test
    void constitutionalScorecardProjectsFiveChannelsAndRoutingDecision() {
        TraceStore.put("rawQuery", "secret question text must not leak");
        TraceStore.put("rag.eval.normalized", Map.of(
                "retrievalHitRate", 0.15d,
                "evidenceCoverage", 0.20d,
                "sourceDiversity", 0.10d,
                "balancedScore", 0.20d));
        TraceStore.put("rag.eval.thresholdBreaks", List.of(
                Map.of("label", "retrieval_starvation", "metric", "retrievalHitRate", "severity", 0.45d),
                Map.of("label", "weak_evidence", "metric", "evidenceCoverage", "severity", 0.50d),
                Map.of("label", "policy_risk_high", "metric", "policyRisk", "value", 0.91d)));
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        TraceStore.put("citation.gate.failed", true);
        TraceStore.put("guard.policyRisk", 0.91d);
        TraceStore.put("web.await.events.timeout.count", 2);

        RagFailureBlackboxService.projectCurrentTrace("constitutional-scorecard-test");

        assertTrue(TraceStore.get("blackbox.risk.scorecard") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> scorecard = (Map<String, Object>) TraceStore.get("blackbox.risk.scorecard");
        assertEquals("constitutional-scorecard-v1", scorecard.get("schemaVersion"));
        assertEquals("BLOCK", scorecard.get("routingDecision"));
        assertEquals(Boolean.TRUE, scorecard.get("blockRecommended"));
        assertEquals("policyRisk", scorecard.get("dominantChannel"));

        assertTrue(scorecard.get("channels") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) scorecard.get("channels");
        assertEquals(5, channels.size());
        for (String key : List.of("searchQuality", "evidenceGrounding", "hallucinationLikelihood",
                "policyRisk", "executionRisk")) {
            assertBounded(channels.get(key));
        }
        assertTrue(((Number) channels.get("policyRisk")).doubleValue() >= 0.90d);
        assertTrue(((Number) scorecard.get("compositeRisk")).doubleValue() >= 0.70d);
        assertEquals("BLOCK", TraceStore.get("blackbox.risk.routingDecision"));
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.blockRecommended"));
        assertEquals("BLOCK", TraceStore.get("rag.blackbox.routingDecision"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.blackbox.blockRecommended"));
        assertEquals("BLOCK", FaithfulnessMetricSnapshotStore.get("rag.blackbox.routingDecision"));
        assertFalse(String.valueOf(scorecard).contains("secret question text must not leak"));
    }

    @Test
    void constitutionalScorecardTreatsTavilyTimeoutAsExecutionRiskWithoutSnapshotMatrix() {
        Map<String, Object> scorecard = RagConstitutionalScorecard.project(
                Map.of("web.tavily.timeout", true),
                null);

        assertTrue(scorecard.get("channels") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) scorecard.get("channels");
        assertTrue(((Number) channels.get("executionRisk")).doubleValue() > 0.0d);
    }

    @Test
    void constitutionalScorecardIgnoresNonFiniteCountInputsForExecutionRisk() {
        Map<String, Object> scorecard = RagConstitutionalScorecard.project(
                Map.of(
                        "rag.eval.normalized", Map.of(
                                "balancedScore", 1.0d,
                                "retrievalHitRate", 1.0d,
                                "evidenceCoverage", 1.0d),
                        "web.await.events.rateLimit.count", Double.POSITIVE_INFINITY),
                null);

        assertTrue(scorecard.get("channels") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) scorecard.get("channels");
        assertEquals(0.0d, ((Number) channels.get("executionRisk")).doubleValue(), 0.0001d);
        assertEquals("ALLOW", scorecard.get("routingDecision"));
    }

    @Test
    void chatWorkflowProjectsScorecardBeforeEvidenceGuardDecision() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int projection = source.indexOf("RagFailureBlackboxService.projectCurrentTrace(");
        int projectionWhere = source.indexOf("\"ChatWorkflow.evidenceGuard.preDecision\"", projection);
        int guardDecision = source.indexOf("guard.guardWithEvidence(draftBeforeGuard");

        assertTrue(projection >= 0,
                "ChatWorkflow must project the constitutional scorecard before EvidenceAwareGuard consumes it");
        assertTrue(projectionWhere > projection,
                "ChatWorkflow scorecard projection must use the evidence-guard pre-decision label");
        assertTrue(guardDecision >= 0, "ChatWorkflow guard decision seam must remain present");
        assertTrue(projectionWhere < guardDecision,
                "constitutional scorecard projection must happen before guardWithEvidence");
    }

    @Test
    void chatWorkflowProjectsScorecardAfterPromptBuildBeforeLlmCall() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int promptBuild = source.indexOf("\"ChatWorkflow.promptBuild\"");
        int projection = source.indexOf("\"ChatWorkflow.promptBuild.postProjection\"", promptBuild);
        int llmCall = source.indexOf("TimedChatModelCaller.chat(", promptBuild);

        assertTrue(promptBuild >= 0, "PromptBuilder trace seam must remain present");
        assertTrue(projection > promptBuild,
                "main chat path must project scorecard after PromptBuilder even when evidenceDocs is empty");
        assertTrue(llmCall > projection,
                "scorecard projection should happen before the timed final LLM call path");
    }

    @Test
    void graphRecommendationStaysFailSoftWhenDisabled() {
        RagFailureBlackboxService service = service(true, provider(new DebugEventStore()));
        TraceStore.put("web.naver.providerDisabled", true);

        service.refresh("graph-disabled");

        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.graphRecommendation.enabled"));
        assertEquals("disabled", TraceStore.get("blackbox.risk.graphRecommendation.reason"));
        assertEquals("observe_only", TraceStore.get("blackbox.risk.graphRecommendation.restoreAction"));
    }

    @Test
    void blackboxReasonDiagnosticsUseSafeHelpers() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/resilience/RagFailureBlackboxService.java"));

        assertFalse(source.contains("TraceStore.put(PREFIX + \"decisionReason\", snapshot.decisionReason());"));
        assertFalse(source.contains("TraceStore.put(PREFIX + \"graphRecommendation.reason\", rec.reason());"));
        assertFalse(source.contains("safeLabel(top.reason(), \"similar_failure_path\")"));
        assertFalse(source.contains("data.put(\"decisionReason\", snapshot.decisionReason());"));
        assertFalse(source.contains("out.put(\"reason\", decisionReason);"));
        assertTrue(source.contains(
                "TraceStore.put(PREFIX + \"decisionReason\", SafeRedactor.traceLabelOrFallback(snapshot.decisionReason(), \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(PREFIX + \"graphRecommendation.reason\", SafeRedactor.traceLabelOrFallback(rec.reason(), \"\"));"));
        assertTrue(source.contains(
                "data.put(\"decisionReason\", SafeRedactor.traceLabelOrFallback(snapshot.decisionReason(), \"unknown\"));"));
        assertTrue(source.contains("out.put(\"reason\", SafeRedactor.traceLabelOrFallback(decisionReason, \"unknown\"));"));
    }

    @Test
    void blackboxPublicLabelHashesFreeTextAndPreservesCanonicalCodes() throws Exception {
        String freeText = RagFailureBlackboxValues.safePublicLabel("student private risk reason", "signal");
        String code = RagFailureBlackboxValues.safePublicLabel("Provider_Disabled", "none");
        String fallback = RagFailureBlackboxValues.safePublicLabel("", "SHADOW_REVIEW");

        assertTrue(freeText.startsWith("hash:"), freeText);
        assertFalse(freeText.contains("student"), freeText);
        assertEquals("provider_disabled", code);
        assertEquals("shadow_review", fallback);
    }

    @Test
    void graphRecommendationReasonUsesTraceLabel() {
        String privateReason = "private graph recommendation reason with student detail";
        FakeGraphClient graph = new FakeGraphClient(List.of(new Neo4jKnowledgeGraphClient.RecoveryRecommendation(
                "vector_quarantine", "prior-pattern", 0.90d, privateReason)));
        RagFailureBlackboxService service = service(true, provider(new DebugEventStore()), null, graph);
        ReflectionTestUtils.setField(service, "graphEnabled", true);
        ReflectionTestUtils.setField(service, "graphMinConfidence", 0.65d);
        ReflectionTestUtils.setField(service, "graphTopK", 3);
        TraceStore.put("web.naver.providerDisabled", true);

        service.refresh("graph-private-reason");

        Object stored = TraceStore.get("blackbox.risk.graphRecommendation.reason");
        assertTrue(String.valueOf(stored).startsWith("hash:"), String.valueOf(stored));
        assertFalse(TraceStore.getAll().toString().contains(privateReason));
        assertFalse(TraceStore.getAll().toString().contains("student detail"));
    }

    @Test
    void graphRecommendationNotAppliedDoesNotOverwriteObservedSnapshotOrGraphMemory() {
        FakeGraphClient graph = new FakeGraphClient(List.of(new Neo4jKnowledgeGraphClient.RecoveryRecommendation(
                "vector_quarantine", "prior-pattern", 0.90d, "similar_failure_path")));
        RagFailureBlackboxService service = service(true, provider(new DebugEventStore()), null, graph);
        ReflectionTestUtils.setField(service, "graphEnabled", true);
        ReflectionTestUtils.setField(service, "graphMinConfidence", 0.65d);
        ReflectionTestUtils.setField(service, "graphTopK", 3);
        TraceStore.put("web.naver.providerDisabled", true);

        RagFailureBlackboxService.Snapshot snapshot = service.refresh("graph-high-risk");

        assertEquals("provider_disabled", snapshot.dominantFailure());
        assertEquals("disable_provider_failsoft", snapshot.restoreAction());
        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.graphRecommendation.applied"));
        assertEquals(1, graph.recommendCount);
        assertEquals(1, graph.upsertCount);
        assertEquals("disable_provider_failsoft", graph.lastUpsertRestoreAction);
    }

    @Test
    void refreshOncePreservesFirstWhereUpdatesLastWhereAndEmitsDebugOnce() {
        RagFailureBlackboxService service = service(true, provider(new DebugEventStore()));
        TraceStore.put("web.naver.providerDisabled", true);

        service.refreshOnce("first");
        service.refreshOnce("second");

        assertEquals("first", TraceStore.get("blackbox.risk.firstWhere"));
        assertEquals("second", TraceStore.get("blackbox.risk.lastWhere"));
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.debugEmitted"));
        assertEquals("blackbox-risk-v2", TraceStore.get("blackbox.risk.projectionVersion"));
    }

    @Test
    void disabledServiceDoesNotWriteBlackboxProjection() {
        RagFailureBlackboxService service = service(false, provider(new DebugEventStore()));
        TraceStore.put("web.naver.providerDisabled", true);

        service.refreshOnce("disabled-consumer");

        assertNull(TraceStore.get("blackbox.risk.riskScore"));
        assertNull(TraceStore.get("blackbox.risk.dominantFailure"));
    }

    @Test
    void recurrenceBoostsPriorityWithoutClampingPublicRiskToOne() {
        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.analyze(Map.of(
                        "web.await.events.rateLimit.count", 80,
                        "citation.gate.failed", true));

        assertEquals("rate_limit", snapshot.dominantFailure());
        assertTrue(snapshot.priorityScore() > snapshot.riskScore());
        assertTrue(snapshot.riskScore() < 0.65d);
    }

    @Test
    void existingProjectionUsesRiskScoreFallbackForNonFinitePriority() {
        RagFailureBlackboxService service = service(true, provider(new DebugEventStore()));
        TraceStore.put("blackbox.risk.riskScore", 0.72d);
        TraceStore.put("blackbox.risk.priorityScore", "NaN");
        TraceStore.put("blackbox.risk.dominantFailure", "rate_limit");
        TraceStore.put("blackbox.risk.restoreAction", "cooldown_reorder");

        RagFailureBlackboxService.Snapshot snapshot = service.refreshOnce("existing-projection");

        assertEquals(0.72d, snapshot.riskScore(), 0.0001d);
        assertEquals(0.72d, snapshot.priorityScore(), 0.0001d);
        assertTrue(snapshot.highRisk());
    }

    @Test
    void nonFiniteCountTraceDoesNotBecomeRateLimitRisk() {
        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.analyze(Map.of("web.await.events.rateLimit.count", Double.POSITIVE_INFINITY));

        assertEquals("none", snapshot.dominantFailure());
        assertEquals(0.0d, snapshot.riskScore(), 0.0001d);
        assertFalse(snapshot.highRisk());
    }

    @Test
    void vectorDecisionTraceAloneDoesNotBecomeContaminationRootCause() {
        TraceStore.put("learning.feedback.vectorDecision", "QUARANTINE");
        TraceStore.put("learning.validation.contaminationSignals", List.of("quarantine_vector_decision"));

        RagFailureBlackboxService.Snapshot snapshot =
                RagFailureBlackboxService.projectCurrentTrace("test");

        assertEquals("none", snapshot.dominantFailure());
        assertEquals("SHADOW_REVIEW", snapshot.vectorDecision());
        assertEquals(0.0d, ((Number) snapshot.matrix().get("q_learning_promotion_risk")).doubleValue(), 0.0001d);
    }

    @Test
    void virtualPointPriorRequiresCurrentHistorySignal() {
        VirtualPointService virtualPoints = new VirtualPointService();
        virtualPoints.put("blackbox:prior", new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.90d,
                0.90d,
                "after_filter_starvation",
                "anchor_compression_topup",
                "prior-pattern",
                1L));
        RagFailureBlackboxService service = service(true, null, virtualPoints);
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "virtualPointMinSimilarity", 0.92d);
        TraceStore.put("webSearch.returnedCount", 5);

        RagFailureBlackboxService.Snapshot snapshot = service.refresh("prior-consumer");

        assertEquals("none", snapshot.dominantFailure());
        assertEquals("observe_only", snapshot.restoreAction());
        assertEquals("current_signal_required", TraceStore.get("blackbox.risk.virtualPoint.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.virtualPoint.applied"));
    }

    @Test
    void virtualPointPriorAppliesOnlyAfterCurrentHistorySignal() {
        VirtualPointService virtualPoints = new VirtualPointService();
        virtualPoints.put("blackbox:prior", new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.90d,
                0.90d,
                "context_contamination",
                "vector_quarantine",
                "prior-pattern",
                1L));
        RagFailureBlackboxService service = service(true, null, virtualPoints);
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "virtualPointMinSimilarity", 0.92d);
        TraceStore.put("webSearch.returnedCount", 5);
        TraceStore.put("prompt.memory.compressor.activated", true);
        TraceStore.put("prompt.memory.compressor.contaminationScore", 0.50d);

        RagFailureBlackboxService.Snapshot snapshot = service.refresh("prior-consumer");

        assertEquals("context_contamination", snapshot.dominantFailure());
        assertEquals("vector_quarantine", snapshot.restoreAction());
        assertEquals("virtual_point_prior", snapshot.decisionReason());
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.matched"));
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.applied"));
        assertEquals("prior-pattern", TraceStore.get("blackbox.risk.virtualPoint.priorPatternId"));
        Object observedAgeMs = TraceStore.get("blackbox.risk.virtualPoint.ageMs");
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
        assertTrue(observedAgeMs instanceof Number);
        assertTrue(((Number) observedAgeMs).longValue() > 0L);
        assertEquals("shadow_uncalibrated",
                TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
        assertEquals("legacy_applied",
                TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
    }

    @Test
    void virtualPointBelowThresholdReportsShadowAgeWithoutAuthority() {
        VirtualPointService virtualPoints = new VirtualPointService();
        virtualPoints.put("blackbox:prior", new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.50d,
                0.50d,
                "context_contamination",
                "vector_quarantine",
                "prior-pattern",
                1L));
        RagFailureBlackboxService service = service(true, null, virtualPoints);
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "virtualPointMinSimilarity", 0.92d);
        TraceStore.put("webSearch.returnedCount", 5);
        TraceStore.put("prompt.memory.compressor.activated", true);
        TraceStore.put("prompt.memory.compressor.contaminationScore", 0.50d);

        service.refresh("prior-below-threshold");

        Object observedAgeMs = TraceStore.get("blackbox.risk.virtualPoint.ageMs");
        assertEquals("prior_below_threshold",
                TraceStore.get("blackbox.risk.virtualPoint.reason"));
        assertEquals(Boolean.FALSE,
                TraceStore.get("blackbox.risk.virtualPoint.applied"));
        assertEquals(Boolean.TRUE,
                TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
        assertTrue(observedAgeMs instanceof Number);
        assertTrue(((Number) observedAgeMs).longValue() > 0L);
        assertEquals("shadow_uncalibrated",
                TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
        assertEquals("not_applied",
                TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
    }

    @Test
    void virtualPointFreshnessTraceIsRemovedWhenLaterRefreshHasNoPoint() {
        VirtualPointService virtualPoints = new VirtualPointService();
        virtualPoints.put("blackbox:prior", new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.90d,
                0.90d,
                "context_contamination",
                "vector_quarantine",
                "prior-pattern",
                1L));
        RagFailureBlackboxService service = service(true, null, virtualPoints);
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "virtualPointMinSimilarity", 0.92d);
        TraceStore.put("webSearch.returnedCount", 5);
        TraceStore.put("prompt.memory.compressor.activated", true);
        TraceStore.put("prompt.memory.compressor.contaminationScore", 0.50d);

        service.refresh("matched-first");

        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
        assertEquals("legacy_applied",
                TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));

        ReflectionTestUtils.setField(service, "historyCorrectionEnabled", false);
        service.refresh("disabled-second");

        assertEquals("history_correction_disabled",
                TraceStore.get("blackbox.risk.virtualPoint.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.virtualPoint.matched"));
        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.virtualPoint.applied"));
        Map<String, Object> trace = TraceStore.getAll();
        assertFalse(trace.containsKey("blackbox.risk.virtualPoint.ageKnown"));
        assertFalse(trace.containsKey("blackbox.risk.virtualPoint.ageMs"));
        assertFalse(trace.containsKey("blackbox.risk.virtualPoint.freshnessDisposition"));
        assertFalse(trace.containsKey("blackbox.risk.virtualPoint.decisionAuthority"));
    }

    @Test
    void virtualPointInvalidOrFutureSeenAtReportsUnknownShadowAge() {
        for (long seenAtMs : new long[]{0L, Long.MAX_VALUE}) {
            TraceStore.clear();
            VirtualPoint point = new VirtualPoint(
                    new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                    0.90d,
                    0.90d,
                    "context_contamination",
                    "vector_quarantine",
                    "prior-pattern",
                    seenAtMs);
            RagFailureBlackboxService service = service(true, null, fixedMatchService(point, 1.0d));
            ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
            ReflectionTestUtils.setField(service, "historyCorrectionRequireCurrentSignal", false);

            service.refresh("unknown-age");

            assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.matched"));
            assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
            assertEquals(-1L, TraceStore.get("blackbox.risk.virtualPoint.ageMs"));
            assertEquals("shadow_uncalibrated",
                    TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
            assertEquals("legacy_applied",
                    TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
        }
    }

    @Test
    void virtualPointObserveOnlyReportsShadowAgeWithoutAuthority() {
        VirtualPoint point = new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.90d,
                0.90d,
                "none",
                "observe_only",
                "prior-pattern",
                1L);
        RagFailureBlackboxService service = service(true, null, fixedMatchService(point, 1.0d));
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "historyCorrectionRequireCurrentSignal", false);

        service.refresh("observe-only");

        assertEquals("prior_observe_only", TraceStore.get("blackbox.risk.virtualPoint.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.virtualPoint.applied"));
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
        assertEquals("shadow_uncalibrated",
                TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
        assertEquals("not_applied", TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
    }

    @Test
    void virtualPointBoostBelowThresholdReportsShadowAgeWithoutAuthority() {
        VirtualPoint point = new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.66d,
                0.66d,
                "context_contamination",
                "vector_quarantine",
                "prior-pattern",
                1L);
        RagFailureBlackboxService service = service(true, null, fixedMatchService(point, 0.92d));
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "historyCorrectionRequireCurrentSignal", false);

        service.refresh("boost-below-threshold");

        assertEquals("boost_below_threshold", TraceStore.get("blackbox.risk.virtualPoint.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("blackbox.risk.virtualPoint.applied"));
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
        assertEquals("shadow_uncalibrated",
                TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
        assertEquals("not_applied", TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
    }

    @Test
    void virtualPointPriorAppliedSnapshotIsNotStoredBackAsObservedMemory() {
        VirtualPointService virtualPoints = new VirtualPointService();
        virtualPoints.put("blackbox:prior", new VirtualPoint(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                0.90d,
                0.90d,
                "context_contamination",
                "vector_quarantine",
                "prior-pattern",
                1L));
        RagFailureBlackboxService service = service(true, null, virtualPoints);
        ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
        ReflectionTestUtils.setField(service, "virtualPointMinSimilarity", 0.92d);
        ReflectionTestUtils.setField(service, "historyCorrectionRequireCurrentSignal", false);
        TraceStore.put("webSearch.returnedCount", 5);

        RagFailureBlackboxService.Snapshot snapshot = service.refresh("prior-consumer");

        assertEquals("context_contamination", snapshot.dominantFailure());
        assertEquals("vector_quarantine", snapshot.restoreAction());
        VirtualPoint storedObserved = virtualPoints.get("blackbox:" + snapshot.patternId()).orElseThrow();
        assertEquals("none", storedObserved.dominantFailure);
        assertEquals("observe_only", storedObserved.restoreAction);
    }

    private static RagFailureBlackboxService service(boolean enabled,
                                                    ObjectProvider<DebugEventStore> debugProvider) {
        return service(enabled, debugProvider, null);
    }

    private static RagFailureBlackboxService service(boolean enabled,
                                                    ObjectProvider<DebugEventStore> debugProvider,
                                                    VirtualPointService virtualPointService) {
        return service(enabled, debugProvider, virtualPointService, null);
    }

    private static RagFailureBlackboxService service(boolean enabled,
                                                    ObjectProvider<DebugEventStore> debugProvider,
                                                    VirtualPointService virtualPointService,
                                                    Neo4jKnowledgeGraphClient graphClient) {
        RagFailureBlackboxService service = new RagFailureBlackboxService(
                debugProvider,
                RagFailureBlackboxServiceTest.provider(virtualPointService),
                RagFailureBlackboxServiceTest.<ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator>provider(null),
                RagFailureBlackboxServiceTest.provider(graphClient));
        ReflectionTestUtils.setField(service, "enabled", enabled);
        return service;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }

    private static VirtualPointService fixedMatchService(VirtualPoint point, double similarity) {
        return new VirtualPointService() {
            @Override
            public synchronized java.util.Optional<Match> nearest(float[] vector, double minSimilarity) {
                return java.util.Optional.of(new Match("blackbox:prior", point, similarity));
            }
        };
    }

    private static void assertBounded(Object value) {
        assertTrue(value instanceof Number, "expected numeric value but got " + value);
        double v = ((Number) value).doubleValue();
        assertTrue(v >= 0.0d && v <= 1.0d, "expected [0,1] score but got " + v);
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

    private static final class FakeGraphClient extends Neo4jKnowledgeGraphClient {
        private final List<RecoveryRecommendation> recommendations;
        private int recommendCount;
        private int upsertCount;
        private String lastUpsertRestoreAction = "";

        private FakeGraphClient(List<RecoveryRecommendation> recommendations) {
            super(new Neo4jKnowledgeGraphProperties());
            this.recommendations = recommendations == null ? List.of() : recommendations;
        }

        @Override
        public String disabledReason() {
            return null;
        }

        @Override
        public void upsertFailurePattern(String patternId,
                                         String dominantFailure,
                                         String hotspot,
                                         String restoreAction,
                                         double confidence,
                                         double riskScore,
                                         int matrixTile,
                                         double jbPressure,
                                         double cbPressure) {
            upsertCount++;
            lastUpsertRestoreAction = restoreAction;
        }

        @Override
        public List<RecoveryRecommendation> recommendRecovery(String patternId,
                                                              String dominantFailure,
                                                              String hotspot,
                                                              int matrixTile,
                                                              double jbPressure,
                                                              double cbPressure,
                                                              int topK) {
            recommendCount++;
            return recommendations;
        }
    }
}
