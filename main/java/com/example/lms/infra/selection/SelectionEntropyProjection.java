package com.example.lms.infra.selection;

import java.util.Arrays;
import java.util.Objects;

public record SelectionEntropyProjection(
        String schema,
        String mode,
        String algorithmVersion,
        boolean replayAccepted,
        String coherenceStatus,
        String seedFingerprint,
        String decisionDigest,
        int decisionCount,
        int drawCount,
        int stableTieBreakCount,
        int candidateDriftCount,
        int routerDrawCount,
        int strategyDrawCount,
        int ensembleDrawCount,
        boolean completionOrderDeterministic,
        String reasonCode) {

    private static final String SCHEMA = "awx.selection-entropy.v1";
    private static final String ALGORITHM = ReplaySelectionEntropy.ALGORITHM_VERSION;

    public SelectionEntropyProjection {
        boolean knownCoherence = Arrays.stream(SelectionEntropyCoherence.values())
                .map(SelectionEntropyCoherence::wireValue)
                .anyMatch(value -> value.equals(coherenceStatus));
        boolean knownReason = Arrays.stream(SelectionEntropyReason.values())
                .map(SelectionEntropyReason::code)
                .anyMatch(value -> value.equals(reasonCode));
        boolean validCounts = validCount(decisionCount)
                && validCount(drawCount)
                && validCount(stableTieBreakCount)
                && validCount(candidateDriftCount)
                && validCount(routerDrawCount)
                && validCount(strategyDrawCount)
                && validCount(ensembleDrawCount);
        boolean validDigest = decisionDigest != null
                && decisionDigest.matches("[a-f0-9]{64}");
        boolean standard = "standard".equals(mode);
        boolean replay = "replay".equals(mode);
        boolean validStandard = !standard || (!replayAccepted
                && seedFingerprint == null
                && "not_requested".equals(coherenceStatus)
                && "".equals(reasonCode));
        boolean validReplay = !replay || (replayAccepted
                && seedFingerprint != null
                && seedFingerprint.matches("[a-f0-9]{12}")
                && !"not_requested".equals(coherenceStatus));

        if (!SCHEMA.equals(schema)
                || !ALGORITHM.equals(algorithmVersion)
                || (!standard && !replay)
                || !knownCoherence
                || !knownReason
                || !validCounts
                || !validDigest
                || !validStandard
                || !validReplay) {
            throw new IllegalArgumentException("selection_entropy_projection_invalid");
        }
    }

    public static SelectionEntropyProjection from(
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger,
            boolean terminal,
            boolean completionOrderDeterministic) {
        Objects.requireNonNull(entropy, "entropy");
        SelectionDecisionLedger.Snapshot value =
                Objects.requireNonNull(ledger, "ledger").snapshot(terminal);
        String fingerprint = entropy instanceof ReplaySelectionEntropy replay
                ? replay.seedFingerprint()
                : null;
        return new SelectionEntropyProjection(
                SCHEMA,
                entropy.mode().wireValue(),
                entropy.algorithmVersion(),
                entropy.mode() == SelectionEntropyMode.REPLAY,
                value.coherence().wireValue(),
                fingerprint,
                value.decisionDigest(),
                value.decisionCount(),
                value.drawCount(),
                value.stableTieBreakCount(),
                value.candidateDriftCount(),
                value.routerDrawCount(),
                value.strategyDrawCount(),
                value.ensembleDrawCount(),
                completionOrderDeterministic,
                value.reason().code());
    }

    private static boolean validCount(int count) {
        return count >= 0 && count <= SelectionDecisionLedger.MAX_DECISIONS;
    }
}
