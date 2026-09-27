package com.example.lms.orchestration.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RagControlRolloutStateTest {

    @Test
    void promotesOnlyAfterThreeHundredCleanEligibleShadowTurns() {
        RagControlRolloutState state = state();

        for (int i = 0; i < 299; i++) {
            state.record(clean(10));
        }
        assertEquals(RagControlRolloutState.Mode.SHADOW, state.mode());

        state.record(clean(10));

        assertEquals(RagControlRolloutState.Mode.ENFORCE, state.mode());
        assertEquals(300, state.snapshot().eligibleCount());
        assertEquals(10, state.snapshot().p95OverheadMillis());
    }

    @Test
    void criticalSignalImmediatelyReturnsEnforceToFreshShadowWindow() {
        RagControlRolloutState state = state();
        for (int i = 0; i < 300; i++) {
            state.record(clean(5));
        }
        assertEquals(RagControlRolloutState.Mode.ENFORCE, state.mode());

        state.record(new RagControlRolloutState.Observation(true, true, false, false, 5));

        assertEquals(RagControlRolloutState.Mode.SHADOW, state.mode());
        assertEquals(0, state.snapshot().eligibleCount());
    }

    @Test
    void ineligibleCriticalSignalStillInvalidatesTheEnforceLease() {
        RagControlRolloutState state = state();
        for (int i = 0; i < 300; i++) {
            state.record(clean(5));
        }
        RagControlRolloutState.RolloutLease enforceLease = state.lease();

        state.record(new RagControlRolloutState.Observation(false, true, false, false, 5));

        assertEquals(RagControlRolloutState.Mode.SHADOW, state.mode());
        assertEquals(0, state.snapshot().eligibleCount());
        assertNotEquals(enforceLease.generation(), state.lease().generation());
    }

    @Test
    void generationChangesOnPromotionAndCriticalRollback() {
        RagControlRolloutState state = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        long initial = state.lease().generation();

        state.record(clean(1));
        long promoted = state.lease().generation();
        state.record(new RagControlRolloutState.Observation(true, false, true, false, 1));
        long rolledBack = state.lease().generation();

        assertNotEquals(initial, promoted);
        assertNotEquals(promoted, rolledBack);
        assertEquals(RagControlRolloutState.Mode.SHADOW, state.mode());
    }

    @Test
    void gapRateAboveOnePercentPreventsPromotion() {
        RagControlRolloutState state = state();
        for (int i = 0; i < 296; i++) {
            state.record(clean(10));
        }
        for (int i = 0; i < 4; i++) {
            state.record(new RagControlRolloutState.Observation(true, false, false, true, 10));
        }

        assertEquals(RagControlRolloutState.Mode.SHADOW, state.mode());
        assertEquals(4, state.snapshot().gapOrUnclassifiedCount());
    }

    @Test
    void p95AboveTwentyMillisecondsPreventsPromotion() {
        RagControlRolloutState state = state();
        for (int i = 0; i < 284; i++) {
            state.record(clean(20));
        }
        for (int i = 0; i < 16; i++) {
            state.record(clean(21));
        }

        assertEquals(RagControlRolloutState.Mode.SHADOW, state.mode());
        assertEquals(21, state.snapshot().p95OverheadMillis());
    }

    @Test
    void newProcessStateAlwaysStartsInShadow() {
        RagControlRolloutState oldState = state();
        for (int i = 0; i < 300; i++) {
            oldState.record(clean(1));
        }

        assertEquals(RagControlRolloutState.Mode.ENFORCE, oldState.mode());
        assertEquals(RagControlRolloutState.Mode.SHADOW, state().mode());
    }

    private static RagControlRolloutState state() {
        return new RagControlRolloutState(new RagControlProperties(300, 0.01d, 20));
    }

    private static RagControlRolloutState.Observation clean(long overheadMillis) {
        return new RagControlRolloutState.Observation(true, false, false, false, overheadMillis);
    }
}
