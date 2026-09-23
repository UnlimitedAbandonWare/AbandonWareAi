package ai.abandonware.nova.orch.storage;

import java.util.List;

/**
 * A fail-soft, out-of-band storage for "pending" memory candidates.
 *
 * <p>This is used when long-term memory writes are skipped (e.g., HYBRID/FREE)
 * so that the data isn't silently lost and can be promoted later.</p>
 */
public interface DegradedStorage {

    void putPending(PendingMemoryEvent event);

    /**
     * Drain up to {@code max} items. Implementations may filter by TTL.
     *
     * <p>Current patch uses this as a future extension point; it is safe to
     * return an empty list if draining isn't implemented.</p>
     */
    List<PendingMemoryEvent> drain(int max);
}
