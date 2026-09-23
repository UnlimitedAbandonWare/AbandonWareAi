package com.example.lms.infra.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SelectionDecisionLedgerTest {

    @Test
    void freezesLedgerHashInputsAndTerminalDigest() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        SelectionCoordinate coordinate = new SelectionCoordinate(
                "llm-router.weighted-exploration", "route:auto", 1L, 0L);
        ledger.record(SelectionDecisionLedger.Lane.ROUTER, coordinate,
                List.of("route:a", "route:b"), 1, "", true, false);

        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
        assertThat(snapshot.decisionDigest())
                .isEqualTo("7b4cfe15f9aaf721fe73133247ced66ecb89e14dbe1a6a59c0bb33aecc3dc349");
        assertThat(snapshot.decisionCount()).isEqualTo(1);
        assertThat(snapshot.drawCount()).isEqualTo(1);
        assertThat(snapshot.routerDrawCount()).isEqualTo(1);
    }

    @Test
    void candidateDriftMarksPartialWithoutChangingTheFirstDecision() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        SelectionCoordinate coordinate =
                new SelectionCoordinate("strategy.softmax", "strategy:dynamic", 0, 0);
        ledger.record(SelectionDecisionLedger.Lane.STRATEGY, coordinate,
                List.of("web_first", "vector_first"), 0, "", true, false);
        String firstDigest = ledger.snapshot(true).decisionDigest();

        ledger.record(SelectionDecisionLedger.Lane.STRATEGY, coordinate,
                List.of("vector_first", "web_first"), 0, "", true, false);
        ledger.record(SelectionDecisionLedger.Lane.STRATEGY, coordinate,
                List.of("web_first", "other"), 0, "", true, false);

        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
        assertThat(snapshot.coherence()).isEqualTo(SelectionEntropyCoherence.PARTIAL);
        assertThat(snapshot.reason()).isEqualTo(SelectionEntropyReason.CANDIDATE_DRIFT);
        assertThat(snapshot.decisionCount()).isEqualTo(1);
        assertThat(snapshot.candidateDriftCount()).isEqualTo(1);
        assertThat(snapshot.decisionDigest()).isEqualTo(firstDigest);
    }

    @Test
    void capacityAndParallelRecordingNeverChangeSelectionOrLeakKeys() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay(2);
        IntStream.range(0, 3).parallel().forEach(index -> ledger.record(
                SelectionDecisionLedger.Lane.ENSEMBLE,
                new SelectionCoordinate("ensemble.profile.shuffle", "node:support", 0, index),
                List.of("profile:a", "profile:b"), index % 2, "", true, false));

        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
        assertThat(snapshot.decisionCount()).isEqualTo(2);
        assertThat(snapshot.drawCount()).isEqualTo(2);
        assertThat(snapshot.ensembleDrawCount()).isEqualTo(2);
        assertThat(snapshot.coherence()).isEqualTo(SelectionEntropyCoherence.PARTIAL);
        assertThat(snapshot.reason()).isEqualTo(SelectionEntropyReason.DECISION_CAP_REACHED);
        assertThat(snapshot.toString()).doesNotContain("profile:a", "profile:b", "node:support");
    }

    @Test
    void terminalFailureWinsOverLaterDriftAndCapacitySignals() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay(1);
        SelectionCoordinate first =
                new SelectionCoordinate("router.pick", "route:auto", 0L, 0L);
        SelectionCoordinate second =
                new SelectionCoordinate("router.pick", "route:backup", 0L, 0L);

        ledger.record(SelectionDecisionLedger.Lane.ROUTER, first,
                List.of("route:a", "route:b"), 0, "", true, false);
        ledger.markFailure(SelectionEntropyReason.DERIVATION_INVALID);
        ledger.record(SelectionDecisionLedger.Lane.ROUTER, first,
                List.of("route:b", "route:a"), 0, "", true, false);
        ledger.record(SelectionDecisionLedger.Lane.ROUTER, second,
                List.of("route:a", "route:b"), 0, "", true, false);
        ledger.markFailure(SelectionEntropyReason.CONTEXT_MISSING);

        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
        assertThat(snapshot.coherence()).isEqualTo(SelectionEntropyCoherence.FAILED);
        assertThat(snapshot.reason()).isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
        assertThat(snapshot.decisionCount()).isEqualTo(1);
        assertThat(snapshot.candidateDriftCount()).isEqualTo(1);
    }

    @Test
    void matchingCoordinateAndCandidateSetKeepsTheFirstStoredRow() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        SelectionCoordinate coordinate =
                new SelectionCoordinate("router.pick", "route:auto", 0L, 0L);

        ledger.record(SelectionDecisionLedger.Lane.ROUTER, coordinate,
                List.of("route:a", "route:b"), 0, "", true, false);
        String firstDigest = ledger.snapshot(true).decisionDigest();
        ledger.record(SelectionDecisionLedger.Lane.ENSEMBLE, coordinate,
                List.of("route:a", "route:b"), 1, "fallback", false, true);

        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
        assertThat(snapshot.decisionDigest()).isEqualTo(firstDigest);
        assertThat(snapshot.decisionCount()).isEqualTo(1);
        assertThat(snapshot.drawCount()).isEqualTo(1);
        assertThat(snapshot.stableTieBreakCount()).isZero();
        assertThat(snapshot.coherence()).isEqualTo(SelectionEntropyCoherence.MATCHED);
    }

    @Test
    void rejectsMissingStableKeysInvalidFallbackAndInvalidFailureReason() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        SelectionCoordinate coordinate =
                new SelectionCoordinate("router.pick", "route:auto", 0L, 0L);

        assertReason(() -> ledger.record(SelectionDecisionLedger.Lane.ROUTER, coordinate,
                        List.of("route:a", " "), 0, "", true, false),
                SelectionEntropyReason.STABLE_KEY_MISSING);
        assertReason(() -> ledger.record(SelectionDecisionLedger.Lane.ROUTER, coordinate,
                        List.of("route:a"), 0, "Not-Safe", true, false),
                SelectionEntropyReason.DERIVATION_INVALID);
        assertThatThrownBy(() -> ledger.markFailure(SelectionEntropyReason.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selection_entropy_failure_reason_invalid");
    }

    private static void assertReason(Runnable operation, SelectionEntropyReason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(reason);
    }
}
