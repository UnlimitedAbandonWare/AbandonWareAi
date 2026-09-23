package com.example.lms.guard;

import java.util.Locale;

/**
 * Bounded, request-scoped conversation posture. This policy is deliberately
 * independent from evidence confidence and security decisions.
 */
public record ConversationFrameV1(
        Mode mode,
        Stance stance,
        LightweightRole lightweightRole,
        boolean multimodalInputPresent,
        boolean suppressOptionalRefinement,
        boolean suppressMemoryWrites,
        ReasonCode reasonCode) {

    public ConversationFrameV1 {
        mode = mode == null ? Mode.OFF : mode;
        stance = stance == null ? Stance.STANDARD : stance;
        reasonCode = reasonCode == null ? ReasonCode.DEFAULT : reasonCode;

        boolean enforcedNonStandard = mode == Mode.ENFORCE && stance != Stance.STANDARD;
        lightweightRole = enforcedNonStandard
                ? LightweightRole.ABSTAIN
                : LightweightRole.OBSERVE_ONLY;
        suppressOptionalRefinement = enforcedNonStandard;
        suppressMemoryWrites = enforcedNonStandard;
    }

    public enum Mode {
        OFF,
        SHADOW,
        ENFORCE;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return OFF;
            }
            try {
                return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return OFF;
            }
        }
    }

    public enum Stance {
        STANDARD,
        REPAIR,
        SUPPORTIVE_CHECK_IN,
        SAFETY_FIRST
    }

    public enum LightweightRole {
        OBSERVE_ONLY,
        ABSTAIN
    }

    public enum ReasonCode {
        DEFAULT,
        EXPLICIT_STOP,
        ASSISTANT_BOUNDARY_COMPLAINT,
        DISTRESS_CHECK_IN,
        IMMEDIATE_SAFETY_SIGNAL
    }

    public static ConversationFrameV1 off(boolean multimodalInputPresent) {
        return new ConversationFrameV1(
                Mode.OFF,
                Stance.STANDARD,
                LightweightRole.OBSERVE_ONLY,
                multimodalInputPresent,
                false,
                false,
                ReasonCode.DEFAULT);
    }

    public boolean enforcementActive() {
        return mode == Mode.ENFORCE;
    }

    public boolean allowsDirectShortCircuit() {
        return !enforcedNonStandard();
    }

    public boolean allowsOptionalRefinement() {
        return !enforcedNonStandard();
    }

    public boolean allowsOptionalExpansion() {
        return !enforcedNonStandard();
    }

    public boolean suppressesMemoryWrites() {
        return suppressMemoryWrites;
    }

    public boolean shouldTrace() {
        return mode != Mode.OFF;
    }

    private boolean enforcedNonStandard() {
        return enforcementActive() && stance != Stance.STANDARD;
    }
}
