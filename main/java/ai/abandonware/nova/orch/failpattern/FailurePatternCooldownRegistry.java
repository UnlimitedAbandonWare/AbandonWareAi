package ai.abandonware.nova.orch.failpattern;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory cooldown registry (fail-soft).
 *
 * <p>
 * We store "cooldown until" timestamps per canonical source id.
 */
public final class FailurePatternCooldownRegistry {

    private final ConcurrentHashMap<String, AtomicLong> untilMsBySource = new ConcurrentHashMap<>();

    public void recordAt(String source, long eventTsEpochMs, long cooldownMs) {
        if (source == null || source.isBlank() || cooldownMs <= 0) {
            return;
        }
        long until = eventTsEpochMs + cooldownMs;
        untilMsBySource.computeIfAbsent(source, k -> new AtomicLong(0))
                .updateAndGet(prev -> Math.max(prev, until));
    }

    public boolean isCoolingDown(String source) {
        return remainingMs(source) > 0;
    }

    public long remainingMs(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        AtomicLong until = untilMsBySource.get(source);
        if (until == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        return Math.max(0, until.get() - now);
    }

    public Map<String, Long> snapshotRemainingMs() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, AtomicLong> e : untilMsBySource.entrySet()) {
            long rem = Math.max(0, e.getValue().get() - now);
            out.put(e.getKey(), rem);
        }
        return out;
    }

    /**
     * Clears all cooldown entries.
     * <p>
     * Called by the orchestrator when reloading cooldown state from JSONL.
     */
    public void clear() {
        untilMsBySource.clear();
    }
}
