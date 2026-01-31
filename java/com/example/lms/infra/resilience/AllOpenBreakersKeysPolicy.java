package com.example.lms.infra.resilience;

import java.util.Locale;

/**
 * Policy for attaching lightweight "all open breakers" fields on AuxBlock events.
 *
 * <p>These fields are intended to be cheap (count + keys) and analysis-friendly.
 * The full snapshot (key -> dto) is controlled separately via {@link AllOpenBreakersSnapshotPolicy}.
 *
 * <p><strong>Note</strong>: AuxBlockTracker currently treats keys/count as <em>always-on</em>
 * (to prevent operational misconfiguration). This enum + its system properties remain
 * for backward compatibility and to keep config parsing behavior centralized, but may
 * be ignored by the event producer.
 */
public enum AllOpenBreakersKeysPolicy {
    /** Always include count + keys (keys may be empty). */
    ALWAYS,
    /** Include count + keys only when at least 1 breaker is currently OPEN. */
    OPEN_ONLY,
    /** Include count + keys only when 2+ breakers are currently OPEN. */
    MULTI_OPEN_ONLY,
    /** Never include count + keys. */
    NEVER;

    /** Preferred system property. */
    public static final String PROP_ALL_OPEN_BREAKERS_KEYS_POLICY = "aux.blocked.allOpenKeys.policy";

    /** Legacy alias system property. */
    public static final String PROP_ALL_OPEN_BREAKERS_KEYS_POLICY_LEGACY = "aux.blocked.allOpenBreakersKeys.policy";

    public boolean includeKeys(int openBreakerCount) {
        return switch (this) {
            case ALWAYS -> true;
            case OPEN_ONLY -> openBreakerCount > 0;
            case MULTI_OPEN_ONLY -> openBreakerCount > 1;
            case NEVER -> false;
        };
    }

    public static AllOpenBreakersKeysPolicy from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALWAYS;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        // allow a couple of common short-hands
        if ("MULTI_ONLY".equals(norm)) {
            return MULTI_OPEN_ONLY;
        }
        if ("OPEN".equals(norm) || "OPENED".equals(norm)) {
            return OPEN_ONLY;
        }
        try {
            return AllOpenBreakersKeysPolicy.valueOf(norm);
        } catch (IllegalArgumentException ignore) {
            return ALWAYS;
        }
    }
}
