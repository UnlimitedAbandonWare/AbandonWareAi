package com.example.lms.service.trace;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.ensemble.EnsembleJudgeService;
import com.example.lms.ensemble.EvidenceGroundedTriadicDebugAdjudicator;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugCopilotTriadicAdjudicationTest {

    @Test
    void explicitAdjudicationPublishesOnlySafeScalarsToDebugEventStore() {
        String rawSecret = "sk-" + "1234567890abcdef1234";
        DebugEventStore store = new DebugEventStore();
        store.emit(DebugProbeType.ORCHESTRATION, DebugEventLevel.WARN,
                "provider-timeout", "provider timeout", Map.of("rawQuery", rawSecret), null);
        store.emit(DebugProbeType.ORCHESTRATION, DebugEventLevel.WARN,
                "retrieval-starvation", "retrieval starvation", Map.of(), null);
        EvidenceGroundedTriadicDebugAdjudicator adjudicator =
                mock(EvidenceGroundedTriadicDebugAdjudicator.class);
        var expected = new EvidenceGroundedTriadicDebugAdjudicator.Adjudication(
                EnsembleJudgeService.DebugPatchDecision.HOLD,
                EnsembleJudgeService.DebugPatchConfidence.MEDIUM,
                "score_gap_too_small",
                "abc123def456",
                3,
                2,
                0.81d,
                0.78d,
                4,
                true);
        when(adjudicator.adjudicate(anyList(), any(EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate.class), anyString()))
                .thenReturn(expected);
        DebugCopilotService service = new DebugCopilotService(() -> Map.of(), adjudicator, store);

        var result = service.adjudicateLatestPatchCandidate(patchCandidate());

        assertEquals(expected, result);
        var event = store.list(20).stream()
                .filter(item -> SafeRedactor.hashValue("triadic-debug-adjudication").equals(item.fingerprint()))
                .findFirst()
                .orElseThrow();
        assertEquals("HOLD", event.data().get("decision"));
        assertEquals("score_gap_too_small", event.data().get("reasonCode"));
        assertEquals(true, event.data().get("advisoryOnly"));
        assertFalse(String.valueOf(event).contains(rawSecret));
        assertFalse(event.data().containsKey("rawPrompt"));
        assertFalse(event.data().containsKey("rawQuery"));
        assertTrue(String.valueOf(event.data()).length() < 1_000);
    }

    @Test
    void explicitAdjudicationPrependsTypedSmokeRouteEvidence() {
        DebugEventStore store = new DebugEventStore();
        store.emit(DebugProbeType.ORCHESTRATION, DebugEventLevel.WARN,
                "provider-timeout", "provider timeout", Map.of(), null);
        store.emit(DebugProbeType.ORCHESTRATION, DebugEventLevel.WARN,
                "retrieval-starvation", "retrieval starvation", Map.of(), null);
        EvidenceGroundedTriadicDebugAdjudicator adjudicator =
                mock(EvidenceGroundedTriadicDebugAdjudicator.class);
        var expected = EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
                "evidence_conflicted", "abc123def456", 4, 0, 0.0d, 0.0d, 0);
        var captured = new java.util.concurrent.atomic.AtomicReference<java.util.List<Map<String, Object>>>();
        when(adjudicator.adjudicate(anyList(), any(EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate.class), anyString()))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(0));
                    return expected;
                });
        Map<String, Object> snapshot = Map.of(
                "reportStale", false,
                "latest", Map.of(
                        "recommendedRoute", "native_ollama",
                        "attemptScores", Map.of(
                                "openAiCompatible", Map.of("score", 15, "status", 200,
                                        "verdict", "blank_response"),
                                "nativeOllama", Map.of("score", 100, "status", 200,
                                        "verdict", "usable", "contentLength", 6))));
        DebugCopilotService service = new DebugCopilotService(() -> snapshot, adjudicator, store);

        service.adjudicateLatestPatchCandidate(patchCandidate());

        assertTrue(captured.get().stream().anyMatch(row ->
                "NATIVE_OLLAMA".equals(row.get("routeFamily"))
                        && "SUCCESS".equals(row.get("routeOutcome"))));
        assertTrue(captured.get().stream().anyMatch(row ->
                "OPENAI_COMPATIBLE".equals(row.get("routeFamily"))
                        && "BLANK".equals(row.get("routeOutcome"))));
    }

    @Test
    void explicitAdjudicationPropagatesCancellation() {
        DebugEventStore store = new DebugEventStore();
        EvidenceGroundedTriadicDebugAdjudicator adjudicator =
                mock(EvidenceGroundedTriadicDebugAdjudicator.class);
        when(adjudicator.adjudicate(anyList(), any(EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate.class), anyString()))
                .thenThrow(new CancellationException("cancelled"));
        DebugCopilotService service = new DebugCopilotService(() -> Map.of(), adjudicator, store);

        assertThrows(CancellationException.class,
                () -> service.adjudicateLatestPatchCandidate(patchCandidate()));
    }

    @Test
    void debugEventPublicationFailureDoesNotReplaceCompletedAdjudication() {
        DebugEventStore store = mock(DebugEventStore.class);
        when(store.listFingerprints(12)).thenReturn(java.util.List.of(
                Map.of("fingerprint", "provider-timeout"),
                Map.of("fingerprint", "retrieval-starvation")));
        EvidenceGroundedTriadicDebugAdjudicator adjudicator =
                mock(EvidenceGroundedTriadicDebugAdjudicator.class);
        var expected = new EvidenceGroundedTriadicDebugAdjudicator.Adjudication(
                EnsembleJudgeService.DebugPatchDecision.APPLY,
                EnsembleJudgeService.DebugPatchConfidence.HIGH,
                "support_grounded",
                "abc123def456",
                2,
                2,
                0.86d,
                0.60d,
                3,
                true);
        when(adjudicator.adjudicate(
                anyList(), any(EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate.class), anyString()))
                .thenReturn(expected);
        doThrow(new IllegalStateException("event store unavailable")).when(store).emit(
                any(DebugProbeType.class),
                any(DebugEventLevel.class),
                anyString(),
                anyString(),
                anyString(),
                anyMap(),
                isNull());
        DebugCopilotService service = new DebugCopilotService(() -> Map.of(), adjudicator, store);

        var result = service.adjudicateLatestPatchCandidate(patchCandidate());

        assertEquals(expected, result);
    }

    @Test
    void fingerprintCollectionFailureReplacesPriorApplyWithFailSoftHold() {
        DebugEventStore store = mock(DebugEventStore.class);
        when(store.listFingerprints(12)).thenThrow(new IllegalStateException("fingerprint store unavailable"));
        EvidenceGroundedTriadicDebugAdjudicator adjudicator =
                mock(EvidenceGroundedTriadicDebugAdjudicator.class);
        var priorApply = new EvidenceGroundedTriadicDebugAdjudicator.Adjudication(
                EnsembleJudgeService.DebugPatchDecision.APPLY,
                EnsembleJudgeService.DebugPatchConfidence.HIGH,
                "support_grounded",
                "abc123def456",
                2,
                2,
                0.86d,
                0.60d,
                3,
                true);
        when(adjudicator.latest()).thenReturn(priorApply);
        DebugCopilotService service = new DebugCopilotService(() -> Map.of(), adjudicator, store);

        var returned = service.adjudicateLatestPatchCandidate(patchCandidate());

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, returned.decision());
        assertEquals("copilot_fail_soft", returned.reasonCode());
        assertEquals(returned, service.latestTriadicAdjudication());
    }

    private static EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate patchCandidate() {
        return new EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate(
                "Keep the completed peer trace when a later worker fails.",
                java.util.List.of("main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java"),
                "a".repeat(64));
    }
}
