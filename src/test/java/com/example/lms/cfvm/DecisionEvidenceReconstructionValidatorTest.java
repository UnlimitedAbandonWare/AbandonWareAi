package com.example.lms.cfvm;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.HashUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionEvidenceReconstructionValidatorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void missingEvidenceMakesDecisionUnreconstructable() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0001",
                List.of("hash:relation0001"),
                "hash:request0001",
                List.of("hash:options0001"),
                true,
                false)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of(Map.of(
                "relationId", "hash:relation0001",
                "decisionId", "hash:decision0001",
                "evidenceId", "")));
        TraceStore.put("decision.reconstruction.lineage", List.of());

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("missingEvidenceCount", 1L);
        assertMetric("orphanRelationCount", 0L);
        assertMetric("lineageMismatchCount", 0L);
        assertEquals(0.0d, ((Number) TraceStore.get(
                "decision.reconstruction.reconstructionRate")).doubleValue());
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("missing_evidence"));
    }

    @Test
    void wrongRelationIdCountsAsOrphanRelation() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0002",
                List.of("hash:relation-wrong"),
                "hash:request0002",
                List.of("hash:options0002"),
                true,
                false)));
        TraceStore.put("decision.reconstruction.evidence", List.of(Map.of(
                "evidenceId", "hash:evidence0002")));
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", List.of());

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("missingEvidenceCount", 0L);
        assertMetric("orphanRelationCount", 1L);
        assertMetric("lineageMismatchCount", 0L);
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("orphan_relation"));
    }

    @Test
    void decisionWithoutRequiredRelationCountsAsOrphanDecision() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0003",
                List.of(),
                "hash:request0003",
                List.of(),
                true,
                false)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", List.of());

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("missingEvidenceCount", 0L);
        assertMetric("orphanRelationCount", 1L);
        assertMetric("lineageMismatchCount", 0L);
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("orphan_decision"));
    }

    @Test
    void duplicateAttemptOrdinalCountsAsLineageMismatch() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0004",
                List.of(),
                "hash:request0004",
                List.of("hash:options0004", "hash:options0004-conflict"),
                false,
                true)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", List.of(
                lineage("hash:decision0004", "hash:request0004", "hash:options0004", 1L, true, true),
                lineage("hash:decision0004", "hash:request0004", "hash:options0004-conflict", 1L,
                        true, true)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("duplicate_lineage"));
    }

    @Test
    void providerResponseWithoutAttemptCountsAsLineageMismatch() throws Exception {
        DebugEventStore debugEventStore = new DebugEventStore();
        setField(debugEventStore, "ndjsonEnabled", false);
        DecisionTraceAspect aspect = new DecisionTraceAspect();
        setField(aspect, "debugEventStore", debugEventStore);
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0005",
                List.of(),
                "hash:request0005",
                List.of("hash:options0005"),
                false,
                true)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", List.of(
                lineage("hash:decision0005", "hash:request0005", "hash:options0005", 1L, false, true)));

        invokeAudit(aspect);

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        assertMetric("providerAttemptCount", 0L);
        assertMetric("providerResponseCount", 1L);
        assertMetric("linkedDecisionCount", 0L);
        assertMetric("providerResponseWithoutAttemptCount", 1L);
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("provider_response_without_attempt"));
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("provider_attempt_missing"));
        DebugEvent event = debugEventStore.listByProbe(DebugProbeType.ORCHESTRATION, 10).stream()
                .filter(row -> SafeRedactor.hashValue("decision-evidence-reconstruction")
                        .equals(row.fingerprint()))
                .findFirst()
                .orElseThrow();
        assertEquals(0L, ((Number) event.data().get("providerAttemptCount")).longValue());
        assertEquals(1L, ((Number) event.data().get("providerResponseCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("linkedDecisionCount")).longValue());
        assertTrue(event.data().toString().contains("provider_response_without_attempt"));
    }

    @Test
    void providerAttemptMissingWhenLinkedLineageHasNoProviderObservation() throws Exception {
        putLineageOnlyDecision("hash:decision0010", "hash:request0010", "hash:options0010",
                List.of(lineage("hash:decision0010", "hash:request0010", "hash:options0010",
                        1L, false, false)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("providerAttemptCount", 0L);
        assertMetric("providerResponseCount", 0L);
        assertMetric("linkedDecisionCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        assertEquals(0.0d, metricRate("runtimeLineagePassRate"));
        assertTrue(reasons().contains("provider_attempt_missing"));
    }

    @Test
    void providerResponseMissingAfterObservedAttempt() throws Exception {
        putLineageOnlyDecision("hash:decision0011", "hash:request0011", "hash:options0011",
                List.of(lineage("hash:decision0011", "hash:request0011", "hash:options0011",
                        1L, true, false)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("providerAttemptCount", 1L);
        assertMetric("providerResponseCount", 0L);
        assertMetric("linkedDecisionCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        assertTrue(reasons().contains("provider_response_missing"));
        assertTrue(reasons().contains("decision_evidence_lineage_disagreement"));
    }

    @Test
    void successfulFallbackDoesNotHideAnEarlierAttemptWithoutResponse() throws Exception {
        putLineageOnlyDecision("hash:decision0016", "hash:request0016", "hash:options0016",
                List.of(
                        lineage("hash:decision0016", "hash:request0016", "hash:options0016",
                                1L, true, false),
                        lineage("hash:decision0016", "hash:request0016", "hash:options0016",
                                2L, true, true)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("providerAttemptCount", 2L);
        assertMetric("providerResponseCount", 1L);
        assertMetric("lineageMismatchCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("providerAttemptWithoutResponseCount", 1L);
        assertTrue(reasons().contains("provider_attempt_without_response"));
    }

    @Test
    void requiredDecisionLineageMissingWhenNoRowsExist() throws Exception {
        putLineageOnlyDecision("hash:decision0012", "hash:request0012", "hash:options0012", List.of());

        invokeAudit(new DecisionTraceAspect());

        assertMetric("decisionLineageMissingCount", 1L);
        assertMetric("linkedDecisionCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        assertTrue(reasons().contains("decision_lineage_missing"));
    }

    @Test
    void requiredDecisionLineageRejectsRowsWithoutAnExplicitDecisionLink() throws Exception {
        putLineageOnlyDecision("hash:decision0013", "hash:request0013", "hash:options0013",
                List.of(lineage("", "hash:request0013", "hash:options0013", 1L, true, true)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("decisionLineageMissingCount", 1L);
        assertMetric("linkedDecisionCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        assertTrue(reasons().contains("decision_lineage_unlinked"));
    }

    @Test
    void requestOrOptionsHashDriftCountsAsLineageMismatch() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0006",
                List.of(),
                "hash:request0006",
                List.of("hash:options0006"),
                false,
                true)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", List.of(
                lineage("hash:decision0006", "hash:request-other", "hash:options-other", 1L, true, true)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        String reasons = String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"));
        assertTrue(reasons.contains("request_hash_mismatch"));
        assertTrue(reasons.contains("options_hash_mismatch"));
        assertMetric("requestHashMismatchCount", 1L);
        assertMetric("optionsHashMismatchCount", 1L);
    }

    @Test
    void missingRequestAndOptionsHashesCannotSatisfyRequiredLineage() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0008",
                List.of(),
                "",
                List.of(),
                false,
                true)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", List.of(
                lineage("hash:decision0008", "", "", 1L, true, true)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertMetric("lineageMismatchCount", 1L);
        String reasons = String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"));
        assertTrue(reasons.contains("request_hash_missing"));
        assertTrue(reasons.contains("options_hash_missing"));
    }

    @Test
    void validEvidenceAndLineageReconstructsDecision() throws Exception {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                "hash:decision0007",
                List.of("hash:relation0007"),
                "hash:request0007",
                List.of("hash:options0007"),
                true,
                true)));
        TraceStore.put("decision.reconstruction.evidence", List.of(Map.of(
                "evidenceId", "hash:evidence0007")));
        TraceStore.put("decision.reconstruction.relations", List.of(Map.of(
                "relationId", "hash:relation0007",
                "decisionId", "hash:decision0007",
                "evidenceId", "hash:evidence0007")));
        TraceStore.put("decision.reconstruction.lineage", List.of(
                lineage("hash:decision0007", "hash:request0007", "hash:options0007", 1L, true, true)));

        invokeAudit(new DecisionTraceAspect());

        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 1L);
        assertMetric("missingEvidenceCount", 0L);
        assertMetric("orphanRelationCount", 0L);
        assertMetric("lineageMismatchCount", 0L);
        assertMetric("providerAttemptCount", 1L);
        assertMetric("providerResponseCount", 1L);
        assertMetric("linkedDecisionCount", 1L);
        assertMetric("providerResponseWithoutAttemptCount", 0L);
        assertMetric("requestHashMismatchCount", 0L);
        assertMetric("optionsHashMismatchCount", 0L);
        assertMetric("decisionLineageMissingCount", 0L);
        assertEquals(1.0d, ((Number) TraceStore.get(
                "decision.reconstruction.reconstructionRate")).doubleValue());
        assertEquals(1.0d, metricRate("runtimeLineagePassRate"));
        assertTrue(String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"))
                .contains("reconstructable"));
    }

    @Test
    void finalDecisionLinksCapturedRuntimeAttemptToTheDecision() throws Throwable {
        DebugEventStore debugEventStore = new DebugEventStore();
        setField(debugEventStore, "ndjsonEnabled", false);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        DecisionTraceAspect aspect = new DecisionTraceAspect();
        setField(aspect, "debugEventStore", debugEventStore);
        setField(aspect, "modelRuntimeHealthTracker", tracker);

        String requestId = "synthetic-runtime-request";
        String timelineId = tracker.beginRequestTimeline(requestId, "synthetic-runtime-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "synthetic-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", "synthetic-model", null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        TraceStore.put("requestId", SafeRedactor.hashValue(requestId));
        recordSyntheticProviderExchange(tracker, timelineId, true, true);
        TraceStore.put("finalAnswer.postprocess.contentHash", SafeRedactor.hashValue("synthetic-content"));

        ChatResult expected = ChatResult.of(
                "synthetic final answer", "synthetic:model", false, Set.of(), List.of());
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(expected);

        assertSame(expected, aspect.traceFinalDecision(joinPoint));
        assertEquals(42L, invokePersistedAssistantResponse(aspect, expected.content()));

        List<?> decisions = (List<?>) TraceStore.get("decision.reconstruction.decisions");
        List<?> lineageRows = (List<?>) TraceStore.get("decision.reconstruction.lineage");
        Map<?, ?> decisionRow = (Map<?, ?>) decisions.get(0);
        Map<?, ?> lineageRow = (Map<?, ?>) lineageRows.get(0);
        assertEquals(decisionRow.get("decisionId"), lineageRow.get("decisionId"));
        assertEquals(HashUtil.sha256(expected.content()), decisionRow.get("workflowResponseHash"));
        assertMetric("providerAttemptCount", 1L);
        assertMetric("providerResponseCount", 1L);
        assertMetric("linkedDecisionCount", 1L);
        assertMetric("lineageMismatchCount", 0L);
        assertEquals(1.0d, metricRate("runtimeLineagePassRate"));
    }

    @Test
    void persistedAssistantResponseLinksControllerShapedContentToDecisionAndEvidence() throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        DecisionTraceAspect aspect = new DecisionTraceAspect();
        setField(aspect, "modelRuntimeHealthTracker", tracker);

        String requestId = "synthetic-shaped-request";
        String timelineId = tracker.beginRequestTimeline(requestId, "synthetic-shaped-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "synthetic-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", "synthetic-model", null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        TraceStore.put("requestId", SafeRedactor.hashValue(requestId));
        recordSyntheticProviderExchange(tracker, timelineId, true, true);
        TraceStore.put("finalAnswer.postprocess.contentHash", HashUtil.sha256("workflow answer"));

        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "[W1]", "web", "synthetic-title", "https://example.invalid/source",
                null, null, null, 1, 0.9d, "synthetic");
        ChatResult workflowResult = ChatResult.of(
                "workflow answer", "synthetic:model", true, Set.of("WEB"), List.of(evidence));
        ProceedingJoinPoint workflowJoinPoint = mock(ProceedingJoinPoint.class);
        when(workflowJoinPoint.proceed()).thenReturn(workflowResult);
        aspect.traceFinalDecision(workflowJoinPoint);

        assertMetric("missingFinalResponseCount", 1L);
        assertMetric("finalResponseMismatchCount", 1L);
        assertMetric("reconstructableCount", 0L);
        assertTrue(reasons().contains("final_response_missing"));

        String shapedContent = "controller-shaped final answer";
        assertEquals(42L, invokePersistedAssistantResponse(aspect, shapedContent));

        List<?> decisions = (List<?>) TraceStore.get("decision.reconstruction.decisions");
        List<?> evidenceRows = (List<?>) TraceStore.get("decision.reconstruction.evidence");
        List<?> finalResponses = (List<?>) TraceStore.get("decision.reconstruction.finalResponses");
        Map<?, ?> decisionRow = (Map<?, ?>) decisions.get(0);
        Map<?, ?> evidenceRow = (Map<?, ?>) evidenceRows.get(0);
        Map<?, ?> responseRow = (Map<?, ?>) finalResponses.get(0);
        assertEquals(decisionRow.get("decisionId"), responseRow.get("decisionId"));
        assertEquals(HashUtil.sha256(shapedContent), responseRow.get("contentHash"));
        assertEquals(List.of(evidenceRow.get("evidenceId")), responseRow.get("evidenceIds"));
        assertFalse(responseRow.toString().contains(shapedContent));
        assertMetric("linkedFinalResponseCount", 1L);
        assertMetric("missingFinalResponseCount", 0L);
        assertMetric("finalResponseMismatchCount", 0L);
        assertMetric("reconstructableCount", 1L);
    }

    @Test
    void finalDecisionDetectsRequestHashDriftFromTheRuntimeTimeline() throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        DecisionTraceAspect aspect = new DecisionTraceAspect();
        setField(aspect, "modelRuntimeHealthTracker", tracker);

        String timelineId = tracker.beginRequestTimeline("timeline-request", "synthetic-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "synthetic-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", "synthetic-model", null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        TraceStore.put("requestId", SafeRedactor.hashValue("different-final-decision-request"));
        recordSyntheticProviderExchange(tracker, timelineId, true, true);

        ChatResult expected = ChatResult.of("synthetic drift answer", "synthetic:model", false, Set.of(), List.of());
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(expected);

        aspect.traceFinalDecision(joinPoint);
        invokePersistedAssistantResponse(aspect, expected.content());

        assertMetric("requestHashMismatchCount", 1L);
        assertMetric("lineageMismatchCount", 1L);
        assertTrue(reasons().contains("request_hash_mismatch"));
    }

    @Test
    void finalAdvicePublishesRequiredMetricsToExistingDebugStore() throws Throwable {
        DebugEventStore debugEventStore = new DebugEventStore();
        setField(debugEventStore, "ndjsonEnabled", false);
        DecisionTraceAspect aspect = new DecisionTraceAspect();
        setField(aspect, "debugEventStore", debugEventStore);
        TraceStore.put("requestId", "hash:request0009");
        TraceStore.put("finalAnswer.releaseStatus", "released");
        TraceStore.put("finalAnswer.releaseReason", "verified");
        TraceStore.put("finalAnswer.postprocess.contentHash", "hash:content0009");

        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "[W1]", "web", "synthetic-title", "https://example.invalid/source",
                null, null, null, 1, 0.9d, "synthetic");
        ChatResult expected = ChatResult.of(
                "synthetic final answer",
                "local:test",
                true,
                Set.of("WEB"),
                List.of(evidence));
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(expected);

        Object actual = aspect.traceFinalDecision(joinPoint);
        invokePersistedAssistantResponse(aspect, expected.content());

        assertSame(expected, actual);
        assertMetric("checkedDecisionCount", 1L);
        assertMetric("reconstructableCount", 1L);
        assertEquals(1.0d, ((Number) TraceStore.get(
                "decision.reconstruction.reconstructionRate")).doubleValue());
        DebugEvent event = debugEventStore.listByProbe(DebugProbeType.ORCHESTRATION, 10).stream()
                .filter(row -> SafeRedactor.hashValue("decision-evidence-reconstruction")
                        .equals(row.fingerprint()))
                .findFirst()
                .orElseThrow();
        assertEquals("DecisionTraceAspect.auditTraceContext", event.where());
        assertEquals(1L, ((Number) event.data().get("checkedDecisionCount")).longValue());
        assertEquals(1L, ((Number) event.data().get("reconstructableCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("missingEvidenceCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("orphanRelationCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("lineageMismatchCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("providerAttemptCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("providerResponseCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("linkedDecisionCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("providerResponseWithoutAttemptCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("providerAttemptWithoutResponseCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("decisionLineageMissingCount")).longValue());
        assertEquals(1L, ((Number) event.data().get("finalResponseCount")).longValue());
        assertEquals(1L, ((Number) event.data().get("linkedFinalResponseCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("missingFinalResponseCount")).longValue());
        assertEquals(0L, ((Number) event.data().get("finalResponseMismatchCount")).longValue());
        assertEquals(1.0d, ((Number) event.data().get("reconstructionRate")).doubleValue());
        assertEquals(1.0d, ((Number) event.data().get("runtimeLineagePassRate")).doubleValue());
        assertTrue(event.data().toString().contains("reconstructable"));
    }

    private static Map<String, Object> decision(
            String decisionId,
            List<String> relationIds,
            String requestIdHash,
            List<String> optionsHashes,
            boolean evidenceRequired,
            boolean lineageRequired) {
        return Map.of(
                "decisionId", decisionId,
                "relationIds", relationIds,
                "requestIdHash", requestIdHash,
                "optionsHashes", optionsHashes,
                "evidenceRequired", evidenceRequired,
                "lineageRequired", lineageRequired);
    }

    private static Map<String, Object> lineage(
            String decisionId,
            String requestIdHash,
            String optionsHash,
            long attemptOrdinal,
            boolean providerAttemptObserved,
            boolean responseObserved) {
        return Map.of(
                "decisionId", decisionId,
                "requestIdHash", requestIdHash,
                "optionsHash", optionsHash,
                "attemptOrdinal", attemptOrdinal,
                "providerAttemptObserved", providerAttemptObserved,
                "responseObserved", responseObserved);
    }

    private static void putLineageOnlyDecision(
            String decisionId,
            String requestIdHash,
            String optionsHash,
            List<Map<String, Object>> lineageRows) {
        TraceStore.put("decision.reconstruction.decisions", List.of(decision(
                decisionId, List.of(), requestIdHash, List.of(optionsHash), false, true)));
        TraceStore.put("decision.reconstruction.evidence", List.of());
        TraceStore.put("decision.reconstruction.relations", List.of());
        TraceStore.put("decision.reconstruction.lineage", lineageRows);
    }

    private static void recordSyntheticProviderExchange(
            ModelRuntimeHealthTracker tracker,
            String timelineId,
            boolean providerAttemptObserved,
            boolean responseObserved) {
        String requestBodyHash = "sha256:" + "1".repeat(64);
        String responseBodyHash = "sha256:" + "2".repeat(64);
        tracker.recordRequestAttemptEvidence(
                timelineId,
                "primary",
                tracker.redactedRequestAttemptRoute(
                        "synthetic-route", "synthetic-model", null, "openai_chat_completions"),
                responseObserved ? "success" : "failed",
                responseObserved ? "none" : "unknown",
                responseObserved ? "success" : "error",
                1L,
                SafeRedactor.hashValue("synthetic-prompt"),
                SafeRedactor.hashValue("synthetic-options"),
                1,
                1,
                responseObserved ? SafeRedactor.hashValue("synthetic-response") : "hash:unknown",
                responseObserved ? 18 : 0,
                16,
                17,
                responseObserved ? 18 : 0,
                requestBodyHash,
                16,
                responseBodyHash,
                responseObserved ? 18 : 0,
                true,
                true,
                responseObserved,
                providerAttemptObserved,
                false,
                responseObserved);
        if (providerAttemptObserved) {
            assertTrue(invokeControlledProviderReceipt(
                    tracker, timelineId, requestBodyHash, responseBodyHash));
        }
    }

    private static boolean invokeControlledProviderReceipt(
            ModelRuntimeHealthTracker tracker,
            String timelineId,
            String requestBodyHash,
            String responseBodyHash) {
        try {
            Method method = ModelRuntimeHealthTracker.class.getDeclaredMethod(
                    "recordControlledProviderReceipt",
                    String.class,
                    int.class,
                    int.class,
                    String.class,
                    int.class,
                    String.class,
                    int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(
                    tracker, timelineId, 1, 1, requestBodyHash, 16, responseBodyHash, 18);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String reasons() {
        return String.valueOf(TraceStore.get("decision.reconstruction.reasonCodes"));
    }

    private static double metricRate(String suffix) {
        return ((Number) TraceStore.get("decision.reconstruction." + suffix)).doubleValue();
    }

    private static void invokeAudit(DecisionTraceAspect aspect) throws Exception {
        ensureDefaultFinalResponses();
        try {
            Method method = DecisionTraceAspect.class.getDeclaredMethod("auditTraceContext");
            method.setAccessible(true);
            method.invoke(aspect);
        } catch (NoSuchMethodException missingFeature) {
            fail("DecisionTraceAspect must audit decision/evidence relations from TraceStore");
        }
    }

    private static void ensureDefaultFinalResponses() {
        if (TraceStore.get("decision.reconstruction.finalResponses") != null) {
            return;
        }
        Set<String> knownEvidenceIds = new LinkedHashSet<>();
        Object rawEvidence = TraceStore.get("decision.reconstruction.evidence");
        if (rawEvidence instanceof List<?> evidenceRows) {
            for (Object value : evidenceRows) {
                if (value instanceof Map<?, ?> row && row.get("evidenceId") != null) {
                    String evidenceId = String.valueOf(row.get("evidenceId"));
                    if (!evidenceId.isBlank()) {
                        knownEvidenceIds.add(evidenceId);
                    }
                }
            }
        }

        List<Map<String, Object>> responses = new ArrayList<>();
        Object rawDecisions = TraceStore.get("decision.reconstruction.decisions");
        if (rawDecisions instanceof List<?> decisions) {
            for (Object value : decisions) {
                if (!(value instanceof Map<?, ?> decision) || decision.get("decisionId") == null) {
                    continue;
                }
                String decisionId = String.valueOf(decision.get("decisionId"));
                if (decisionId.isBlank()) {
                    continue;
                }
                Set<String> linkedEvidenceIds = new LinkedHashSet<>();
                Object rawRelations = TraceStore.get("decision.reconstruction.relations");
                if (rawRelations instanceof List<?> relations) {
                    for (Object relationValue : relations) {
                        if (!(relationValue instanceof Map<?, ?> relation)
                                || !decisionId.equals(String.valueOf(relation.get("decisionId")))) {
                            continue;
                        }
                        String evidenceId = String.valueOf(relation.get("evidenceId"));
                        if (knownEvidenceIds.contains(evidenceId)) {
                            linkedEvidenceIds.add(evidenceId);
                        }
                    }
                }
                String contentHash = HashUtil.sha256("fixture-final-response|" + decisionId);
                responses.add(Map.of(
                        "responseId", SafeRedactor.hashValue(decisionId + "|" + contentHash),
                        "decisionId", decisionId,
                        "contentHash", contentHash,
                        "evidenceIds", List.copyOf(linkedEvidenceIds)));
            }
        }
        TraceStore.put("decision.reconstruction.finalResponses", List.copyOf(responses));
    }

    private static Object invokePersistedAssistantResponse(
            DecisionTraceAspect aspect,
            String content) {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        when(historyService.appendMessageReturningId(7L, "assistant", content)).thenReturn(42L);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(historyService);
        proxyFactory.setInterfaces(ChatHistoryService.class);
        proxyFactory.addAspect(aspect);
        ChatHistoryService proxy = proxyFactory.getProxy();
        return proxy.appendMessageReturningId(7L, "assistant", content);
    }

    private static void assertMetric(String suffix, long expected) {
        Object value = TraceStore.get("decision.reconstruction." + suffix);
        assertTrue(value instanceof Number, () -> "missing numeric metric: " + suffix);
        assertEquals(expected, ((Number) value).longValue(), suffix);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
