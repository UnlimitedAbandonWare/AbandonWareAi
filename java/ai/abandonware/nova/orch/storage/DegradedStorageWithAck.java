package ai.abandonware.nova.orch.storage;

// MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_ACK_V1

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Optional extension interface for {@link DegradedStorage} implementations that support
 * claim/ack semantics.
 *
 * <p>Why:</p>
 * <ul>
 *   <li>Enables partial-batch acks (successfully drained items can be removed while failures remain).</li>
 *   <li>Provides basic monitoring/retention controls without forcing every {@link DegradedStorage}
 *       to implement them.</li>
 * </ul>
 */
public interface DegradedStorageWithAck extends DegradedStorage {

    /**
     * Claim up to {@code max} pending items for processing.
     *
     * <p>Claimed items should be excluded from subsequent {@link #claim(int)} calls until they
     * are {@link #ack(String) acked} or {@link #release(String) released}/{@link #nack(String, String) nacked}.</p>
     */
    List<ClaimedPending> claim(int max);

    /**
     * Permanently remove a previously claimed item.
     */
    void ack(String token);

    /**
     * Return a claimed item back to the pending queue without counting as a failure.
     */
    void release(String token);

    /**
     * Return a claimed item back to the pending queue and record the failure reason.
     */
    void nack(String token, String error);

    /**
     * Current outbox stats.
     */
    OutboxStats stats();

    /**
     * Enforce retention constraints (TTL / max files / max bytes) and attempt to recover stale
     * in-flight claims.
     */
    OutboxSweepResult sweep();

    /**
     * Diagnostics helper to sample (peek) recent outbox entries without claiming them.
     *
     * <p>Implementations may return an empty list if peek is not supported.</p>
     *
     * @param state One of: pending, inflight, quarantine, bad, all
     * @param limit Maximum number of items to return (implementations may clamp)
     * @param maxSnippetChars Max characters of {@link PendingMemoryEvent#answerSnippet()} to include
     */
    default List<OutboxPeekItem> peek(String state, int limit, int maxSnippetChars) {
        return List.of();
    }

    record ClaimedPending(
            String token,
            PendingMemoryEvent event,
            int attemptCount,
            Instant createdAt,
            Instant lastAttemptAt,
            long sizeBytes,
            Map<String, Object> meta
    ) {}

    record OutboxPeekItem(
            String token,
            String state,
            int attemptCount,
            Instant createdAt,
            Instant lastAttemptAt,
            long sizeBytes,
            String lastError,
            PendingMemoryEvent event,
            Map<String, Object> meta
    ) {}

    record OutboxStats(
            boolean enabled,
            String mode,
            String path,
            int pendingCount,
            int inflightCount,
            long totalBytes,
            Instant oldestCreatedAt,
            Instant newestCreatedAt,
            int maxFiles,
            long maxBytes,
            long ttlSeconds,
            long inflightStaleSeconds,
            long ackTotal,
            long nackTotal,
            long releaseTotal,
            long droppedExpiredTotal,
            long droppedByLimitTotal,
            long parseErrorTotal,
            long lastSweepEpochMs
    ) {}

    record OutboxSweepResult(
            long sweptAtEpochMs,
            int removedExpired,
            int removedByMaxFiles,
            int removedByMaxBytes,
            int recoveredInflight,
            long bytesBefore,
            long bytesAfter
    ) {}
}
