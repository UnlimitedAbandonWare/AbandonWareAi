package com.example.lms.debug;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugEventTracePromotionServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void seedsExternalEvidenceTagsAsReadOnlyLanes() {
        DebugEventTracePromotionService.seedRequestedExternalEvidenceLanes(
                "@supabase @superpowers @computer-use @browser @\uCEF4\uD4E8\uD130 @\uBE0C\uB77C\uC6B0\uC800");

        Map<String, Object> trace = TraceStore.getAll();

        assertEquals("external_evidence", trace.get("externalEvidence.mode"));
        assertEquals(Boolean.FALSE, trace.get("externalEvidence.executionThread"));
        assertEquals(Boolean.TRUE, trace.get("externalEvidence.readOnly"));
        assertEquals(Boolean.FALSE, trace.get("externalEvidence.mutationAllowed"));
        assertEquals("chat_request_tags", trace.get("externalEvidence.source"));
        assertEquals(List.of("supabase", "superpowers", "computer-use", "browser"),
                trace.get("externalEvidence.requestedLanes"));
        assertEquals("external_evidence_lane", trace.get("supabase.evidenceNeeded"));
        assertEquals("external_evidence_lane", trace.get("superpowers.evidenceNeeded"));
        assertEquals("external_evidence_lane", trace.get("computer-use.evidenceNeeded"));
        assertEquals("external_evidence_lane", trace.get("browser.evidenceNeeded"));
    }

    @Test
    void promotesExternalEvidenceTraceAsDebugEventWithoutRawSecrets() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "externalEvidence.mode", "external_evidence",
                "externalEvidence.executionThread", false,
                "externalEvidence.requestedLanes", List.of("supabase", "superpowers", "computer-use", "browser"),
                "supabase.evidenceNeeded", "project_ref_missing",
                "supabase.service_role", "private_value_should_not_surface",
                "browser.evidenceNeeded", "browser_session_evidence_needed",
                "computer-use.evidenceNeeded", "computer_use_smoke_missing",
                "superpowers.evidenceNeeded", "external_evidence_lane");

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.EXTERNAL_EVIDENCE)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChatApiController.stream.final", event.where());
        assertEquals("external_evidence", event.data().get("kind"));
        assertEquals("external_evidence", event.data().get("lanePolicy"));
        assertEquals("external_evidence_lane", event.data().get("failureClass"));
        assertEquals(Boolean.FALSE, event.data().get("executionThread"));
        assertEquals(Boolean.FALSE, event.data().get("mutationAllowed"));
        assertEquals(4, event.data().get("laneCount"));
        assertTrue(String.valueOf(event.data()).contains("project_ref_missing"));
        assertFalse(event.toString().contains("private_value_should_not_surface"));
        assertFalse(event.toString().contains("service_role"));
    }

    @Test
    void promotesSuppressedTraceStageAsFaultMaskDebugEvent() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "chat.stream.signal.suppressed.stage", "signal.asLong",
                "chat.stream.signal.suppressed.errorType", "invalid_number",
                "chat.stream.signal.suppressed.signal.asLong", true,
                "rawPrompt", "private-token should not surface");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.FAULT_MASK)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChatApiController.stream.preLlm", event.where());
        assertEquals("trace_suppressed", event.data().get("stage"));
        assertTrue(String.valueOf(event.data().get("traceSignal")).contains("hash12"));
        assertEquals("signal.asLong", event.data().get("suppressedStage"));
        assertEquals("invalid_number", event.data().get("exceptionType"));
        assertEquals("invalid_number", event.data().get("failureClass"));
        assertEquals(Boolean.TRUE, event.data().get("promotedFromTraceStore"));
        assertFalse(event.toString().contains("private-token"));
    }

    @Test
    void promotesSuppressedFlagTraceAsFaultMaskDebugEvent() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "chat.api.suppressed.parse.asLong", true,
                "chat.api.suppressed.parse.asLong.errorType", "invalid_number",
                "rawPrompt", "private-token should not surface");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.sync.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.FAULT_MASK)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChatApiController.sync.preLlm", event.where());
        assertEquals("parse.asLong", event.data().get("suppressedStage"));
        assertEquals("invalid_number", event.data().get("exceptionType"));
        assertEquals("invalid_number", event.data().get("failureClass"));
        assertEquals(Boolean.TRUE, event.data().get("promotedFromTraceStore"));
        assertFalse(event.toString().contains("private-token"));
    }

    @Test
    void promotesReactorDroppedErrorCounterAsFaultMaskDebugEvent() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "reactor.onErrorDropped.count", 2,
                "reactor.onErrorDropped.cancel.count", 1,
                "reactor.onErrorDropped.bodyReleased.count", 1,
                "reactor.onErrorDropped.last", "WebClientResponseException",
                "rawPrompt", "private-token should not surface");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.FAULT_MASK)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChatApiController.stream.preLlm", event.where());
        assertEquals("reactor_on_error_dropped", event.data().get("stage"));
        assertTrue(String.valueOf(event.data().get("traceSignal")).contains("hash12"));
        assertEquals("reactor_on_error_dropped", event.data().get("failureClass"));
        assertEquals("WebClientResponseException", event.data().get("exceptionType"));
        assertEquals(2, event.data().get("count"));
        assertEquals(1, event.data().get("cancelCount"));
        assertEquals(1, event.data().get("bodyReleasedCount"));
        assertEquals(Boolean.TRUE, event.data().get("promotedFromTraceStore"));
        assertFalse(event.toString().contains("private-token"));
        assertFalse(event.toString().contains("rawPrompt"));
    }

    @Test
    void promotesContextCounterSignalsAsNamedFaultMaskDebugEvents() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "ctx.debugPort.suppressed.count", 2,
                "ctx.debugPort.suppressed.errorType", "debug_sink_failed",
                "ctx.propagation.missing.count", 3,
                "ctx.propagation.missing.errorType", "trace_context_missing",
                "rawPrompt", "private-token should not surface");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        List<DebugEvent> faultMaskEvents = store.list(10).stream()
                .filter(e -> e.probe() == DebugProbeType.FAULT_MASK)
                .toList();
        assertEquals(2, faultMaskEvents.size());
        DebugEvent debugPort = faultMaskEvents.stream()
                .filter(e -> "debug_port_suppressed".equals(e.data().get("stage")))
                .findFirst()
                .orElseThrow();
        DebugEvent propagation = faultMaskEvents.stream()
                .filter(e -> "context_propagation_missing".equals(e.data().get("stage")))
                .findFirst()
                .orElseThrow();

        assertEquals("debug_port_suppressed", debugPort.data().get("failureClass"));
        assertEquals("debug_sink_failed", debugPort.data().get("exceptionType"));
        assertEquals(2, debugPort.data().get("count"));
        assertEquals("context_propagation_missing", propagation.data().get("failureClass"));
        assertEquals("trace_context_missing", propagation.data().get("exceptionType"));
        assertEquals(3, propagation.data().get("count"));
        assertFalse(faultMaskEvents.toString().contains("suppressedStage=count"));
        assertFalse(faultMaskEvents.toString().contains("private-token"));
        assertFalse(faultMaskEvents.toString().contains("rawPrompt"));
    }

    @Test
    void malformedTruthyCounterLeavesRedactedParseBreadcrumb() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "ctx.debugPort.suppressed.count", "yes",
                "ctx.debugPort.suppressed.errorType", "debug_sink_failed",
                "rawPrompt", "ownerToken=private-token");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.FAULT_MASK)
                .findFirst()
                .orElseThrow();
        assertEquals("debug_port_suppressed", event.data().get("stage"));
        assertEquals(1, event.data().get("count"));
        assertEquals("intValue", TraceStore.get("debugEvent.promote.suppressed.stage"));
        assertEquals("NumberFormatException", TraceStore.get("debugEvent.promote.suppressed.errorType"));
        String dump = event + "\n" + TraceStore.getAll();
        assertFalse(dump.contains("ownerToken"));
        assertFalse(dump.contains("private-token"));
    }

    @Test
    void lastResortSuppressionBreadcrumbLogsSafeStageWhenTraceStoreWriteFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(DebugEventTracePromotionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            TraceStore.installContext(new ThrowingTraceMap());

            DebugEventTracePromotionService.seedRequestedExternalEvidenceLanes(
                    "@supabase ownerToken=private-token");

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains("[AWX][debug-event] traceStoreWriteSuppressed"));
            assertTrue(rendered.contains("stage=seed.externalEvidence"));
            assertTrue(rendered.contains("errorType=UnsupportedOperationException"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains("private-token"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            TraceStore.clear();
        }
    }

    @Test
    void promotesQueryRewriteSuperTokensAsDebugEventWithoutRawTitle() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                Map.entry("queryTransformer.subQueries.superTokens.branchCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.subModelCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.subModelAssignmentCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.subModelIds",
                        List.of("definition-model", "alias-model", "relation-model", "ownerToken=private-model")),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.axes",
                        List.of("definition", "alias", "relation", "ownerToken=private-axis")),
                Map.entry("queryTransformer.subQueries.refined.count", 3),
                Map.entry("queryTransformer.subQueries.refined.paddedCount", 2),
                Map.entry("queryTransformer.subQueries.superTokens.titlePresent", true),
                Map.entry("queryTransformer.subQueries.superTokens.titleHash12", "abc123safehash"),
                Map.entry("queryTransformer.subQueries.superTokens.titleTokenCount", 5),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleHashCount", 2),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleHashes",
                        List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "ownerToken=private-title-hash")),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleMetadataCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleLengths",
                        List.of(45, 44, 47, "ownerToken=private-title-length")),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleTermCounts",
                        List.of(6, 6, 6, "ownerToken=private-title-terms")),
                Map.entry("queryTransformer.subQueries.superTokens.branchQueryMetadataCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.branchQueryHashes",
                        List.of("cccccccccccc", "dddddddddddd", "eeeeeeeeeeee", "ownerToken=private-query")),
                Map.entry("queryTransformer.subQueries.superTokens.branchQueryLengths",
                        List.of(85, 82, 90, "ownerToken=private-query-length")),
                Map.entry("queryTransformer.subQueries.superTokens.branchQueryTermCounts",
                        List.of(11, 10, 12, "ownerToken=private-query-terms")),
                Map.entry("queryTransformer.subQueries.superTokens.branchQueryCoverageComplete", true),
                Map.entry("queryTransformer.subQueries.superTokens.branchTitleCoverageComplete", true),
                Map.entry("queryTransformer.subQueries.superTokens.coverageComplete", true),
                Map.entry("queryTransformer.subQueries.coverage.axisCount", 3),
                Map.entry("queryTransformer.subQueries.coverage.coveredAxisCount", 3),
                Map.entry("queryTransformer.subQueries.coverage.missingAxisCount", 0),
                Map.entry("queryTransformer.subQueries.coverage.complete", true),
                Map.entry("queryTransformer.subQueries.coverage.coveredAxes",
                        List.of("definition", "alias", "relation", "ownerToken=private-axis")),
                Map.entry("queryTransformer.subQueries.superTokens.titleLength", 42),
                Map.entry("queryTransformer.subQueries.superTokens.rawTitle", "ownerToken=private-title"));

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.QUERY_TRANSFORMER)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.INFO, event.level());
        assertEquals("ChatApiController.stream.preLlm", event.where());
        assertEquals("query_transformer", event.data().get("kind"));
        assertEquals("pre_llm", event.data().get("phaseStage"));
        assertEquals("query_rewrite", event.data().get("stage"));
        assertEquals("query_rewrite.super_tokens", event.data().get("failureClass"));
        assertEquals(3, event.data().get("branchCount"));
        assertEquals(3, event.data().get("superCount"));
        assertEquals(3, event.data().get("subModelCount"));
        assertEquals(3, event.data().get("subModelAssignmentCount"));
        assertEquals(3, event.data().get("refinedCount"));
        assertEquals(2, event.data().get("paddedCount"));
        assertEquals(3, event.data().get("branchAxisCount"));
        assertEquals(List.of(
                Map.of("present", true, "len", 10, "hash12", SafeRedactor.hash12("definition")),
                Map.of("present", true, "len", 5, "hash12", SafeRedactor.hash12("alias")),
                Map.of("present", true, "len", 8, "hash12", SafeRedactor.hash12("relation"))),
                event.data().get("branchAxes"));
        assertEquals(List.of(
                Map.of("present", true, "len", 16, "hash12", SafeRedactor.hash12("definition-model")),
                Map.of("present", true, "len", 11, "hash12", SafeRedactor.hash12("alias-model")),
                Map.of("present", true, "len", 14, "hash12", SafeRedactor.hash12("relation-model"))),
                event.data().get("subModelIds"));
        assertEquals(3, event.data().get("branchTitleCount"));
        assertEquals(2, event.data().get("branchTitleHashCount"));
        assertEquals(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"), event.data().get("branchTitleHashes"));
        assertEquals(2, event.data().get("branchTitleMetadataCount"));
        assertEquals(List.of(
                Map.of("axis", "definition", "hash12", "aaaaaaaaaaaa", "len", 45, "termCount", 6),
                Map.of("axis", "alias", "hash12", "bbbbbbbbbbbb", "len", 44, "termCount", 6)),
                event.data().get("branchTitleMetadata"));
        assertEquals(3, event.data().get("branchQueryMetadataCount"));
        assertEquals(List.of(
                Map.of("axis", "definition", "model", "definition-model", "hash12", "cccccccccccc", "len", 85, "termCount", 11),
                Map.of("axis", "alias", "model", "alias-model", "hash12", "dddddddddddd", "len", 82, "termCount", 10),
                Map.of("axis", "relation", "model", "relation-model", "hash12", "eeeeeeeeeeee", "len", 90, "termCount", 12)),
                event.data().get("branchQueryMetadata"));
        assertEquals(Boolean.TRUE, event.data().get("branchQueryCoverageComplete"));
        assertEquals(Boolean.TRUE, event.data().get("titlePresent"));
        assertEquals("abc123safehash", event.data().get("titleHash12"));
        assertEquals(5, event.data().get("titleTermCount"));
        assertEquals(Boolean.TRUE, event.data().get("branchTitleCoverageComplete"));
        assertEquals(Boolean.TRUE, event.data().get("coverageComplete"));
        assertEquals(3, event.data().get("outputAxisCount"));
        assertEquals(3, event.data().get("coveredAxisCount"));
        assertEquals(0, event.data().get("missingAxisCount"));
        assertEquals(Boolean.TRUE, event.data().get("outputCoverageComplete"));
        assertEquals(List.of(
                Map.of("present", true, "len", 10, "hash12", SafeRedactor.hash12("definition")),
                Map.of("present", true, "len", 5, "hash12", SafeRedactor.hash12("alias")),
                Map.of("present", true, "len", 8, "hash12", SafeRedactor.hash12("relation"))),
                event.data().get("coveredAxes"));
        assertFalse(event.toString().contains("private-title"));
        assertFalse(event.toString().contains("ownerToken"));
    }

    @Test
    void promotesQueryRewriteAxisCountWithoutTrustingRawAxisLabels() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                Map.entry("queryTransformer.subQueries.superTokens.branchCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.axisCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.axes",
                        List.of("ownerToken=private-axis")));

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.QUERY_TRANSFORMER)
                .findFirst()
                .orElseThrow();
        assertEquals(3, event.data().get("branchAxisCount"));
        assertFalse(event.data().containsKey("branchAxes"));
        assertFalse(event.toString().contains("ownerToken"));
    }

    @Test
    void promotesIncompleteQueryRewriteCoverageAsWarnDebugEvent() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                Map.entry("queryTransformer.subQueries.superTokens.branchCount", 2),
                Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 2),
                Map.entry("queryTransformer.subQueries.superTokens.subModelCount", 2),
                Map.entry("queryTransformer.subQueries.superTokens.subModelAssignmentCount", 2),
                Map.entry("queryTransformer.subQueries.superTokens.subModelIds",
                        List.of("definition-model", "alias-model")),
                Map.entry("queryTransformer.subQueries.superTokens.axes",
                        List.of("definition", "alias")),
                Map.entry("queryTransformer.subQueries.coverage.axisCount", 3),
                Map.entry("queryTransformer.subQueries.coverage.coveredAxisCount", 2),
                Map.entry("queryTransformer.subQueries.coverage.missingAxisCount", 1),
                Map.entry("queryTransformer.subQueries.coverage.complete", false),
                Map.entry("queryTransformer.subQueries.coverage.coveredAxes",
                        List.of("definition", "alias")),
                Map.entry("queryTransformer.subQueries.superTokens.rawTitle", "ownerToken=private-title"));

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.QUERY_TRANSFORMER)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("query_rewrite.super_tokens.incomplete_coverage", event.data().get("failureClass"));
        assertEquals(3, event.data().get("outputAxisCount"));
        assertEquals(2, event.data().get("coveredAxisCount"));
        assertEquals(1, event.data().get("missingAxisCount"));
        assertEquals(Boolean.FALSE, event.data().get("outputCoverageComplete"));
        assertFalse(event.toString().contains("private-title"));
        assertFalse(event.toString().contains("ownerToken"));
    }

    @Test
    void promotesQueryRewriteSubModelAssignmentCountWithoutTrustingRawModelLabels() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                Map.entry("queryTransformer.subQueries.superTokens.branchCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.subModelAssignmentCount", 3),
                Map.entry("queryTransformer.subQueries.superTokens.subModelIds",
                        List.of("ownerToken=private-model")),
                Map.entry("queryTransformer.subQueries.superTokens.coverageComplete", true));

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.QUERY_TRANSFORMER)
                .findFirst()
                .orElseThrow();
        assertEquals(3, event.data().get("subModelAssignmentCount"));
        assertFalse(event.data().containsKey("subModelIds"));
        assertEquals(Boolean.TRUE, event.data().get("coverageComplete"));
        assertFalse(event.toString().contains("ownerToken"));
    }

    @Test
    void promotesRefinedQueryRewriteAsDebugEventEvenWithoutSuperTokens() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "queryTransformer.subQueries.refined", true,
                "queryTransformer.subQueries.refined.count", 2,
                "queryTransformer.subQueries.refined.paddedCount", 1,
                "queryTransformer.subQueries.refined.reason", "llm-response",
                "queryTransformer.subQueries.rawTitle", "ownerToken=private-title");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.QUERY_TRANSFORMER)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.INFO, event.level());
        assertEquals("query_rewrite", event.data().get("stage"));
        assertEquals("query_rewrite", event.data().get("failureClass"));
        assertEquals(2, event.data().get("refinedCount"));
        assertEquals(1, event.data().get("paddedCount"));
        assertEquals(0, event.data().get("branchCount"));
        assertFalse(event.toString().contains("private-title"));
        assertFalse(event.toString().contains("ownerToken"));
    }

    @Test
    void promotesLocalLlmOperatorActionAsDebugEventForOpsConsole() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "llm.localSmoke.operatorAction.triggered", true,
                "llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded",
                "llm.localSmoke.operatorAction.failureClass", "model_blank",
                "llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route",
                "llm.localSmoke.operatorAction.actionScore", 100,
                "llm.localSmoke.operatorAction.scoreDelta", 85,
                "rawPrompt", "private prompt must not surface");

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.MODEL_GUARD)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChatApiController.stream.preLlm", event.where());
        assertEquals("local_llm_operator_action", event.data().get("stage"));
        assertEquals("model_blank", event.data().get("failureClass"));
        assertEquals("threshold_exceeded", event.data().get("triggerReason"));
        assertEquals("prefer_native_ollama_route", event.data().get("nextAction"));
        assertEquals(100, event.data().get("actionScore"));
        assertEquals(85, event.data().get("scoreDelta"));
        assertEquals(Boolean.TRUE, event.data().get("promotedFromTraceStore"));
        assertFalse(event.toString().contains("private prompt"));
        assertFalse(event.toString().contains("rawPrompt"));
    }

    @Test
    void promotesStageBoundaryBreadcrumbRowsAsDebugEvents() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.search", Map.of(
                        "stage", "search",
                        "failureClass", "after-filter-starvation",
                        "reasonCode", "after_filter_starvation",
                        "queryHash12", "abcdef123456",
                        "queryLength", 42,
                        "returnedCount", 4,
                        "afterFilterCount", 0,
                        "rawPrompt", "ownerToken=private-token"),
                "rawPrompt", "ownerToken=private-token");

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.WEB_SEARCH)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChatApiController.stream.final", event.where());
        assertEquals("stage_boundary", event.data().get("stage"));
        assertEquals("search", event.data().get("boundaryStage"));
        assertEquals("after-filter-starvation", event.data().get("failureClass"));
        assertEquals("after_filter_starvation", event.data().get("reasonCode"));
        assertEquals("abcdef123456", event.data().get("queryHash12"));
        assertEquals(42, event.data().get("queryLength"));
        assertEquals(Boolean.TRUE, event.data().get("promotedFromTraceStore"));
        assertFalse(event.toString().contains("ownerToken"));
        assertFalse(event.toString().contains("private-token"));
        assertFalse(event.toString().contains("rawPrompt"));
    }

    @Test
    void routesStageBoundaryBreadcrumbsToTheirOwningDebugProbe() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.request", Map.of(
                        "stage", "request", "failureClass", "context-missing", "reasonCode", "context_missing"),
                "mla.breadcrumb.step.orchestration", Map.of(
                        "stage", "orchestration", "failureClass", "fallback", "reasonCode", "fallback"),
                "mla.breadcrumb.step.search", Map.of(
                        "stage", "search", "failureClass", "zero-result", "reasonCode", "zero_result"),
                "mla.breadcrumb.step.prompt", Map.of(
                        "stage", "prompt",
                        "failureClass", "context-missing",
                        "reasonCode", "context_missing"),
                "mla.breadcrumb.step.llm", Map.of(
                        "stage", "llm",
                        "failureClass", "timeout",
                        "reasonCode", "timeout"),
                "mla.breadcrumb.step.sse", Map.of(
                        "stage", "sse", "failureClass", "fallback", "reasonCode", "empty_final_text"),
                "mla.breadcrumb.step.verification", Map.of(
                        "stage", "verification",
                        "status", "fail_soft",
                        "failureClass", "catch",
                        "reasonCode", "judge_call_failed",
                        "judgeLane", "fact_status_classifier",
                        "judgeFailSoftLaneCount", 1,
                        "judgeCallAttempted", true,
                        "verificationOutcomeKnown", false,
                        "redacted", true));

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        List<DebugEvent> events = store.list(10);
        assertEquals(DebugProbeType.CONTEXT_PROPAGATION, boundaryEvent(events, "request").probe());
        assertEquals(DebugProbeType.ORCHESTRATION, boundaryEvent(events, "orchestration").probe());
        assertEquals(DebugProbeType.WEB_SEARCH, boundaryEvent(events, "search").probe());
        assertEquals(DebugProbeType.PROMPT, boundaryEvent(events, "prompt").probe());
        assertEquals(DebugProbeType.MODEL_GUARD, boundaryEvent(events, "llm").probe());
        assertEquals(DebugProbeType.GENERIC, boundaryEvent(events, "sse").probe());
        assertEquals(DebugProbeType.GENERIC, boundaryEvent(events, "verification").probe());
    }

    @Test
    void rejectsStageBoundaryRowWhenKeyAndPayloadStageDisagree() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.search", Map.of(
                        "stage", "llm",
                        "failureClass", "timeout",
                        "reasonCode", "timeout"));

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        assertTrue(store.list(5).isEmpty(),
                "the allowlisted key suffix must remain authoritative for probe ownership");
    }

    @Test
    void ignoresMalformedAndSuccessfulStageBoundaryRowsInsteadOfWarning() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.prompt", Map.of(
                        "stage", "prompt",
                        "status", "PASS",
                        "reasonCode", "verified"),
                "mla.breadcrumb.step.llm", Map.of(
                        "stage", "llm",
                        "reasonCode", "timeout"));

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        assertTrue(store.list(5).isEmpty(),
                "rows without an allowlisted failureClass must not become WARN events");
    }

    @Test
    void ignoresUnknownStageBoundaryRow() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.untrusted", Map.of(
                        "stage", "untrusted",
                        "failureClass", "catch",
                        "reasonCode", "unknown_stage"));

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        assertTrue(store.list(5).isEmpty(),
                "only the stage-boundary allowlist may select an AI debug probe");
    }

    @Test
    void promotesSameBoundaryFailureOnlyOnceAcrossPhaseSnapshots() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.prompt", Map.of(
                        "stage", "prompt",
                        "failureClass", "context-missing",
                        "reasonCode", "context_missing"));

        service.promoteChatTrace("pre_llm", meta, "ChatApiController.stream.preLlm");
        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        long boundaryEvents = store.list(5).stream()
                .filter(e -> "prompt".equals(e.data().get("boundaryStage")))
                .count();
        assertEquals(1L, boundaryEvents,
                "phase snapshots must not double-count the same failure in AI debug metrics");
    }

    @Test
    void preservesChangedBoundaryEvidenceAcrossPhaseSnapshots() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> preMeta = Map.of(
                "mla.breadcrumb.step.search", Map.of(
                        "stage", "search",
                        "failureClass", "after-filter-starvation",
                        "reasonCode", "after_filter_starvation",
                        "returnedCount", 4,
                        "afterFilterCount", 0));
        Map<String, Object> finalMeta = Map.of(
                "mla.breadcrumb.step.search", Map.of(
                        "stage", "search",
                        "failureClass", "after-filter-starvation",
                        "reasonCode", "after_filter_starvation",
                        "returnedCount", 7,
                        "afterFilterCount", 0));

        service.promoteChatTrace("pre_llm", preMeta, "ChatApiController.stream.preLlm");
        service.promoteChatTrace("final", finalMeta, "ChatApiController.stream.final");
        service.promoteChatTrace("final", finalMeta, "ChatApiController.stream.final");

        List<DebugEvent> events = store.list(5).stream()
                .filter(e -> "search".equals(e.data().get("boundaryStage")))
                .toList();
        assertEquals(2, events.size(),
                "changed allowlisted evidence must survive while an unchanged repeat remains deduped");
        assertTrue(events.stream().anyMatch(e -> Integer.valueOf(4).equals(e.data().get("returnedCount"))));
        assertTrue(events.stream().anyMatch(e -> Integer.valueOf(7).equals(e.data().get("returnedCount"))));
    }

    @Test
    void promotesVerificationFailSoftScalarsWithoutInventingOutcome() {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.verification", Map.of(
                        "stage", "verification",
                        "status", "fail_soft",
                        "failureClass", "catch",
                        "reasonCode", "judge_call_failed",
                        "judgeLane", "both",
                        "judgeFailSoftLaneCount", 2,
                        "judgeCallAttempted", true,
                        "verificationOutcomeKnown", false,
                        "redacted", true,
                        "rawPrompt", "ownerToken=private-token"));

        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        DebugEvent event = boundaryEvent(store.list(5), "verification");
        assertEquals(DebugProbeType.GENERIC, event.probe());
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("verification.judge", event.data().get("layer"));
        assertEquals("fail_soft", event.data().get("status"));
        assertEquals("both", event.data().get("judgeLane"));
        assertEquals(2, event.data().get("judgeFailSoftLaneCount"));
        assertFalse(event.data().containsKey("judgeFailureCount"));
        assertEquals(Boolean.TRUE, event.data().get("judgeCallAttempted"));
        assertEquals(Boolean.FALSE, event.data().get("verificationOutcomeKnown"));
        assertEquals(Boolean.TRUE, event.data().get("redacted"));
        assertFalse(event.data().containsKey("verificationOutcome"));
        assertFalse(event.toString().contains("ownerToken"));
        assertFalse(event.toString().contains("private-token"));
        assertFalse(event.toString().contains("rawPrompt"));
    }

    @Test
    void boundaryOnlyPromotionDoesNotEmitStaleLocalLlmOperatorAction() throws Exception {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = verificationAndLocalLlmTrace();

        invokeBoundaryOnlyPromotion(service, "final", meta, "ChatApiController.stream.final");

        List<DebugEvent> events = store.list(10);
        assertEquals(1, events.size());
        assertEquals("verification", events.get(0).data().get("boundaryStage"));
        assertTrue(events.stream().noneMatch(event -> event.data().containsKey("localLlmNextAction")));
    }

    @Test
    void fullPromotionAfterBoundaryOnlyDedupesBoundaryAndKeepsOtherSignals() throws Exception {
        DebugEventStore store = enabledDebugEventStore();
        DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);
        Map<String, Object> meta = verificationAndLocalLlmTrace();

        invokeBoundaryOnlyPromotion(service, "final", meta, "ChatApiController.stream.final");
        service.promoteChatTrace("final", meta, "ChatApiController.stream.final");

        List<DebugEvent> events = store.list(10);
        assertEquals(1L, events.stream()
                .filter(event -> "verification".equals(event.data().get("boundaryStage")))
                .count());
        assertEquals(1L, events.stream()
                .filter(event -> "inspect_local_llm".equals(event.data().get("localLlmNextAction")))
                .count());
    }

    @Test
    void rejectsIncoherentVerificationFailSoftTuplesBeforeWarnPromotion() {
        List<Map<String, Object>> invalidRows = List.of(
                Map.<String, Object>of(
                        "stage", "verification", "status", "PASS",
                        "failureClass", "catch", "reasonCode", "verified",
                        "judgeLane", "fact_status_classifier", "judgeFailSoftLaneCount", 1,
                        "judgeCallAttempted", true, "verificationOutcomeKnown", true, "redacted", true),
                Map.<String, Object>of(
                        "stage", "verification", "status", "fail_soft",
                        "failureClass", "catch", "reasonCode", "judge_call_failed",
                        "judgeLane", "both", "judgeFailSoftLaneCount", 1,
                        "judgeCallAttempted", false, "verificationOutcomeKnown", false, "redacted", true),
                Map.<String, Object>of(
                        "stage", "verification", "status", "fail_soft",
                        "failureClass", "provider-disabled", "reasonCode", "judge_model_unavailable",
                        "judgeLane", "claim_verifier", "judgeFailSoftLaneCount", 1,
                        "judgeCallAttempted", true, "verificationOutcomeKnown", false, "redacted", true),
                Map.<String, Object>of(
                        "stage", "verification", "status", "fail_soft",
                        "failureClass", "fallback", "reasonCode", "judge_fail_soft",
                        "judgeLane", "fact_status_classifier", "judgeFailSoftLaneCount", 1,
                        "judgeCallAttempted", false, "verificationOutcomeKnown", false, "redacted", true));

        for (Map<String, Object> row : invalidRows) {
            DebugEventStore store = enabledDebugEventStore();
            DebugEventTracePromotionService service = new DebugEventTracePromotionService(store);

            service.promoteChatTrace("final", Map.of("mla.breadcrumb.step.verification", row),
                    "ChatApiController.stream.final");

            assertTrue(store.list(5).isEmpty(), "incoherent tuple must fail closed: " + row);
        }
    }

    private static DebugEvent boundaryEvent(List<DebugEvent> events, String stage) {
        return events.stream()
                .filter(e -> stage.equals(e.data().get("boundaryStage")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> verificationAndLocalLlmTrace() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("mla.breadcrumb.step.verification", Map.of(
                "stage", "verification",
                "status", "fail_soft",
                "failureClass", "catch",
                "reasonCode", "judge_call_failed",
                "judgeLane", "fact_status_classifier",
                "judgeFailSoftLaneCount", 1,
                "judgeCallAttempted", true,
                "verificationOutcomeKnown", false,
                "redacted", true));
        meta.put("llm.localSmoke.operatorAction.triggered", true);
        meta.put("llm.localSmoke.operatorAction.triggerReason", "latency_risk");
        meta.put("llm.localSmoke.operatorAction.failureClass", "local_llm_risk");
        meta.put("llm.localSmoke.operatorAction.nextAction", "inspect_local_llm");
        meta.put("llm.localSmoke.operatorAction.actionScore", 60);
        return meta;
    }

    private static void invokeBoundaryOnlyPromotion(
            DebugEventTracePromotionService service,
            String phase,
            Map<String, Object> traceMeta,
            String where) throws Exception {
        java.lang.reflect.Method method = java.util.Arrays.stream(service.getClass().getMethods())
                .filter(candidate -> "promoteStageBoundaryBreadcrumbsOnly".equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(method, "stage-boundary-only promotion API must exist");
        method.invoke(service, phase, traceMeta, where);
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

    private static final class ThrowingTraceMap extends HashMap<String, Object> {
        @Override
        public Object put(String key, Object value) {
            throw new UnsupportedOperationException("ownerToken=private-token");
        }
    }
}
