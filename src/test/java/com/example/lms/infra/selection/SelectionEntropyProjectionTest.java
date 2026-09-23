package com.example.lms.infra.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SelectionEntropyProjectionTest {

    private static final String EMPTY_DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void projectionUsesOnlyBoundedLedgerScalarsAcrossEarlyAndTerminalReplay() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        SelectionEntropy replay = SelectionEntropyFactory.replay(
                SelectionReplaySpec.v1(seedDeckZero()));

        SelectionEntropyProjection early =
                SelectionEntropyProjection.from(replay, ledger, false, false);
        SelectionEntropyProjection terminal =
                SelectionEntropyProjection.from(replay, ledger, true, false);

        assertThat(early.coherenceStatus()).isEqualTo("accepted");
        assertThat(terminal.coherenceStatus()).isEqualTo("matched");
        assertThat(terminal.schema()).isEqualTo("awx.selection-entropy.v1");
        assertThat(terminal.mode()).isEqualTo("replay");
        assertThat(terminal.algorithmVersion()).isEqualTo("selection-entropy-v1");
        assertThat(terminal.decisionDigest()).isEqualTo(EMPTY_DIGEST);
        assertThat(terminal.replayAccepted()).isTrue();
        assertThat(terminal.seedFingerprint()).isEqualTo("009e8892e5b3");
        assertThat(terminal.completionOrderDeterministic()).isFalse();
        assertThat(terminal.toString())
                .doesNotContain("route:a", "node:support", "profile:a", "00010203");
    }

    @Test
    void standardProjectionNeverClaimsReplayOrCarriesAFingerprint() {
        SelectionEntropyProjection projection = SelectionEntropyProjection.from(
                SelectionEntropyFactory.standard(),
                SelectionDecisionLedger.forStandard(),
                true,
                false);

        assertThat(projection.mode()).isEqualTo("standard");
        assertThat(projection.replayAccepted()).isFalse();
        assertThat(projection.seedFingerprint()).isNull();
        assertThat(projection.coherenceStatus()).isEqualTo("not_requested");
        assertThat(projection.reasonCode()).isEmpty();
    }

    @Test
    void rejectsSemanticallyImpossibleModeAndReplayCombinations() {
        assertThatThrownBy(() -> projection(
                "standard", true, "accepted", "0123456789ab", 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection(
                "replay", false, "accepted", null, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection(
                "replay", true, "accepted", "NOT-A-HASH", 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection(
                "replay", true, "unknown", "0123456789ab", 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeCountsAndUnknownReasonCodes() {
        assertThatThrownBy(() -> projection(
                "replay", true, "partial", "0123456789ab", -1, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection(
                "replay", true, "partial", "0123456789ab", 10_001, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection(
                "replay", true, "partial", "0123456789ab", 0, "made_up_reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SelectionEntropyProjection projection(
            String mode,
            boolean replayAccepted,
            String coherence,
            String fingerprint,
            int decisionCount,
            String reason) {
        return new SelectionEntropyProjection(
                "awx.selection-entropy.v1",
                mode,
                "selection-entropy-v1",
                replayAccepted,
                coherence,
                fingerprint,
                EMPTY_DIGEST,
                decisionCount,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                reason);
    }

    private static byte[] seedDeckZero() {
        byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) {
            seed[index] = (byte) index;
        }
        return seed;
    }
}
