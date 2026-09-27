package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugCopilotServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void addsRewriteRiskCausesWithoutRawQuery() {
        String rawQuery = "raw sensitive query should not be exposed";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("ml.risk.rewrite.band", "HIGH");
        TraceStore.put("ml.risk.rewrite.score", "0.82");
        TraceStore.put("ml.risk.rewrite.currentScore", "0.76");
        TraceStore.put("ml.risk.rewrite.primaryFactor", "providerFailure");
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("selfask.3way.api.disabledReason", "missing-key-or-unauthorized");
        TraceStore.put("selfask.requery.summary", Map.of(
                "laneCoverage", 3,
                "seedCount", 3,
                "requeryConfirmed", true,
                "query", rawQuery));
        TraceStore.append("selfask.requery.attempts", Map.of(
                "lane", "RC",
                "seedHash12", "abc123def456",
                "failureClass", "zero-results",
                "returnedCount", 0,
                "afterFilterCount", 0));

        new DebugCopilotService().maybeEnrichTrace();

        Object causes = TraceStore.get("dbg.copilot.causes");
        String dump = String.valueOf(causes);
        assertTrue(causes instanceof List<?>);
        assertTrue(dump.contains("rewrite_risk_high"));
        assertTrue(dump.contains("rewrite_starvation"));
        assertTrue(dump.contains("rewrite_provider_failure"));
        assertTrue(dump.contains("web.tavily.providerDisabled=true"));
        assertTrue(dump.contains("web.tavily.afterFilterCount=0"));
        assertFalse(dump.contains(rawQuery));
    }

    @Test
    void addsRewriteContradictionAndLatencyPressureCausesWithoutRawQuery() {
        String rawQuery = "private contradiction query should stay hidden";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("ml.risk.rewrite.band", "MEDIUM");
        TraceStore.put("ml.risk.rewrite.score", "0.66");
        TraceStore.put("ml.risk.rewrite.currentScore", "0.62");
        TraceStore.put("ml.risk.rewrite.primaryFactor", "contradiction");
        TraceStore.put("ml.risk.rewrite.components", Map.of(
                "contradiction", 0.135d,
                "latencyPressure", 0.090d));
        TraceStore.put("overdrive.contradiction.mean", 0.90d);
        TraceStore.put("web.await.events.count", 4);
        TraceStore.put("web.await.events.timeout.count", 3);
        TraceStore.put("selfask.3way.requery.confirmed", true);
        TraceStore.put("raw.query.fixture", rawQuery);

        new DebugCopilotService().maybeEnrichTrace();

        Object causes = TraceStore.get("dbg.copilot.causes");
        String dump = String.valueOf(causes)
                + String.valueOf(TraceStore.get("dbg.copilot.actions"))
                + String.valueOf(TraceStore.get("dbg.copilot.summary"));
        assertTrue(causes instanceof List<?>);
        assertTrue(dump.contains("rewrite_contradiction_pressure"), dump);
        assertTrue(dump.contains("rewrite_latency_pressure"), dump);
        assertTrue(dump.contains("ml.risk.rewrite.components.contradiction=0.135"), dump);
        assertTrue(dump.contains("ml.risk.rewrite.components.latencyPressure=0.09"), dump);
        assertTrue(dump.contains("web.await.events.timeout.count=3"), dump);
        assertFalse(dump.contains(rawQuery), dump);
    }

    @Test
    void nonFiniteRiskScoresDoNotCreateDebugCauses() {
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("blackbox.risk.riskScore", Double.POSITIVE_INFINITY);
        TraceStore.put("blackbox.risk.dominantFailure", "rate_limit");
        TraceStore.put("blackbox.risk.restoreAction", "cooldown_reorder");
        TraceStore.put("ml.risk.rewrite.score", "Infinity");
        TraceStore.put("ml.risk.rewrite.primaryFactor", "providerFailure");
        TraceStore.put("aux.blocked.count", Double.POSITIVE_INFINITY);

        new DebugCopilotService().maybeEnrichTrace();

        String dump = String.valueOf(TraceStore.get("dbg.copilot.causes"));
        assertFalse(dump.contains("aux_blocked"), dump);
        assertFalse(dump.contains("rag_blackbox_rate_limit"), dump);
        assertFalse(dump.contains("rewrite_risk_high"), dump);
        assertEquals(Boolean.TRUE, TraceStore.get("debug.copilot.suppressed.asInt"));
        assertEquals(Boolean.TRUE, TraceStore.get("debug.copilot.suppressed.asDouble"));
        assertEquals("invalid_number", TraceStore.get("debug.copilot.suppressed.asInt.errorType"));
        assertEquals("invalid_number", TraceStore.get("debug.copilot.suppressed.asDouble.errorType"));
    }

    @Test
    void numericDebugCopilotParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/trace/DebugCopilotService.java"))
                .replace("\r\n", "\n");

        assertParserCatchNarrowed(source, "return Integer.parseInt(String.valueOf(v).trim());");
        assertParserCatchNarrowed(source, "Double.parseDouble(String.valueOf(v).trim());");
    }

    @Test
    void addsNightmareSilentFailureCauseWithoutRawQuery() {
        String rawQuery = "raw final rescue query should not be exposed";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("nightmare.finalRescue.used", true);
        TraceStore.put("nightmare.finalRescue.reason", "definitive_failure_with_evidence");
        TraceStore.put("nightmare.finalRescue.evidenceCount", 4);
        TraceStore.put("nightmare.finalRescue.queryHash", "hash:abc123def456");
        TraceStore.append("nightmare.silent.events", Map.of(
                "key", "chat:draft:local",
                "ctxLen", 72,
                "reason", "definitive_failure_with_evidence"));
        TraceStore.put("raw.query.fixture", rawQuery);

        new DebugCopilotService().maybeEnrichTrace();

        Object causes = TraceStore.get("dbg.copilot.causes");
        String dump = String.valueOf(causes);
        assertTrue(causes instanceof List<?>);
        assertTrue(dump.contains("nightmare_silent_failure"));
        assertTrue(dump.contains("nightmare.finalRescue.queryHash=hash:abc123def456"));
        assertFalse(dump.contains(rawQuery));
    }

    @Test
    void addsLlmHealthPressureCauseWithoutRawModelOrPrompt() {
        String rawPrompt = "private prompt that should not leak";
        String rawModel = "qwen3:8b-private-owner-token";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("llm.gateway.route.healthFailurePressure", 0.82d);
        TraceStore.put("llm.gateway.route.healthSampleCount", 5L);
        TraceStore.put("llm.gateway.route.healthFailureCount", 4L);
        TraceStore.put("llm.gateway.route.healthRoutingHint", "llm_route_degrade");
        TraceStore.put("llm.gateway.route.model", rawModel);
        TraceStore.put("raw.prompt.fixture", rawPrompt);

        new DebugCopilotService().maybeEnrichTrace();

        Object causes = TraceStore.get("dbg.copilot.causes");
        String dump = String.valueOf(causes)
                + String.valueOf(TraceStore.get("dbg.copilot.actions"))
                + String.valueOf(TraceStore.get("dbg.copilot.summary"));
        assertTrue(causes instanceof List<?>);
        assertTrue(dump.contains("llm_health_pressure"));
        assertTrue(dump.contains("llm.gateway.route.healthFailurePressure=0.82"));
        assertTrue(dump.contains("llm.gateway.route.healthRoutingHint=llm_route_degrade"));
        assertTrue(dump.contains("llm.client"));
        assertFalse(dump.contains(rawModel));
        assertFalse(dump.contains(rawPrompt));
        assertFalse(dump.contains("private-owner-token"));
    }

    @Test
    void addsLocalLlmOperatorActionCauseFromSmokeHistoryWithoutRawPayloads() {
        String rawPrompt = "private local smoke prompt";
        String rawModel = "qwen3:8b-private-owner-token";
        TraceStore.put("dbg.search.enabled", true);

        Map<String, Object> snapshot = Map.of(
                "latest", Map.of(
                        "recommendedRoute", "native_ollama",
                        "operatorAction", Map.of(
                                "triggered", true,
                                "triggerReason", "threshold_exceeded",
                                "failureClass", "model_blank",
                                "nextAction", "prefer_native_ollama_route",
                                "actionScore", 100,
                                "scoreDelta", 85,
                                "negativeSignalCount", 1
                        ),
                        "rawPrompt", rawPrompt,
                        "rawModel", rawModel
                ));

        new DebugCopilotService(() -> snapshot).maybeEnrichTrace();

        Object causes = TraceStore.get("dbg.copilot.causes");
        String dump = String.valueOf(causes)
                + String.valueOf(TraceStore.get("dbg.copilot.actions"))
                + String.valueOf(TraceStore.get("dbg.copilot.summary"));
        assertTrue(causes instanceof List<?>);
        assertTrue(dump.contains("llm_health_pressure"));
        assertEquals(true, TraceStore.get("llm.localSmoke.operatorAction.triggered"));
        assertEquals("threshold_exceeded", TraceStore.get("llm.localSmoke.operatorAction.triggerReason"));
        assertEquals("model_blank", TraceStore.get("llm.localSmoke.operatorAction.failureClass"));
        assertEquals("prefer_native_ollama_route", TraceStore.get("llm.localSmoke.operatorAction.nextAction"));
        assertEquals(100, TraceStore.get("llm.localSmoke.operatorAction.actionScore"));
        assertEquals(85, TraceStore.get("llm.localSmoke.operatorAction.scoreDelta"));
        assertEquals(1, TraceStore.get("llm.localSmoke.operatorAction.negativeSignalCount"));
        assertEquals(1.0d, (Double) TraceStore.get("llm.gateway.route.healthFailurePressure"), 0.001d);
        assertEquals(1L, TraceStore.get("llm.gateway.route.healthFailureCount"));
        assertEquals("llm_route_degrade", TraceStore.get("llm.gateway.route.healthRoutingHint"));
        assertTrue(dump.contains("localLlm.operatorAction.triggerReason=threshold_exceeded"));
        assertTrue(dump.contains("localLlm.operatorAction.failureClass=model_blank"));
        assertTrue(dump.contains("localLlm.operatorAction.nextAction=prefer_native_ollama_route"));
        assertTrue(dump.contains("localLlm.operatorAction.actionScore=100"));
        assertTrue(dump.contains("localLlm.operatorAction.scoreDelta=85"));
        assertFalse(dump.contains(rawPrompt));
        assertFalse(dump.contains(rawModel));
        assertFalse(dump.contains("private-owner-token"));
    }

    @Test
    void publishesLocalLlmOperatorActionEvenWhenDebugCopilotGateIsOff() {
        Map<String, Object> snapshot = Map.of(
                "latest", Map.of(
                        "recommendedRoute", "native_ollama",
                        "operatorAction", Map.of(
                                "triggered", true,
                                "triggerReason", "threshold_exceeded",
                                "failureClass", "model_blank",
                                "nextAction", "prefer_native_ollama_route",
                                "actionScore", 100,
                                "scoreDelta", 85,
                                "negativeSignalCount", 1
                        )
                ));

        new DebugCopilotService(() -> snapshot).maybeEnrichTrace();

        assertEquals(true, TraceStore.get("llm.localSmoke.operatorAction.triggered"));
        assertEquals("threshold_exceeded", TraceStore.get("llm.localSmoke.operatorAction.triggerReason"));
        assertEquals("model_blank", TraceStore.get("llm.localSmoke.operatorAction.failureClass"));
        assertEquals("prefer_native_ollama_route", TraceStore.get("llm.localSmoke.operatorAction.nextAction"));
        assertEquals(100, TraceStore.get("llm.localSmoke.operatorAction.actionScore"));
        assertEquals(85, TraceStore.get("llm.localSmoke.operatorAction.scoreDelta"));
        assertEquals(1, TraceStore.get("llm.localSmoke.operatorAction.negativeSignalCount"));
        assertEquals(1.0d, (Double) TraceStore.get("llm.gateway.route.healthFailurePressure"), 0.001d);
        assertEquals(1L, TraceStore.get("llm.gateway.route.healthFailureCount"));
        assertEquals("llm_route_degrade", TraceStore.get("llm.gateway.route.healthRoutingHint"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("dbg.copilot.causes"));
    }

    @Test
    void staleLocalLlmSmokeHistoryDoesNotPublishCurrentOperatorAction() {
        Map<String, Object> snapshot = Map.of(
                "reportStale", true,
                "evidenceMode", "supporting_stale",
                "latest", Map.of(
                        "operatorAction", Map.of(
                                "triggered", true,
                                "triggerReason", "threshold_exceeded",
                                "failureClass", "model_blank",
                                "nextAction", "inspect_ollama_runtime_capacity",
                                "actionScore", 100,
                                "scoreDelta", 85,
                                "negativeSignalCount", 2
                        )
                ));

        new DebugCopilotService(() -> snapshot).maybeEnrichTrace();

        assertNull(TraceStore.get("llm.localSmoke.operatorAction.failureClass"));
        assertNull(TraceStore.get("llm.localSmoke.operatorAction.triggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.localSmoke.operatorAction.stale"));
        assertEquals("supporting_stale", TraceStore.get("llm.localSmoke.operatorAction.evidenceMode"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("llm_route_degrade"));
    }

    private static void assertParserCatchNarrowed(String source, String parserCall) {
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable: " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 420));

        assertFalse(window.contains("catch (Exception"),
                "DebugCopilot numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "DebugCopilot numeric parser fallback should catch only NumberFormatException");
    }

    @Test
    void correlationHintsAreHashOnly() {
        String rawTraceId = "trace-private-owner-token-123";
        String rawSessionId = "session-private-owner-token-456";
        TraceStore.put("dbg.search.enabled", true);
        MDC.put("traceId", rawTraceId);
        MDC.put("sessionId", rawSessionId);

        new DebugCopilotService().maybeEnrichTrace();

        assertEquals(SafeRedactor.hashValue(rawTraceId), TraceStore.get("dbg.copilot.traceId"));
        assertEquals(SafeRedactor.hashValue(rawSessionId), TraceStore.get("dbg.copilot.sid"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawTraceId), trace);
        assertFalse(trace.contains(rawSessionId), trace);
    }

    @Test
    void causeActionsAndEvidenceDoNotExposeRawIdsOrReasons() {
        String rawTraceId = "trace-private-owner-token-789";
        String rawSessionId = "session-private-owner-token-987";
        String rawReason = "private qtx reason api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("aux.queryTransformer.degraded", true);
        TraceStore.put("aux.queryTransformer.degraded.reason", rawReason);
        MDC.put("traceId", rawTraceId);
        MDC.put("sessionId", rawSessionId);

        new DebugCopilotService().maybeEnrichTrace();

        String output = String.valueOf(List.of(
                TraceStore.get("dbg.copilot.causes"),
                TraceStore.get("dbg.copilot.actions"),
                TraceStore.get("dbg.copilot.summary"),
                TraceStore.get("dbg.copilot.traceId"),
                TraceStore.get("dbg.copilot.sid")));
        assertFalse(output.contains(rawTraceId), output);
        assertFalse(output.contains(rawSessionId), output);
        assertFalse(output.contains(rawReason), output);
        assertFalse(output.contains("private qtx reason"), output);
        assertFalse(output.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""), output);
        assertTrue(output.contains(SafeRedactor.hashValue(rawTraceId)), output);
        assertTrue(output.contains(SafeRedactor.hashValue(rawSessionId)), output);
        assertTrue(output.contains("hash:"), output);
    }

    @Test
    void auxBlockedLastEvidenceDoesNotExposeRawTracePayload() {
        String rawAuxLast = "private aux blocked payload api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + " ownerToken=raw";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("aux.blocked.last", rawAuxLast);

        new DebugCopilotService().maybeEnrichTrace();

        String output = String.valueOf(List.of(
                TraceStore.get("dbg.copilot.causes"),
                TraceStore.get("dbg.copilot.actions"),
                TraceStore.get("dbg.copilot.summary")));
        assertFalse(output.contains(rawAuxLast), output);
        assertFalse(output.contains("private aux blocked payload"), output);
        assertFalse(output.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""), output);
        assertFalse(output.contains("ownerToken=raw"), output);
        assertTrue(output.contains("hash:"), output);
    }

    @Test
    void orchestrationReasonEvidenceDoesNotExposeRawTracePayload() {
        String rawReason = "private orch reason api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + " ownerToken=raw";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("orch.bypass", true);
        TraceStore.put("orch.reason", rawReason);

        new DebugCopilotService().maybeEnrichTrace();

        String output = String.valueOf(List.of(
                TraceStore.get("dbg.copilot.causes"),
                TraceStore.get("dbg.copilot.actions"),
                TraceStore.get("dbg.copilot.summary")));
        assertFalse(output.contains(rawReason), output);
        assertFalse(output.contains("private orch reason"), output);
        assertFalse(output.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""), output);
        assertFalse(output.contains("ownerToken=raw"), output);
        assertTrue(output.contains("hash:"), output);
    }
}
