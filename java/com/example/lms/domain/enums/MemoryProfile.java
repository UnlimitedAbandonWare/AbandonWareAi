package com.example.lms.domain.enums;

/**
 * MemoryProfile controls how aggressively long-term memory should be written.
 * <p>
 * This is intentionally orthogonal to existing {@link MemoryGateProfile} which
 * focuses on gate behaviour at inference time.  MemoryProfile is used at the
 * session / orchestrator level.
 */
public enum MemoryProfile {

    /**
     * Long‑running projects or knowledge building.
     * Favour writing high quality memories.
     */
    STRICT,

    /**
     * Lightweight Q&A.  Some memories may be written, but the bar is higher
     * and implementations are free to ignore writes in borderline cases.
     */
    LIGHT,

    /**
     * Do not write long‑term memory at all for this session / request.
     * Short‑term reasoning and scratch space can still be used.
     */
    OFF;

    /**
     * Convenience helper used by call‑sites that only need a boolean.
     */
    public boolean allowWrite() {
        return this != OFF;
    }
}
