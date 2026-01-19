package com.example.lms.infra.resilience;

/**
 * UAW: standardized event type codes for aux-block telemetry.
 *
 * <p>
 * These codes are persisted into TraceStore (as strings) so downstream tooling
 * (TraceHtmlBuilder / console loggers / external aggregators) can rely on stable
 * identifiers.
 * </p>
 */
public enum AuxBlockEventType {

    /** A stage decided to block an auxiliary LLM call-path (bypass/fallback). */
    STAGE_BLOCKED("stage_blocked"),

    /** A stage decided to override noise gating for the current request. */
    STAGE_NOISE_OVERRIDE("stage_noise_override");

    private final String code;

    AuxBlockEventType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
