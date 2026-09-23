package com.example.lms.infra.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SelectionCoordinateTest {

    @Test
    void canonicalizesActorAndUsesUnambiguousBigEndianEncoding() {
        SelectionCoordinate coordinate = new SelectionCoordinate(
                "ensemble.profile.temperature", "Node:Support", 7L, 11L);

        byte[] decision = "ensemble.profile.temperature".getBytes(StandardCharsets.UTF_8);
        byte[] actor = "node:support".getBytes(StandardCharsets.UTF_8);
        ByteBuffer expected = ByteBuffer.allocate(4 + decision.length + 4 + actor.length + 16)
                .putInt(decision.length).put(decision)
                .putInt(actor.length).put(actor)
                .putLong(7L).putLong(11L);

        assertThat(coordinate.actorKey()).isEqualTo("node:support");
        assertThat(coordinate.encoded()).containsExactly(expected.array());
    }

    @Test
    void rejectsInvalidDecisionActorAndOrdinalsWithTheFixedReason() {
        assertInvalid(new SelectionCoordinateCall("Upper", "node:support", 0L, 0L));
        assertInvalid(new SelectionCoordinateCall("router.pick", "space actor", 0L, 0L));
        assertInvalid(new SelectionCoordinateCall("router.pick", "route:auto", -1L, 0L));
        assertInvalid(new SelectionCoordinateCall("router.pick", "route:auto", 0L, -1L));
    }

    @Test
    void exposesOnlyTheApprovedWireValuesAndReasonCodes() {
        assertThat(SelectionEntropyMode.values())
                .extracting(SelectionEntropyMode::wireValue)
                .containsExactly("standard", "replay");
        assertThat(SelectionEntropyCoherence.values())
                .extracting(SelectionEntropyCoherence::wireValue)
                .containsExactly(
                        "not_requested", "accepted", "matched", "partial",
                        "failed", "forbidden", "invalid");
        assertThat(SelectionEntropyReason.values())
                .extracting(SelectionEntropyReason::code)
                .containsExactly(
                        "",
                        "selection_entropy_replay_forbidden",
                        "selection_entropy_replay_invalid",
                        "selection_entropy_algorithm_unsupported",
                        "selection_entropy_replay_init_failed",
                        "selection_entropy_context_missing",
                        "selection_entropy_coordinate_invalid",
                        "selection_entropy_stable_key_missing",
                        "selection_entropy_derivation_invalid",
                        "selection_entropy_candidate_drift",
                        "selection_entropy_decision_cap_reached");
    }

    @Test
    void exposesOnlyTheFixedReasonCodeThroughTheException() {
        SelectionEntropyException error =
                new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);

        assertThat(error.reason()).isEqualTo(SelectionEntropyReason.COORDINATE_INVALID);
        assertThat(error).hasMessage("selection_entropy_coordinate_invalid");
        assertThat(error.getCause()).isNull();
    }

    private static void assertInvalid(SelectionCoordinateCall call) {
        assertThatThrownBy(call::create)
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.COORDINATE_INVALID);
    }

    private record SelectionCoordinateCall(String decision, String actor, long attempt, long draw) {
        SelectionCoordinate create() {
            return new SelectionCoordinate(decision, actor, attempt, draw);
        }
    }
}
