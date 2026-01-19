package com.example.lms.infra.resilience;

import java.util.Locale;

/**
 * Controls when to attach the "allOpenBreakersSnapshot" field to an aux.blocked event.
 *
 * <p>Defaults to MULTI_OPEN_ONLY (previous behaviour), but can be overridden via
 * {@code System.getProperty("aux.blocked.allOpenSnapshot.policy")}
 * (or the legacy key {@code aux.blocked.allOpenBreakersSnapshot.policy}).
 */
public enum AllOpenBreakersSnapshotPolicy {
    /** Never attach the structured snapshot map. */
    NEVER,

    /** Attach only when this event was blocked because a breaker is OPEN. */
    BREAKER_OPEN_ONLY,

    /** Attach only when 2+ breakers are concurrently OPEN (default). */
    MULTI_OPEN_ONLY,

    /** Attach whenever at least 1 breaker is OPEN. */
    ALWAYS;

    public static AllOpenBreakersSnapshotPolicy from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MULTI_OPEN_ONLY;
        }

        String v = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');

        return switch (v) {
            case "NEVER", "NONE", "OFF", "DISABLED" -> NEVER;
            case "BREAKER_OPEN_ONLY", "BREAKER_OPEN", "ON_BREAKER_OPEN" -> BREAKER_OPEN_ONLY;
            case "ALWAYS", "ON", "ENABLED" -> ALWAYS;
            case "MULTI_OPEN_ONLY", "MULTI_OPEN", "MULTI", "MULTIPLE" -> MULTI_OPEN_ONLY;
            default -> MULTI_OPEN_ONLY;
        };
    }

    public boolean includeSnapshot(boolean breakerOpen, int openBreakerCount) {
        return switch (this) {
            case NEVER -> false;
            case ALWAYS -> openBreakerCount > 0;
            case BREAKER_OPEN_ONLY -> breakerOpen && openBreakerCount > 0;
            case MULTI_OPEN_ONLY -> openBreakerCount > 1;
        };
    }
}
