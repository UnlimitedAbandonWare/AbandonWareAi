package com.example.lms.infra.resilience;

import com.example.lms.service.guard.GuardContext;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Standardized reasons for why an aux/LLM-stage call was blocked.
 *
 * <p>Each reason has:
 * <ul>
 *   <li>a stable {@link #code()} string for logs/traces</li>
 *   <li>a {@link #priority()} for deterministic selection when multiple signals are present</li>
 * </ul>
 */
public enum AuxBlockedReason {

    // NOTE: Priority is a policy table.
    // Higher wins when multiple candidates are present.

    DISABLED("disabled", 120),
    BREAKER_OPEN("breaker-open", 110),
    OTHER_AUX_BREAKER_OPEN("other-aux-breaker-open", 105),
    AUX_HARD_DOWN("aux-hard-down", 100),

    /** Stage policy clamp: explicit config-level OFF switch for expensive aux stages. */
    STAGE_POLICY_CLAMP("stage-policy-clamp", 95),

    BYPASS("bypass", 90),

    /** Failure-pattern feedback loop cooldown window (anti-fragile breaker feedback). */
    FAILURE_COOLDOWN("failure-cooldown", 85),

    AUX_DEGRADED("aux-degraded", 80),
    STRIKE("strike-mode", 70),
    COMPRESSION("compression-mode", 60),

    /** Signals (hints) to avoid UNKNOWN in traces when we only have secondary context. */
    FAULTMASK_SIGNAL("faultmask-signal", 50),
    IRREGULARITY_SIGNAL("irregularity-signal", 40),

    UNKNOWN("unknown", 0);

    private final String code;
    private final int priority;

    AuxBlockedReason(String code, int priority) {
        this.code = code;
        this.priority = priority;
    }

    public String code() {
        return code;
    }

    public int priority() {
        return priority;
    }

    /**
     * Policy order (highest priority first). Useful for debugging and making the selection
     * rules explicit.
     */
    public static List<AuxBlockedReason> policyOrder() {
        return POLICY_ORDER;
    }

    private static final List<AuxBlockedReason> POLICY_ORDER = Arrays.stream(values())
            .sorted(Comparator.comparingInt(AuxBlockedReason::priority).reversed())
            .toList();

    /**
     * Pick the higher-priority reason (null-safe).
     */
    public static AuxBlockedReason bestOf(AuxBlockedReason a, AuxBlockedReason b) {
        if (a == null) return b == null ? UNKNOWN : b;
        if (b == null) return a;
        return a.priority >= b.priority ? a : b;
    }

    /**
     * Pick the best reason from a list (null/empty-safe).
     */
    public static AuxBlockedReason bestOf(Iterable<AuxBlockedReason> reasons) {
        if (reasons == null) return UNKNOWN;
        AuxBlockedReason best = UNKNOWN;
        for (AuxBlockedReason r : reasons) {
            best = bestOf(best, r);
        }
        return best;
    }

    /**
     * Derive a reason from the GuardContext using the policy table.
     */
    public static AuxBlockedReason fromContext(GuardContext ctx) {
        if (ctx == null) {
            return UNKNOWN;
        }

        AuxBlockedReason best = UNKNOWN;

        // Context signals (may co-occur; choose by priority())
        if (ctx.isAuxHardDown()) {
            best = bestOf(best, AUX_HARD_DOWN);
        }
        if (ctx.isAuxDegraded()) {
            best = bestOf(best, AUX_DEGRADED);
        }
        if (ctx.isBypassMode()) {
            best = bestOf(best, BYPASS);
        }
        if (ctx.isStrikeMode()) {
            best = bestOf(best, STRIKE);
        }
        if (ctx.isCompressionMode()) {
            best = bestOf(best, COMPRESSION);
        }

        return best;
    }
}
