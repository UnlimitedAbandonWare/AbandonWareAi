package com.example.lms.service.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory denylist for vector ids (TTL-based, reversible).
 *
 * <p>Used to quickly suppress recurring "hub" vectors during incidents without destructive deletes.</p>
 */
@Component
public class VectorDenylistRegistry {
    private static final Logger log = LoggerFactory.getLogger(VectorDenylistRegistry.class);

    @Value("${vector.denylist.ttl-minutes:60}")
    private long ttlMinutes;

    @Value("${vector.denylist.max-size:2000}")
    private int maxSize;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public void ban(String embeddingId, String reason) {
        if (embeddingId == null || embeddingId.isBlank()) return;
        pruneIfNeeded();
        long expireAtMs = System.currentTimeMillis() + Duration.ofMinutes(Math.max(1, ttlMinutes)).toMillis();
        entries.put(embeddingId, new Entry(expireAtMs, safe(reason)));
        log.debug("[VectorDenylist] ban id={} ttlMin={} reason={}", embeddingId, ttlMinutes, safe(reason));
    }

    public void unban(String embeddingId) {
        if (embeddingId == null || embeddingId.isBlank()) return;
        entries.remove(embeddingId);
    }

    public boolean isBanned(String embeddingId) {
        if (embeddingId == null || embeddingId.isBlank()) return false;
        Entry e = entries.get(embeddingId);
        if (e == null) return false;
        if (e.expireAtMs < System.currentTimeMillis()) {
            entries.remove(embeddingId);
            return false;
        }
        return true;
    }

    public int size() {
        return entries.size();
    }

    public Map<String, String> snapshotReasons() {
        ConcurrentHashMap<String, String> out = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue() != null && e.getValue().expireAtMs >= now) {
                out.put(e.getKey(), e.getValue().reason);
            }
        }
        return out;
    }

    private void pruneIfNeeded() {
        if (entries.size() <= Math.max(100, maxSize)) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            if (v == null || v.expireAtMs < now) {
                entries.remove(e.getKey());
            }
        }
        // If still too big, remove arbitrary entries.
        while (entries.size() > Math.max(100, maxSize)) {
            String k = entries.keys().hasMoreElements() ? entries.keys().nextElement() : null;
            if (k == null) break;
            entries.remove(k);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record Entry(long expireAtMs, String reason) {
        Entry {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
