package ai.abandonware.nova.orch.failpattern;

/**
 * JSONL event for matched failure patterns.
 *
 * <p>Schema is append-only: new fields may be added over time.
 * Downstream consumers that ignore unknown properties remain compatible.
 */
public record FailurePatternEvent(
        long tsEpochMillis,
        FailurePatternKind kind,
        String source,
        String key,
        long cooldownMs,
        String cooldownPolicy,
        String logger,
        String level,
        String message
) {
}
