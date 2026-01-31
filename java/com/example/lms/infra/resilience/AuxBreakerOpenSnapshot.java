package com.example.lms.infra.resilience;

/**
 * Snapshot of a single breaker OPEN window, for debugging / trace analysis.
 *
 * <p>Intended to be embedded into aux.blocked events (TraceStore) in a compact, structured form.
 */
public record AuxBreakerOpenSnapshot(
        String breakerKey,
        long openSinceMs,
        long openUntilMs,
        long remainingOpenMs,
        long openWindowMs,
        String lastKind,
        String lastErrorMessage
) {
    public static AuxBreakerOpenSnapshot of(String breakerKey, long openSinceMs, long openUntilMs, long nowMs, String lastKind, String lastErrorMessage) {
        long remaining = openUntilMs > 0L ? Math.max(0L, openUntilMs - nowMs) : 0L;
        long openWindow = (openSinceMs > 0L && openUntilMs > 0L) ? Math.max(0L, openUntilMs - openSinceMs) : 0L;
        return new AuxBreakerOpenSnapshot(breakerKey, openSinceMs, openUntilMs, remaining, openWindow, lastKind, lastErrorMessage);
    }
}
