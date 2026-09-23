package com.example.lms.debug.ai;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUsageLedgerTest {

    @Test
    void exposesProcessLifetimeCountOnlyMetadataBeforeTheFirstObservation() {
        ChatUsageLedger ledger = new ChatUsageLedger();

        Map<String, Object> snapshot = ledger.snapshot();

        assertEquals("awx.chat-usage.v1", snapshot.get("schemaVersion"));
        assertEquals(Boolean.TRUE, snapshot.get("captureEnabled"));
        assertEquals(Boolean.FALSE, snapshot.get("observed"));
        assertEquals("process_lifetime", snapshot.get("counterScope"));
        assertEquals(Boolean.FALSE, snapshot.get("restartDurable"));
        assertEquals(Boolean.FALSE, snapshot.get("overflowed"));
        assertEquals("not_observed", snapshot.get("wireAttemptCoverage"));
        assertTrue(number(snapshot, "counterEpochStartedAtMs") > 0L);
        assertFalse(snapshot.toString().contains("private prompt payload"));
        assertFalse(snapshot.toString().contains("private answer payload"));
        assertFalse(snapshot.toString().contains("private-session-id"));
        assertFalse(snapshot.toString().contains("private-model-id"));
    }

    @Test
    void recordsConfiguredCapsAndProviderUsageWithoutTreatingUnknownAsZero() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt explicit = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        160,
                        2_048,
                        2_048,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));
        assertTrue(explicit.responseReceived(new TokenUsage(100, 50, 150)));
        assertTrue(explicit.markSuccessful());

        ChatUsageLedger.ModelAttempt omitted = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.SELF_HEAL,
                ChatUsageLedger.ConfiguredCap.omitted(
                        160,
                        2_048,
                        ChatUsageLedger.CapSource.SELF_HEAL));
        assertTrue(omitted.timedOut());

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(2L, number(model, "attempts"));
        assertEquals(0L, number(model, "inFlight"));
        assertEquals(1L, number(model, "responseReceived"));
        assertEquals(0L, number(model, "failedBeforeResponse"));
        assertEquals(1L, number(model, "timedOut"));
        assertEquals(0L, number(model, "cancelled"));
        assertEquals(2L, number(model, "profileTargetObservedCount"));
        assertEquals(160L, number(model, "latestProfileTarget"));
        assertEquals(2L, number(model, "normalizedRequestCapObservedCount"));
        assertEquals(2_048L, number(model, "latestNormalizedRequestCap"));
        assertEquals(1L, number(model, "configuredCapKnownAttemptCount"));
        assertEquals(1L, number(model, "unknownAttemptCount"));
        assertEquals(2_048L, number(model, "configuredCapSum"));
        assertEquals(2_048L, number(model, "configuredCapMin"));
        assertEquals(2_048L, number(model, "configuredCapMax"));
        assertEquals("explicit", model.get("lastSuccessfulCapState"));
        assertEquals("max_tokens", model.get("lastSuccessfulParameterKind"));
        assertEquals(2_048, model.get("lastSuccessfulConfiguredCap"));
        assertEquals(1L, number(model, "providerUsageObservedAttemptCount"));
        assertEquals(1L, number(model, "providerUsageMissingAttemptCount"));
        assertEquals(false, model.get("providerOutputTokensComplete"));
        assertEquals(100L, number(model, "providerInputTokens"));
        assertEquals(50L, number(model, "providerOutputTokens"));
        assertEquals(150L, number(model, "providerTotalTokens"));
        assertEquals(1L, number(nested(model, "byPurpose"), "primary"));
        assertEquals(1L, number(nested(model, "byPurpose"), "self_heal"));
        assertEquals(1L, number(nested(model, "capStateCounts"), "explicit"));
        assertEquals(1L, number(nested(model, "capStateCounts"), "omitted"));
        assertEquals(1L, number(nested(model, "parameterKindCounts"), "max_tokens"));
        assertEquals(1L, number(nested(model, "parameterKindCounts"), "omitted"));
    }

    @Test
    void timeoutWinsExactlyOnceWhenAWorkerCompletesLate() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(160, 2_048));

        assertTrue(attempt.timedOut());
        assertFalse(attempt.responseReceived(new TokenUsage(1, 2, 3)));
        assertFalse(attempt.cancelled());

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "attempts"));
        assertEquals(0L, number(model, "inFlight"));
        assertEquals(0L, number(model, "responseReceived"));
        assertEquals(1L, number(model, "timedOut"));
        assertEquals(0L, number(model, "providerUsageObservedAttemptCount"));
        assertNull(model.get("lastSuccessfulCapState"));
    }

    @Test
    void failedLaterAttemptCannotReplaceTheLastSuccessfulConfiguredCap() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt successful = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        240, 2_048, 2_048,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));
        successful.responseReceived(new TokenUsage(1, 2, 3));
        successful.markSuccessful();
        ChatUsageLedger.ModelAttempt failed = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.RETRY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        240, 8_192, 8_192,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));
        failed.failedBeforeResponse();

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(8_192L, number(model, "latestNormalizedRequestCap"));
        assertEquals(2_048L, number(model, "lastSuccessfulConfiguredCap"));
    }

    @Test
    void expansionInvariantsRemainTrueWhileInvocationAndModelAreInFlight() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ExpansionAttempt expansion = ledger.beginExpansion();
        assertEquals(1L, number(nested(ledger.snapshot(), "answerExpansion"), "inFlightBeforeModel"));
        assertEquals(0L, number(nested(ledger.snapshot(), "answerExpansion"), "inFlightAfterModel"));
        assertExpansionInvariants(ledger.snapshot());

        ChatUsageLedger.ModelAttempt model = expansion.beginModelInvocation(
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(240, null));
        assertEquals(0L, number(nested(ledger.snapshot(), "answerExpansion"), "inFlightBeforeModel"));
        assertEquals(1L, number(nested(ledger.snapshot(), "answerExpansion"), "inFlightAfterModel"));
        assertExpansionInvariants(ledger.snapshot());

        model.responseReceived(null);
        model.markSuccessful();
        expansion.accepted();
        assertEquals(0L, number(nested(ledger.snapshot(), "answerExpansion"), "inFlightBeforeModel"));
        assertEquals(0L, number(nested(ledger.snapshot(), "answerExpansion"), "inFlightAfterModel"));
        assertExpansionInvariants(ledger.snapshot());
    }

    @Test
    void partialUsagePreservesUnknownTokenDimensionsAndCoverage() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));

        assertTrue(attempt.responseReceived(new TokenUsage(null, 7, null)));

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "providerUsageObservedAttemptCount"));
        assertEquals(0L, number(model, "providerUsageMissingAttemptCount"));
        assertEquals(0L, number(model, "providerInputTokensObservedAttemptCount"));
        assertEquals(1L, number(model, "providerOutputTokensObservedAttemptCount"));
        assertEquals(0L, number(model, "providerTotalTokensObservedAttemptCount"));
        assertEquals(false, model.get("providerInputTokensComplete"));
        assertEquals(true, model.get("providerOutputTokensComplete"));
        assertEquals(false, model.get("providerTotalTokensComplete"));
        assertEquals(7L, number(model, "providerOutputTokens"));
    }

    @Test
    void mixedObservedAndMissingUsageMarksOutputTotalPartialAndReconcilesCoverage() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt observed = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        observed.responseReceived(new TokenUsage(2, 3, 5));
        ChatUsageLedger.ModelAttempt missing = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.RETRY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        missing.responseReceived(null);
        ChatUsageLedger.ModelAttempt timedOut = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.RETRY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        timedOut.timedOut();

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "providerUsageObservedAttemptCount"));
        assertEquals(2L, number(model, "providerUsageMissingAttemptCount"));
        assertEquals(3L, number(model, "providerUsageObservedAttemptCount")
                + number(model, "providerUsageMissingAttemptCount"));
        assertEquals(false, model.get("providerOutputTokensComplete"));
    }

    @Test
    void rejectedUsageMissingKeepsRejectedTokenCostIncomplete() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ExpansionAttempt accepted = ledger.beginExpansion();
        ChatUsageLedger.ModelAttempt acceptedModel = accepted.beginModelInvocation(
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        acceptedModel.responseReceived(new TokenUsage(2, 10, 12));
        accepted.accepted();

        ChatUsageLedger.ExpansionAttempt rejected = ledger.beginExpansion();
        ChatUsageLedger.ModelAttempt rejectedModel = rejected.beginModelInvocation(
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        rejectedModel.responseReceived(null);
        rejected.rejectedEmpty();

        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertEquals(0L, number(expansion, "rejectedProviderOutputTokens"));
        assertEquals(false, expansion.get("rejectedProviderOutputTokensComplete"));
    }

    @Test
    void acceptedOnlyExpansionKeepsRejectedTokenCostNotApplicable() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ExpansionAttempt accepted = ledger.beginExpansion();
        ChatUsageLedger.ModelAttempt model = accepted.beginModelInvocation(
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        model.responseReceived(new TokenUsage(2, 10, 12));
        accepted.accepted();

        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertEquals(0L, number(expansion, "rejectedProviderOutputTokens"));
        assertEquals(false, expansion.get("rejectedProviderOutputTokensComplete"));
    }

    @Test
    void expansionOutcomesPreserveBothInvariantsAndRejectedTokenCost() {
        ChatUsageLedger ledger = new ChatUsageLedger();

        assertTrue(ledger.beginExpansion().skippedBeforeModel());

        ChatUsageLedger.ExpansionAttempt accepted = ledger.beginExpansion();
        ChatUsageLedger.ModelAttempt acceptedCall = accepted.beginModelInvocation(
                ChatUsageLedger.ConfiguredCap.explicit(
                        400,
                        null,
                        400,
                        ChatUsageLedger.ParameterKind.NUM_PREDICT,
                        ChatUsageLedger.CapSource.PROFILE_TARGET));
        assertTrue(acceptedCall.responseReceived(new TokenUsage(20, 30, 50)));
        assertTrue(accepted.accepted());

        ChatUsageLedger.ExpansionAttempt rejected = ledger.beginExpansion();
        ChatUsageLedger.ModelAttempt rejectedCall = rejected.beginModelInvocation(
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(400, null));
        assertTrue(rejectedCall.responseReceived(new TokenUsage(10, 7, 17)));
        assertTrue(rejected.rejectedNumeric());

        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertEquals(3L, number(expansion, "invocations"));
        assertEquals(1L, number(expansion, "skippedBeforeModel"));
        assertEquals(0L, number(expansion, "failedBeforeModel"));
        assertEquals(2L, number(expansion, "modelInvocations"));
        assertEquals(1L, number(expansion, "accepted"));
        assertEquals(1L, number(expansion, "rejectedNumeric"));
        assertEquals(0L, number(expansion, "rejectedNoEvidence"));
        assertEquals(0L, number(expansion, "rejectedTooShort"));
        assertEquals(0L, number(expansion, "rejectedEmpty"));
        assertEquals(0L, number(expansion, "failedAfterModel"));
        assertEquals(0L, number(expansion, "timedOut"));
        assertEquals(0L, number(expansion, "cancelled"));
        assertEquals(2L, number(expansion, "providerUsageObservedAttemptCount"));
        assertEquals(0L, number(expansion, "providerUsageMissingAttemptCount"));
        assertEquals(37L, number(expansion, "providerOutputTokens"));
        assertEquals(7L, number(expansion, "rejectedProviderOutputTokens"));
        assertEquals(400L, number(expansion, "requestedTokenBudgetUpperBoundSum"));
        assertEquals(1L, number(expansion, "unknownBudgetAttemptCount"));
        assertEquals(0.5d, (Double) expansion.get("rejectionRate"));
    }

    @Test
    void concurrentAttemptsKeepBothSnapshotsCoherent() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 120; i++) {
            final int index = i;
            workers.submit(() -> {
                ChatUsageLedger.ModelAttempt model = ledger.beginModelInvocation(
                        ChatUsageLedger.ModelPurpose.PRIMARY,
                        ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
                if (index % 3 == 0) {
                    model.timedOut();
                } else if (index % 3 == 1) {
                    model.failedBeforeResponse();
                } else {
                    model.responseReceived(null);
                }
                ChatUsageLedger.ExpansionAttempt expansion = ledger.beginExpansion();
                ChatUsageLedger.ModelAttempt expansionModel = expansion.beginModelInvocation(
                        ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(240, null));
                expansionModel.responseReceived(null);
                if (index % 2 == 0) {
                    expansion.accepted();
                } else {
                    expansion.rejectedNoEvidence();
                }
            });
        }
        workers.shutdown();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));

        Map<String, Object> snapshot = ledger.snapshot();
        Map<String, Object> model = nested(snapshot, "modelInvocations");
        Map<String, Object> expansion = nested(snapshot, "answerExpansion");
        assertEquals(number(model, "attempts"),
                number(model, "inFlight")
                        + number(model, "responseReceived")
                        + number(model, "failedBeforeResponse")
                        + number(model, "timedOut")
                        + number(model, "cancelled"));
        assertEquals(number(expansion, "invocations"),
                number(expansion, "skippedBeforeModel")
                        + number(expansion, "failedBeforeModel")
                        + number(expansion, "modelInvocations"));
        assertEquals(number(expansion, "modelInvocations"),
                number(expansion, "accepted")
                        + number(expansion, "rejectedNumeric")
                        + number(expansion, "rejectedNoEvidence")
                        + number(expansion, "rejectedTooShort")
                        + number(expansion, "rejectedEmpty")
                        + number(expansion, "failedAfterModel")
                        + number(expansion, "timedOut")
                        + number(expansion, "cancelled"));
    }

    @Test
    void additiveCostCountersSaturateWithoutWrapping() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ReflectionTestUtils.setField(ledger, "configuredCapSum", Long.MAX_VALUE - 2L);

        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        null,
                        64,
                        64,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));
        attempt.responseReceived(new TokenUsage(1, 1, 2));

        Map<String, Object> snapshot = ledger.snapshot();
        assertEquals(Boolean.TRUE, snapshot.get("overflowed"));
        assertEquals(Long.MAX_VALUE, number(nested(snapshot, "modelInvocations"), "configuredCapSum"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static void assertExpansionInvariants(Map<String, Object> snapshot) {
        Map<String, Object> expansion = nested(snapshot, "answerExpansion");
        assertEquals(number(expansion, "invocations"),
                number(expansion, "skippedBeforeModel")
                        + number(expansion, "failedBeforeModel")
                        + number(expansion, "modelInvocations"));
        assertEquals(number(expansion, "modelInvocations"),
                number(expansion, "accepted")
                        + number(expansion, "rejectedNumeric")
                        + number(expansion, "rejectedNoEvidence")
                        + number(expansion, "rejectedTooShort")
                        + number(expansion, "rejectedEmpty")
                        + number(expansion, "failedAfterModel")
                        + number(expansion, "timedOut")
                        + number(expansion, "cancelled"));
    }
}
