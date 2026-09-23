package com.abandonware.ai.agent.tool.impl.ops;

import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded request-handoff store for opaque counter-evidence bindings. Raw
 * claims, queries, snippets, and session identifiers are never retained.
 */
public final class CounterEvidencePacketStore {

    private static final int DEFAULT_MAX_ENTRIES = 128;
    private static final long DEFAULT_TTL_MILLIS = 5 * 60_000L;

    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    public CounterEvidencePacketStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    CounterEvidencePacketStore(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public synchronized String issue(String sessionId,
                                     String claimHash,
                                     String decisionQuestionHash,
                                     List<String> queryTraceRefs,
                                     List<Map<String, Object>> evidenceRows) {
        if (!opaqueRef(claimHash) || !opaqueRef(decisionQuestionHash)) {
            throw new IllegalArgumentException("counter packet requires opaque claim and question refs");
        }
        List<String> traces = queryTraceRefs == null ? List.of() : List.copyOf(queryTraceRefs);
        if (traces.size() != 3 || traces.stream().anyMatch(value -> !opaqueRef(value))) {
            throw new IllegalArgumentException("counter packet requires three opaque query trace refs");
        }
        long now = clock.getAsLong();
        purgeExpired(now);
        while (entries.size() >= maxEntries) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }

        String sessionHash = SafeRedactor.hashValue(normalizeSession(sessionId));
        String packetRef = SafeRedactor.hashValue(sessionHash + "|" + claimHash + "|"
                + decisionQuestionHash + "|" + now + "|" + sequence.incrementAndGet());
        List<EvidenceBinding> bindings = normalizeBindings(evidenceRows);
        Packet packet = new Packet(packetRef, claimHash, decisionQuestionHash, traces, bindings,
                now + ttlMillis);
        entries.put(packetRef, new Entry(sessionHash, packet));
        return packetRef;
    }

    public synchronized Optional<Packet> consume(String packetRef, String sessionId) {
        Optional<Packet> packet = peek(packetRef, sessionId);
        if (packet.isEmpty()) {
            return Optional.empty();
        }
        entries.remove(packetRef);
        return packet;
    }

    public synchronized Optional<Packet> peek(String packetRef, String sessionId) {
        long now = clock.getAsLong();
        purgeExpired(now);
        Entry entry = entries.get(packetRef);
        if (entry == null) {
            return Optional.empty();
        }
        String sessionHash = SafeRedactor.hashValue(normalizeSession(sessionId));
        if (!entry.sessionHash().equals(sessionHash)) {
            return Optional.empty();
        }
        return Optional.of(entry.packet());
    }

    private void purgeExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().packet().expiresAtMillis() < now);
    }

    private static List<EvidenceBinding> normalizeBindings(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<EvidenceBinding> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            String evidenceId = opaque(row.get("evidenceId"));
            String sourceProvenanceHash = opaque(row.get("independenceGroupHash"));
            String queryTraceRef = opaque(row.get("queryTraceRef"));
            String querySlot = label(row.get("querySlot"));
            String observedAt = label(row.get("observedAt"));
            double retrievalScore = score(row.get("retrievalScore"));
            String relationHint = label(row.get("relation"));
            if (relationHint == null) {
                relationHint = "NEUTRAL";
            }
            if (!relationHint.equals("NEUTRAL") && !relationHint.equals("CONFLICTS")) {
                throw new IllegalArgumentException("invalid local relation hint");
            }
            double crossValidationScore = score(row.get("crossValidationScore"));
            if (evidenceId == null || sourceProvenanceHash == null || queryTraceRef == null
                    || querySlot == null || observedAt == null) {
                throw new IllegalArgumentException("invalid opaque evidence binding");
            }
            out.add(new EvidenceBinding(evidenceId, sourceProvenanceHash, queryTraceRef,
                    querySlot, observedAt, retrievalScore, relationHint, crossValidationScore));
        }
        return List.copyOf(out);
    }

    private static String normalizeSession(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "internal-agent" : sessionId.trim();
    }

    private static String opaque(Object raw) {
        String value = raw == null ? null : String.valueOf(raw).trim();
        return opaqueRef(value) ? value : null;
    }

    private static boolean opaqueRef(String value) {
        return value != null && value.matches("hash:[0-9a-f]{12}");
    }

    private static String label(Object raw) {
        String value = raw == null ? null : String.valueOf(raw).trim();
        if (value == null || value.isBlank() || value.length() > 80) {
            return null;
        }
        return SafeRedactor.traceLabel(value);
    }

    private static double score(Object raw) {
        if (!(raw instanceof Number number)) {
            return 0.0d;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) ? Math.max(0.0d, Math.min(1.0d, value)) : 0.0d;
    }

    private record Entry(String sessionHash, Packet packet) {
    }

    public record EvidenceBinding(String evidenceId,
                                  String sourceProvenanceHash,
                                  String queryTraceRef,
                                  String querySlot,
                                  String observedAt,
                                  double retrievalScore,
                                  String relationHint,
                                  double crossValidationScore) {
    }

    public record Packet(String packetRef,
                         String claimHash,
                         String decisionQuestionHash,
                         List<String> queryTraceRefs,
                         List<EvidenceBinding> evidenceBindings,
                         long expiresAtMillis) {
        public Packet {
            queryTraceRefs = List.copyOf(queryTraceRefs);
            evidenceBindings = List.copyOf(evidenceBindings);
        }
    }
}
