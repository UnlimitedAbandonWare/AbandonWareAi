package com.example.lms.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Process-local exact rolling admission; bounded eviction and restart discard history.
 * The shared monitor makes owner/IP inspection and recording a single transaction.
 * Owner history retains the bounded minute throughput over one hour, even with hourly disabled.
 * This candidate changes the requested default owner capacity from 10 to 600 timestamps.
 */
final class RollingTurnWindow {
    static final long MINUTE_MS = 60_000, HOUR_MS = 3_600_000;
    record Rule(String key, int limit, long windowMs, int retained, String scope) {}
    private record Entry(long at) {}
    private record Stamp(String key, Entry entry) {}
    private record Replay(long at, Result receipt, String fingerprint) {}
    static final class Result {
        private final List<Stamp> stamps;
        private final String replayKey;
        private final boolean accepted;
        private final long retryAfterMs;
        private final String scope;
        private final String rejectionReason;
        private boolean released;
        private Result(boolean accepted, long retryAfterMs, String scope, List<Stamp> stamps, String replayKey) {
            this(accepted, retryAfterMs, scope, stamps, replayKey, null);
        }
        private Result(boolean accepted, long retryAfterMs, String scope, List<Stamp> stamps, String replayKey,
                       String rejectionReason) {
            this.accepted = accepted; this.retryAfterMs = retryAfterMs; this.scope = scope;
            this.stamps = stamps; this.replayKey = replayKey;
            this.rejectionReason = rejectionReason;
        }
        boolean accepted() { return accepted; }
        long retryAfterMs() { return retryAfterMs; }
        String scope() { return scope; }
        String rejectionReason() { return rejectionReason; }
    }
    private static final class Ring {
        final Entry[] entries;
        int head, size;
        Ring(int capacity) { entries = new Entry[capacity]; }
        Entry newest(int rank) { return entries[(head + size - rank) % entries.length]; }
        void add(Entry entry) {
            if (size == entries.length) { entries[head] = null; head = (head + 1) % entries.length; size--; }
            entries[(head + size++) % entries.length] = entry;
        }
        void remove(Entry entry) {
            for (int i = 0; i < size; i++) {
                int slot = (head + i) % entries.length;
                if (entries[slot] != entry) continue;
                for (int j = i; j < size - 1; j++)
                    entries[(head + j) % entries.length] = entries[(head + j + 1) % entries.length];
                entries[(head + --size) % entries.length] = null;
                return;
            }
        }
    }
    private final int maxKeys;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Ring> rings = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, Replay> replays = new LinkedHashMap<>();
    private long lastNow = Long.MIN_VALUE, evictedKeys, evictedReplayKeys;
    RollingTurnWindow(int maxKeys, LongSupplier clock) {
        if (maxKeys < 1 || maxKeys > 100_000) throw new IllegalArgumentException("invalid_max_keys");
        this.maxKeys = maxKeys; this.clock = java.util.Objects.requireNonNull(clock);
    }
    synchronized Result tryAcquire(String replayKey, List<Rule> rules) {
        return tryAcquire(replayKey, null, rules);
    }
    synchronized Result tryAcquire(String replayKey, String fingerprint, List<Rule> rules) {
        long now = Math.max(lastNow, clock.getAsLong()); lastNow = now;
        var expired = replays.entrySet().iterator();
        while (expired.hasNext()) {
            if (now - expired.next().getValue().at < HOUR_MS) break;
            expired.remove();
        }
        if (replayKey != null && replays.containsKey(replayKey)) {
            // The legacy quota-only overload keeps its zero-charge receipt contract.
            // Generation admission always supplies the bounded semantic fingerprint.
            if (fingerprint == null) return new Result(true, 0, null, List.of(), null);
            Replay prior = replays.get(replayKey);
            String reason = fingerprint.equals(prior.fingerprint)
                    ? "idempotency_duplicate" : "idempotency_payload_mismatch";
            return new Result(false, 0, null, List.of(), null, reason);
        }
        long retry = 0; String scope = null;
        for (Rule rule : rules) {
            if (rule.limit < 1 || rule.retained < rule.limit || rule.windowMs < 1)
                throw new IllegalArgumentException("invalid_window_rule");
            Ring ring = rings.get(rule.key);
            if (ring == null || ring.size < rule.limit) continue;
            long oldest = ring.newest(rule.limit).at;
            long remaining = rule.windowMs - (now - oldest);
            if (remaining > retry) { retry = remaining; scope = rule.scope; }
        }
        if (retry > 0) return new Result(false, retry, scope, List.of(), null);
        var stamps = new ArrayList<Stamp>();
        for (Rule rule : rules) {
            if (stamps.stream().anyMatch(stamp -> stamp.key.equals(rule.key))) continue;
            Ring ring = rings.get(rule.key);
            if (ring == null) {
                if (rings.size() >= maxKeys) { rings.remove(rings.keySet().iterator().next()); evictedKeys++; }
                ring = new Ring(rule.retained); rings.put(rule.key, ring);
            }
            Entry entry = new Entry(now); ring.add(entry); stamps.add(new Stamp(rule.key, entry));
        }
        Result result = new Result(true, 0, null, List.copyOf(stamps), replayKey);
        if (replayKey != null) {
            if (replays.size() >= maxKeys) { replays.remove(replays.keySet().iterator().next()); evictedReplayKeys++; }
            replays.put(replayKey, new Replay(now, result, fingerprint));
        }
        return result;
    }
    synchronized void release(Result receipt) {
        if (!receipt.accepted || receipt.released) return;
        receipt.released = true;
        for (Stamp stamp : receipt.stamps) {
            Ring ring = rings.get(stamp.key);
            if (ring != null) ring.remove(stamp.entry);
        }
        if (receipt.replayKey != null) {
            Replay replay = replays.get(receipt.replayKey);
            if (replay != null && replay.receipt == receipt) replays.remove(receipt.replayKey);
        }
    }
    synchronized long evictedKeys() { return evictedKeys; }
    synchronized long evictedReplayKeys() { return evictedReplayKeys; }
    synchronized int keyCount() { return rings.size(); }
    synchronized int replayKeyCount() { return replays.size(); }
}
