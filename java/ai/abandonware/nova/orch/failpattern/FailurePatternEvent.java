package ai.abandonware.nova.orch.failpattern;

/**
 * JSONL ledger event (one line per event).
 *
 * <p>Intentionally small schema:
 * this is meant for lightweight feedback + offline inspection.
 */
public record FailurePatternEvent(
        long tsEpochMillis,
        FailurePatternKind kind,
        String source,
        String key,
        String logger,
        String level,
        String message
) {
}
