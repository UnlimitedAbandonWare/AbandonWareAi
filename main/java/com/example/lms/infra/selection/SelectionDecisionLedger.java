package com.example.lms.infra.selection;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class SelectionDecisionLedger {

    public enum Lane {
        ROUTER,
        STRATEGY,
        ENSEMBLE,
        RANKING
    }

    public static final int MAX_DECISIONS = 10_000;

    private static final byte[] CANDIDATE_KEY_LABEL =
            "awx-selection-candidate-key-v1".getBytes(StandardCharsets.US_ASCII);

    private final int capacity;
    private final ConcurrentHashMap<String, DecisionRow> rows = new ConcurrentHashMap<>();
    private final Set<String> driftedCoordinates = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SelectionEntropyCoherence> coherence;
    private final AtomicReference<SelectionEntropyReason> reason =
            new AtomicReference<>(SelectionEntropyReason.NONE);
    private final AtomicInteger drawCount = new AtomicInteger();
    private final AtomicInteger stableTieBreakCount = new AtomicInteger();
    private final AtomicInteger candidateDriftCount = new AtomicInteger();
    private final AtomicInteger routerDrawCount = new AtomicInteger();
    private final AtomicInteger strategyDrawCount = new AtomicInteger();
    private final AtomicInteger ensembleDrawCount = new AtomicInteger();

    private SelectionDecisionLedger(SelectionEntropyCoherence initial, int capacity) {
        this.coherence = new AtomicReference<>(initial);
        this.capacity = capacity;
    }

    public static SelectionDecisionLedger forStandard() {
        return new SelectionDecisionLedger(
                SelectionEntropyCoherence.NOT_REQUESTED, MAX_DECISIONS);
    }

    public static SelectionDecisionLedger forReplay() {
        return new SelectionDecisionLedger(
                SelectionEntropyCoherence.ACCEPTED, MAX_DECISIONS);
    }

    static SelectionDecisionLedger forReplay(int capacity) {
        if (capacity < 1 || capacity > MAX_DECISIONS) {
            throw new IllegalArgumentException("selection_entropy_ledger_capacity_invalid");
        }
        return new SelectionDecisionLedger(SelectionEntropyCoherence.ACCEPTED, capacity);
    }

    public void record(
            Lane lane,
            SelectionCoordinate coordinate,
            List<String> stableCandidateKeys,
            int selectedIndex,
            String fallbackReason,
            boolean drawConsumed,
            boolean stableTieBreak) {
        recordValidatedDecision(
                lane,
                coordinate,
                stableCandidateKeys,
                selectedIndex,
                fallbackReason == null ? "" : fallbackReason,
                drawConsumed,
                stableTieBreak);
    }

    public synchronized void markFailure(SelectionEntropyReason failureReason) {
        if (failureReason == null || failureReason == SelectionEntropyReason.NONE) {
            throw new IllegalArgumentException("selection_entropy_failure_reason_invalid");
        }
        if (coherence.get() == SelectionEntropyCoherence.FAILED) {
            return;
        }
        reason.set(failureReason);
        coherence.set(SelectionEntropyCoherence.FAILED);
    }

    public synchronized Snapshot snapshot(boolean terminal) {
        SelectionEntropyCoherence visible = coherence.get();
        if (terminal && visible == SelectionEntropyCoherence.ACCEPTED) {
            visible = SelectionEntropyCoherence.MATCHED;
        }
        return snapshotOf(visible, reason.get(), canonicalDecisionDigest());
    }

    private synchronized void recordValidatedDecision(
            Lane lane,
            SelectionCoordinate coordinate,
            List<String> keys,
            int selectedIndex,
            String fallbackReason,
            boolean drawConsumed,
            boolean stableTieBreak) {
        if (lane == null || coordinate == null || keys == null || keys.isEmpty()
                || selectedIndex < 0 || selectedIndex >= keys.size()
                || keys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new SelectionEntropyException(SelectionEntropyReason.STABLE_KEY_MISSING);
        }
        String safeFallback = fallbackReason == null ? "" : fallbackReason;
        if (!safeFallback.matches("[a-z0-9_]{0,64}")) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }

        String coordinateHash = hex(sha256(coordinate.encoded()));
        String candidateSetHash = candidateSetHash(keys);
        DecisionRow proposed = new DecisionRow(
                coordinateHash,
                candidateSetHash,
                selectedIndex,
                safeFallback,
                lane,
                drawConsumed,
                stableTieBreak);
        DecisionRow existing = rows.get(coordinateHash);
        if (existing != null) {
            if (!existing.candidateSetHash().equals(candidateSetHash)
                    && driftedCoordinates.add(coordinateHash)) {
                candidateDriftCount.incrementAndGet();
                markPartial(SelectionEntropyReason.CANDIDATE_DRIFT);
            }
            return;
        }
        if (rows.size() >= capacity) {
            markPartial(SelectionEntropyReason.DECISION_CAP_REACHED);
            return;
        }

        rows.put(coordinateHash, proposed);
        if (drawConsumed) {
            drawCount.incrementAndGet();
            switch (lane) {
                case ROUTER -> routerDrawCount.incrementAndGet();
                case STRATEGY -> strategyDrawCount.incrementAndGet();
                case ENSEMBLE -> ensembleDrawCount.incrementAndGet();
                case RANKING -> {
                }
            }
        }
        if (stableTieBreak) {
            stableTieBreakCount.incrementAndGet();
        }
    }

    private synchronized void markPartial(SelectionEntropyReason partialReason) {
        if (coherence.get() != SelectionEntropyCoherence.FAILED) {
            reason.compareAndSet(SelectionEntropyReason.NONE, partialReason);
            coherence.set(SelectionEntropyCoherence.PARTIAL);
        }
    }

    private static String candidateSetHash(List<String> keys) {
        ByteBuffer bytes;
        try {
            bytes = ByteBuffer.allocate(4 + Math.multiplyExact(keys.size(), 32));
        } catch (ArithmeticException failure) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        bytes.putInt(keys.size());
        for (String key : keys) {
            MessageDigest digest = sha256Digest();
            digest.update(CANDIDATE_KEY_LABEL);
            digest.update((byte) 0);
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            bytes.put(digest.digest());
        }
        return hex(sha256(bytes.array()));
    }

    private String canonicalDecisionDigest() {
        String text = rows.values().stream()
                .sorted(Comparator.comparing(DecisionRow::coordinateHash)
                        .thenComparing(DecisionRow::candidateSetHash)
                        .thenComparingInt(DecisionRow::selectedIndex)
                        .thenComparing(DecisionRow::fallbackReason))
                .map(row -> row.coordinateHash() + "|" + row.candidateSetHash() + "|"
                        + row.selectedIndex() + "|" + row.fallbackReason() + "\n")
                .collect(Collectors.joining());
        return hex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private Snapshot snapshotOf(
            SelectionEntropyCoherence visible,
            SelectionEntropyReason visibleReason,
            String digest) {
        return new Snapshot(
                visible,
                visibleReason,
                digest,
                rows.size(),
                drawCount.get(),
                stableTieBreakCount.get(),
                candidateDriftCount.get(),
                routerDrawCount.get(),
                strategyDrawCount.get(),
                ensembleDrawCount.get());
    }

    private static byte[] sha256(byte[] input) {
        return sha256Digest().digest(input);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
    }

    private static String hex(byte[] input) {
        return HexFormat.of().formatHex(input);
    }

    private record DecisionRow(
            String coordinateHash,
            String candidateSetHash,
            int selectedIndex,
            String fallbackReason,
            Lane lane,
            boolean drawConsumed,
            boolean stableTieBreak) {
    }

    public record Snapshot(
            SelectionEntropyCoherence coherence,
            SelectionEntropyReason reason,
            String decisionDigest,
            int decisionCount,
            int drawCount,
            int stableTieBreakCount,
            int candidateDriftCount,
            int routerDrawCount,
            int strategyDrawCount,
            int ensembleDrawCount) {
    }
}
