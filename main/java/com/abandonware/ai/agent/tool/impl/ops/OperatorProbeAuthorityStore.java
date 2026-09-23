package com.abandonware.ai.agent.tool.impl.ops;

import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded handoff store for operator-defined probe authority. Only opaque
 * goal/constraint refs and low-cardinality policy metadata are retained.
 */
public final class OperatorProbeAuthorityStore {

    private static final int DEFAULT_MAX_ENTRIES = 128;
    private static final long DEFAULT_TTL_MILLIS = 5 * 60_000L;
    private static final int MAX_BUDGET_MILLIS = 60_000;

    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    public OperatorProbeAuthorityStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    OperatorProbeAuthorityStore(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public synchronized String issue(String sessionId,
                                     String goalHash,
                                     String constraintHash,
                                     String probeRequestHash,
                                     Mode mode,
                                     int maxMillis) {
        if (!opaqueRef(goalHash) || !opaqueRef(constraintHash) || !opaqueRef(probeRequestHash)) {
            throw new IllegalArgumentException("operator authority requires opaque goal, constraint, and request refs");
        }
        if (mode == null || maxMillis < 1 || maxMillis > MAX_BUDGET_MILLIS) {
            throw new IllegalArgumentException("operator authority requires bounded mode and budget");
        }
        long now = clock.getAsLong();
        purgeExpired(now);
        while (entries.size() >= maxEntries) {
            entries.remove(entries.keySet().iterator().next());
        }

        String sessionHash = SafeRedactor.hashValue(normalizeSession(sessionId));
        String authorityRef = SafeRedactor.hashValue(sessionHash + "|" + goalHash + "|"
                + constraintHash + "|" + probeRequestHash + "|" + mode + "|"
                + now + "|" + sequence.incrementAndGet());
        entries.entrySet().removeIf(entry -> entry.getValue().sessionHash().equals(sessionHash));
        Authority authority = new Authority(
                authorityRef, goalHash, constraintHash, probeRequestHash, mode, maxMillis,
                now + ttlMillis);
        entries.put(authorityRef, new Entry(sessionHash, authority));
        return authorityRef;
    }

    public synchronized Optional<Authority> peek(String authorityRef, String sessionId) {
        long now = clock.getAsLong();
        purgeExpired(now);
        Entry entry = entries.get(authorityRef);
        if (entry == null) {
            return Optional.empty();
        }
        String sessionHash = SafeRedactor.hashValue(normalizeSession(sessionId));
        return entry.sessionHash().equals(sessionHash)
                ? Optional.of(entry.authority())
                : Optional.empty();
    }

    public synchronized Optional<Authority> consume(String authorityRef, String sessionId) {
        Optional<Authority> authority = peek(authorityRef, sessionId);
        if (authority.isEmpty()) {
            return Optional.empty();
        }
        entries.remove(authorityRef);
        return authority;
    }

    public synchronized Optional<Authority> consumeIfMatches(String authorityRef,
                                                             String sessionId,
                                                             String goalHash,
                                                             String constraintHash,
                                                             String probeRequestHash,
                                                             Mode requiredMode) {
        Optional<Authority> authority = peek(authorityRef, sessionId);
        if (authority.isEmpty()
                || !authority.get().goalHash().equals(goalHash)
                || !authority.get().constraintHash().equals(constraintHash)
                || !authority.get().probeRequestHash().equals(probeRequestHash)
                || authority.get().mode() != requiredMode) {
            return Optional.empty();
        }
        entries.remove(authorityRef);
        return authority;
    }

    public synchronized void revokeSession(String sessionId) {
        String sessionHash = SafeRedactor.hashValue(normalizeSession(sessionId));
        entries.entrySet().removeIf(entry -> entry.getValue().sessionHash().equals(sessionHash));
    }

    private void purgeExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().authority().expiresAtMillis() <= now);
    }

    private static String normalizeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("operator authority requires explicit session");
        }
        return sessionId.trim();
    }

    private static boolean opaqueRef(String value) {
        return value != null && value.matches("hash:[0-9a-f]{12}");
    }

    private record Entry(String sessionHash, Authority authority) {
    }

    public enum Mode {
        OBSERVE_ONLY,
        PROBE_AND_PATCH_CANDIDATE
    }

    public record Authority(String authorityRef,
                            String goalHash,
                            String constraintHash,
                            String probeRequestHash,
                            Mode mode,
                            int maxMillis,
                            long expiresAtMillis) {
    }
}
