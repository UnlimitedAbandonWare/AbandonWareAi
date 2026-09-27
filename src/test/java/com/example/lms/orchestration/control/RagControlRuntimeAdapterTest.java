package com.example.lms.orchestration.control;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagControlRuntimeAdapterTest {

    private final RagControlRuntimeAdapter adapter = new RagControlRuntimeAdapter();
    private final RagGuardProbeComposer composer = new RagGuardProbeComposer();

    @Test
    void completeRuntimeProofProducesAllSevenTypedStages() {
        List<RagControlFinding> findings = adapter.collect(
                healthyInput(),
                "timeline-raw-id",
                completeTimeline(),
                completeAttempts());

        assertEquals(7, findings.size());
        assertEquals(List.of(RagControlFinding.Stage.values()),
                findings.stream().map(RagControlFinding::stage).toList());
        assertTrue(findings.stream().allMatch(
                finding -> finding.lineageStatus() == RagControlFinding.LineageStatus.COMPLETE));
        assertTrue(findings.stream().allMatch(
                finding -> finding.failureClass() == RagControlFinding.FailureClass.NONE));
        assertTrue(findings.stream().allMatch(
                finding -> finding.lineageKey().startsWith("hash:")));
        assertFalse(findings.stream().anyMatch(
                finding -> finding.lineageKey().contains("timeline-raw-id")));

        RagActionPlan plan = composer.compose(findings);
        assertEquals(RagActionPlan.Action.CONTINUE, plan.action());
        assertTrue(plan.lineageComplete());
        Map<String, Object> finalEvidence = stage(findings, RagControlFinding.Stage.FINAL).evidence();
        assertEquals("hash:333333333333", finalEvidence.get("finalHash"));
        assertEquals("PRESENTATION_FINAL", finalEvidence.get("evidenceBoundary"));
        assertEquals(1, finalEvidence.get("logicalCallOrdinal"));
        assertEquals(1, finalEvidence.get("attemptOrdinal"));
    }

    @Test
    void missingOptionsOrResponseProofFailsClosedAsMissingLineage() {
        List<Map<String, Object>> incompleteAttempts = List.of(Map.of(
                "requestHash", "hash:111111111111",
                "promptHash", "hash:aaaaaaaaaaaa",
                "optionsHash", "hash:unknown",
                "responseHash", "hash:222222222222",
                "providerAttemptObserved", true,
                "responseObserved", true));

        List<RagControlFinding> findings = adapter.collect(
                healthyInput(),
                "timeline-id",
                completeTimeline(),
                incompleteAttempts);
        RagActionPlan plan = composer.compose(findings);

        assertTrue(findings.stream().allMatch(
                finding -> finding.lineageStatus() == RagControlFinding.LineageStatus.MISSING));
        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
    }

    @Test
    void missingPromptProofFailsClosedAsMissingLineage() {
        List<Map<String, Object>> incompleteAttempts = List.of(Map.of(
                "requestHash", "hash:111111111111",
                "optionsHash", "hash:222222222222",
                "responseHash", "hash:333333333333",
                "providerAttemptObserved", true,
                "responseObserved", true));

        RagActionPlan plan = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), incompleteAttempts));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
    }

    @Test
    void attemptMustBelongToTheSameTimelineRequest() {
        List<Map<String, Object>> mismatchedAttempts = List.of(Map.of(
                "requestHash", "hash:999999999999",
                "promptHash", "hash:aaaaaaaaaaaa",
                "optionsHash", "hash:222222222222",
                "responseHash", "hash:333333333333",
                "providerAttemptObserved", true,
                "responseObserved", true));

        RagActionPlan plan = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), mismatchedAttempts));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
    }

    @Test
    void oneGoodAttemptCannotHideAnotherCompletedRequestRegardlessOfOrder() {
        Map<String, Object> expected = completeAttempts().get(0);
        Map<String, Object> conflicting = Map.of(
                "requestHash", "hash:999999999999",
                "promptHash", "hash:bbbbbbbbbbbb",
                "optionsHash", "hash:444444444444",
                "responseHash", "hash:555555555555",
                "providerAttemptObserved", true,
                "responseObserved", true);

        RagActionPlan forward = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), List.of(expected, conflicting)));
        RagActionPlan reverse = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), List.of(conflicting, expected)));

        assertEquals(RagActionPlan.Action.HOLD, forward.action());
        assertEquals(RagActionPlan.Action.HOLD, reverse.action());
        assertFalse(forward.lineageComplete());
        assertFalse(reverse.lineageComplete());
    }

    @Test
    void completedAttemptsMustShareOnePromptOptionsAndResponseTuple() {
        Map<String, Object> expected = completeAttempts().get(0);
        Map<String, Object> conflicting = Map.of(
                "requestHash", "hash:111111111111",
                "promptHash", "hash:aaaaaaaaaaaa",
                "optionsHash", "hash:444444444444",
                "responseHash", "hash:555555555555",
                "providerAttemptObserved", true,
                "responseObserved", true);

        RagActionPlan plan = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), List.of(expected, conflicting)));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
    }

    @Test
    void duplicateMatchingReceiptBackedResponsesAreAmbiguous() {
        Map<String, Object> first = copyWith(completeAttempts().get(0),
                "attemptTotal", 2);
        Map<String, Object> second = copyWith(first,
                "attemptOrdinal", 2);

        RagActionPlan plan = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), List.of(first, second)));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
    }

    @Test
    void clientHttpResponseDoesNotCountAsProviderReceipt() {
        Map<String, Object> clientOnly = copyWith(completeAttempts().get(0),
                "evidenceBoundary", "client_http_response",
                "providerReceiptObserved", false,
                "providerReceiptSource", "not_observed",
                "providerAttemptObserved", false,
                "wireAttemptObserved", false);

        assertLineageHold(List.of(clientOnly), completeTimeline());
    }

    @Test
    void providerAndWireBooleansWithoutControlledReceiptFailClosed() {
        Map<String, Object> forged = copyWith(completeAttempts().get(0),
                "evidenceBoundary", "client_http_response",
                "providerReceiptObserved", false,
                "providerReceiptSource", "not_observed");

        assertLineageHold(List.of(forged), completeTimeline());
    }

    @Test
    void missingLogicalCallOrdinalFailsClosed() {
        Map<String, Object> missing = new LinkedHashMap<>(completeAttempts().get(0));
        missing.remove("logicalCallOrdinal");

        assertLineageHold(List.of(Map.copyOf(missing)), completeTimeline());
    }

    @Test
    void missingAttemptOrdinalFailsClosed() {
        Map<String, Object> missing = new LinkedHashMap<>(completeAttempts().get(0));
        missing.remove("attemptOrdinal");

        assertLineageHold(List.of(Map.copyOf(missing)), completeTimeline());
    }

    @Test
    void selectedFinalHashMustMatchReceiptBackedResponse() {
        List<Map<String, Object>> mismatchedFinal = List.of(
                completeTimeline().get(0),
                completeTimeline().get(1),
                Map.of(
                        "requestHash", "hash:111111111111",
                        "phase", "final_boundary",
                        "terminalClass", "none",
                        "finalHash", "hash:ffffffffffff"));

        assertLineageHold(completeAttempts(), mismatchedFinal);
    }

    @Test
    void attemptDropFailsClosedEvenWithOneOtherwiseCompleteRow() {
        Map<String, Object> dropped = copyWith(completeAttempts().get(0),
                "attemptTotal", 2,
                "attemptDropped", 1);

        assertLineageHold(List.of(dropped), completeTimeline());
    }

    @ParameterizedTest
    @MethodSource("invalidDroppedCounters")
    void malformedDropCountCannotCertifyCompleteRuntimeEvidence(Object value) {
        assertLineageHold(List.of(copyWith(completeAttempts().get(0), "attemptDropped", value)),
                completeTimeline());
    }

    private static java.util.stream.Stream<Object> invalidDroppedCounters() {
        return java.util.stream.Stream.of("bad", -1, 4_294_967_296L, 0.5d, Double.NaN, true);
    }

    @Test
    void missingDropCountCannotCertifyCompleteRuntimeEvidence() {
        Map<String, Object> missing = new LinkedHashMap<>(completeAttempts().get(0));
        missing.remove("attemptDropped");
        assertLineageHold(List.of(Map.copyOf(missing)), completeTimeline());
    }

    @Test
    void overflowingOrdinalCannotAliasTheFirstAttempt() {
        assertLineageHold(List.of(copyWith(completeAttempts().get(0),
                "attemptOrdinal", 4_294_967_297L)), completeTimeline());
    }

    @Test
    void exactZeroRepresentationsPreserveValidEvidence() {
        for (Object zero : List.of(0L, "0", new java.math.BigDecimal("0.0"))) {
            RagActionPlan plan = composer.compose(adapter.collect(healthyInput(), "timeline-id",
                    completeTimeline(), List.of(copyWith(completeAttempts().get(0), "attemptDropped", zero))));
            assertTrue(plan.lineageComplete());
            assertEquals(RagActionPlan.Action.CONTINUE, plan.action());
        }
    }

    @Test
    void oneFailedAttemptAndOneUniqueSelectedSuccessRemainCoherent() {
        Map<String, Object> failed = copyWith(completeAttempts().get(0),
                "attemptOrdinal", 1,
                "attemptTotal", 2,
                "outcome", "failed",
                "terminalClass", "error",
                "evidenceBoundary", "client_http_response",
                "providerReceiptObserved", false,
                "providerReceiptSource", "not_observed",
                "providerAttemptObserved", false,
                "wireAttemptObserved", false,
                "responseObserved", false,
                "responseHash", "hash:unknown");
        Map<String, Object> selected = copyWith(completeAttempts().get(0),
                "attemptOrdinal", 2,
                "attemptTotal", 2);

        RagActionPlan plan = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", completeTimeline(), List.of(failed, selected)));

        assertEquals(RagActionPlan.Action.CONTINUE, plan.action());
        assertTrue(plan.lineageComplete());
    }

    @Test
    void zeroResultAndAfterFilterStarvationRemainDistinct() {
        RagControlRuntimeAdapter.RuntimeInput zero = new RagControlRuntimeAdapter.RuntimeInput(
                true, 0, 0, false, true, true, false);
        RagControlRuntimeAdapter.RuntimeInput starved = new RagControlRuntimeAdapter.RuntimeInput(
                true, 4, 0, false, true, true, false);

        RagControlFinding zeroRetrieval = stage(adapter.collect(
                zero, "timeline", completeTimeline(), completeAttempts()),
                RagControlFinding.Stage.RETRIEVAL);
        RagControlFinding starvedRetrieval = stage(adapter.collect(
                starved, "timeline", completeTimeline(), completeAttempts()),
                RagControlFinding.Stage.RETRIEVAL);

        assertEquals(RagControlFinding.FailureClass.ZERO_RESULT, zeroRetrieval.failureClass());
        assertEquals(RagControlFinding.FailureClass.AFTER_FILTER_STARVATION, starvedRetrieval.failureClass());
        assertEquals(RagActionPlan.Action.DEGRADE, zeroRetrieval.proposedAction());
        assertEquals(RagActionPlan.Action.DEGRADE, starvedRetrieval.proposedAction());
    }

    @Test
    void existingReleaseGuardBecomesAnIrreversibleHardHold() {
        RagControlRuntimeAdapter.RuntimeInput held = new RagControlRuntimeAdapter.RuntimeInput(
                true, 2, 1, false, true, true, true);

        RagActionPlan plan = composer.compose(adapter.collect(
                held, "timeline", completeTimeline(), completeAttempts()));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertTrue(plan.hardGuardLocked());
        assertTrue(plan.findings().stream().anyMatch(finding ->
                finding.authority() == RagControlFinding.Authority.HARD_GUARD));
    }

    @Test
    void blankFinalAnswerIsClassifiedAsSilentFailure() {
        RagControlRuntimeAdapter.RuntimeInput blank = new RagControlRuntimeAdapter.RuntimeInput(
                true, 2, 1, true, true, true, false);

        RagControlFinding finalFinding = stage(adapter.collect(
                blank, "timeline", completeTimeline(), completeAttempts()),
                RagControlFinding.Stage.FINAL);

        assertEquals(RagControlFinding.FailureClass.SILENT_FAILURE, finalFinding.failureClass());
        assertEquals(RagActionPlan.Action.HOLD, finalFinding.proposedAction());
        assertEquals(RagControlFinding.Authority.VERIFICATION, finalFinding.authority());
    }

    @Test
    void typedPresentationInputIsInternalAndRoundTripsWithoutPublicTraceExposure() {
        TraceStore.clear();
        RagControlRuntimeAdapter.RuntimeInput input = healthyInput();
        try {
            RagControlRuntimeAdapter.capturePresentationInput(input);

            assertEquals(input, RagControlRuntimeAdapter.currentPresentationInput());
            assertFalse(TraceStore.getAll().containsKey(RagControlRuntimeAdapter.PRESENTATION_INPUT_TRACE_KEY));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void unfinishedRagBoundaryProducesSevenStageSpecificGaps() {
        List<RagControlFinding> findings = adapter.collect(
                RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded(true),
                "timeline-id",
                List.of(),
                List.of());

        assertEquals(7, findings.size());
        assertEquals(List.of(RagControlFinding.Stage.values()),
                findings.stream().map(RagControlFinding::stage).toList());
        assertTrue(findings.stream().allMatch(finding ->
                finding.failureClass() == RagControlFinding.FailureClass.OBSERVABILITY_GAP));
        assertTrue(findings.stream().allMatch(finding ->
                finding.evidenceStatus() == RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED));
    }

    @Test
    void unfinishedBoundaryPreservesKnownHardGuardAsAnIrreversibleHold() {
        RagControlRuntimeAdapter.RuntimeInput input = new RagControlRuntimeAdapter.RuntimeInput(
                true, 0, 0, true, false, false, true, false);

        RagActionPlan plan = composer.compose(adapter.collect(
                input, "timeline-id", List.of(), List.of()));

        assertTrue(plan.hardGuardLocked());
        assertTrue(plan.shouldStop());
        assertEquals("existing_release_guard_hold", plan.reasonCode());
    }

    private static RagControlRuntimeAdapter.RuntimeInput healthyInput() {
        return new RagControlRuntimeAdapter.RuntimeInput(
                true, 3, 2, false, true, true, false);
    }

    private static List<Map<String, Object>> completeTimeline() {
        return List.of(
                Map.of(
                        "requestHash", "hash:111111111111",
                        "phase", "dispatch",
                        "terminalClass", "none"),
                Map.of(
                        "requestHash", "hash:111111111111",
                        "phase", "pending",
                        "terminalClass", "none"),
                Map.of(
                        "requestHash", "hash:111111111111",
                        "phase", "final_boundary",
                        "terminalClass", "none",
                        "finalHash", "hash:333333333333"));
    }

    private static List<Map<String, Object>> completeAttempts() {
        return List.of(Map.ofEntries(
                Map.entry("requestHash", "hash:111111111111"),
                Map.entry("logicalCallOrdinal", 1),
                Map.entry("attemptOrdinal", 1),
                Map.entry("outcome", "success"),
                Map.entry("terminalClass", "success"),
                Map.entry("evidenceBoundary", "provider_receive"),
                Map.entry("promptHash", "hash:aaaaaaaaaaaa"),
                Map.entry("optionsHash", "hash:222222222222"),
                Map.entry("responseHash", "hash:333333333333"),
                Map.entry("httpRequestBodyHash", "sha256:" + "4".repeat(64)),
                Map.entry("httpRequestBodyUtf8ByteCount", 128),
                Map.entry("httpResponseBodyHash", "sha256:" + "5".repeat(64)),
                Map.entry("httpResponseBodyUtf8ByteCount", 96),
                Map.entry("modelAdapterAttemptObserved", true),
                Map.entry("clientHttpExchangeObserved", true),
                Map.entry("clientHttpResponseObserved", true),
                Map.entry("providerReceiptObserved", true),
                Map.entry("providerReceiptSource", "controlled_http_server"),
                Map.entry("providerAttemptObserved", true),
                Map.entry("wireAttemptObserved", true),
                Map.entry("responseObserved", true),
                Map.entry("attemptTotal", 1),
                Map.entry("attemptDropped", 0)));
    }

    private void assertLineageHold(
            List<Map<String, Object>> attempts,
            List<Map<String, Object>> timeline) {
        RagActionPlan plan = composer.compose(adapter.collect(
                healthyInput(), "timeline-id", timeline, attempts));
        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
    }

    private static Map<String, Object> copyWith(Map<String, Object> source, Object... pairs) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(source);
        for (int index = 0; index < pairs.length; index += 2) {
            copy.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return Map.copyOf(copy);
    }

    private static RagControlFinding stage(
            List<RagControlFinding> findings,
            RagControlFinding.Stage stage) {
        return findings.stream()
                .filter(finding -> finding.stage() == stage)
                .findFirst()
                .orElseThrow();
    }
}
