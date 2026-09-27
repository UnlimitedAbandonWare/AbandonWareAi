package com.example.lms.api;

import com.example.lms.lifecycle.DurableLifecycleReceiptStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Process-local, hash-only replay guard for feedback mutations. */
@Component
public final class FeedbackMutationGuard {

    static final int MAX_ENTRIES = 4096;
    static final long TTL_MILLIS = 86_400_000L;

    public enum Decision {
        ACCEPT,
        REPLAY,
        CONFLICT,
        IN_PROGRESS,
        CAPACITY
    }

    private enum State {
        PENDING,
        COMMITTED
    }

    private record Entry(String fingerprintHash, long acceptedAtEpochMs, State state) {
    }

    private final Clock clock;
    private final DurableLifecycleReceiptStore receiptStore;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FeedbackMutationGuard() {
        this(Clock.systemUTC(), DurableLifecycleReceiptStore.none());
    }

    @Autowired
    public FeedbackMutationGuard(DurableLifecycleReceiptStore receiptStore) {
        this(Clock.systemUTC(), receiptStore);
    }

    FeedbackMutationGuard(Clock clock) {
        this(clock, DurableLifecycleReceiptStore.none());
    }

    FeedbackMutationGuard(Clock clock, DurableLifecycleReceiptStore receiptStore) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    }

    public synchronized Decision accept(
            String ownerIdentity,
            long sessionId,
            long ratedMessageId,
            String ratedContent,
            String normalizedRating,
            String correction) {
        Fingerprints fingerprints = fingerprints(
                ownerIdentity,
                sessionId,
                ratedMessageId,
                ratedContent,
                normalizedRating,
                correction);
        long now = clock.millis();
        evictExpired(now);

        Entry existing = entries.get(fingerprints.basisHash());
        if (existing != null) {
            if (!existing.fingerprintHash().equals(fingerprints.fullHash())) {
                return Decision.CONFLICT;
            }
            return existing.state() == State.COMMITTED
                    ? Decision.REPLAY
                    : Decision.IN_PROGRESS;
        }

        DurableLifecycleReceiptStore.Receipt durable = receiptStore.find(
                        DurableLifecycleReceiptStore.Lifecycle.FEEDBACK,
                        fingerprints.basisHash())
                .orElse(null);
        if (durable != null
                && !(durable.state() == DurableLifecycleReceiptStore.State.COMMITTED
                && isExpired(now, durable.atEpochMs()))) {
            if (!durable.payloadHash().equals(fingerprints.fullHash())) {
                return Decision.CONFLICT;
            }
            if (durable.state() == DurableLifecycleReceiptStore.State.COMMITTED) {
                return Decision.REPLAY;
            }
            if (durable.state() == DurableLifecycleReceiptStore.State.PENDING) {
                return Decision.IN_PROGRESS;
            }
        }

        evictOldestCommittedUntilRoom();
        if (entries.size() >= MAX_ENTRIES) {
            return Decision.CAPACITY;
        }
        receiptStore.record(receipt(
                fingerprints,
                DurableLifecycleReceiptStore.State.PENDING,
                now,
                DurableLifecycleReceiptStore.Reason.NONE));
        entries.put(
                fingerprints.basisHash(),
                new Entry(fingerprints.fullHash(), now, State.PENDING));
        return Decision.ACCEPT;
    }

    synchronized void commit(
            String ownerIdentity,
            long sessionId,
            long ratedMessageId,
            String ratedContent,
            String normalizedRating,
            String correction) {
        Fingerprints fingerprints = fingerprints(
                ownerIdentity,
                sessionId,
                ratedMessageId,
                ratedContent,
                normalizedRating,
                correction);
        Entry existing = entries.get(fingerprints.basisHash());
        if (existing != null
                && existing.state() == State.PENDING
                && existing.fingerprintHash().equals(fingerprints.fullHash())) {
            long committedAt = clock.millis();
            try {
                receiptStore.record(receipt(
                        fingerprints,
                        DurableLifecycleReceiptStore.State.COMMITTED,
                        committedAt,
                        DurableLifecycleReceiptStore.Reason.NONE));
            } catch (RuntimeException persistenceFailure) {
                throw new DurableCommitException(persistenceFailure);
            }
            entries.put(
                    fingerprints.basisHash(),
                    new Entry(existing.fingerprintHash(), committedAt, State.COMMITTED));
        }
    }

    public synchronized void abort(
            String ownerIdentity,
            long sessionId,
            long ratedMessageId,
            String ratedContent,
            String normalizedRating,
            String correction) {
        Fingerprints fingerprints = fingerprints(
                ownerIdentity,
                sessionId,
                ratedMessageId,
                ratedContent,
                normalizedRating,
                correction);
        Entry existing = entries.get(fingerprints.basisHash());
        if (existing != null
                && existing.state() == State.PENDING
                && existing.fingerprintHash().equals(fingerprints.fullHash())) {
            receiptStore.record(receipt(
                    fingerprints,
                    DurableLifecycleReceiptStore.State.FAILED,
                    clock.millis(),
                    DurableLifecycleReceiptStore.Reason.MUTATION_FAILED));
            entries.remove(fingerprints.basisHash());
        }
    }

    synchronized int retainedEntryCount() {
        evictExpired(clock.millis());
        return entries.size();
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            long acceptedAt = entry.acceptedAtEpochMs();
            if (entry.state() == State.COMMITTED
                    && now >= acceptedAt
                    && now - acceptedAt >= TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static boolean isExpired(long now, long acceptedAtEpochMs) {
        return now >= acceptedAtEpochMs && now - acceptedAtEpochMs >= TTL_MILLIS;
    }

    private static DurableLifecycleReceiptStore.Receipt receipt(
            Fingerprints fingerprints,
            DurableLifecycleReceiptStore.State state,
            long atEpochMs,
            DurableLifecycleReceiptStore.Reason reason) {
        return new DurableLifecycleReceiptStore.Receipt(
                DurableLifecycleReceiptStore.SCHEMA_VERSION,
                DurableLifecycleReceiptStore.Lifecycle.FEEDBACK,
                fingerprints.basisHash(),
                fingerprints.fullHash(),
                state,
                atEpochMs,
                reason);
    }

    private void evictOldestCommittedUntilRoom() {
        while (entries.size() >= MAX_ENTRIES) {
            boolean removed = false;
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().state() == State.COMMITTED) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return;
            }
        }
    }

    private static Fingerprints fingerprints(
            String ownerIdentity,
            long sessionId,
            long ratedMessageId,
            String ratedContent,
            String normalizedRating,
            String correction) {
        String ownerHash = componentHash("owner", requireText(ownerIdentity, "ownerIdentity"));
        String sessionHash = componentHash("session", Long.toString(sessionId));
        String messageIdHash = componentHash("messageId", Long.toString(ratedMessageId));
        String contentHash = componentHash("content", Objects.requireNonNull(ratedContent, "ratedContent"));
        String ratingHash = componentHash(
                "rating",
                requireText(normalizedRating, "normalizedRating").toUpperCase(Locale.ROOT));
        String correctionHash = componentHash(
                "correction",
                correction == null ? "null:" : "value:" + correction);
        String basisHash = sha256(ownerHash + "\0" + sessionHash + "\0" + messageIdHash + "\0" + contentHash);
        String fullHash = sha256(basisHash + "\0" + ratingHash + "\0" + correctionHash);
        return new Fingerprints(basisHash, fullHash);
    }

    private static String componentHash(String label, String value) {
        return sha256(label + "\0" + value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private record Fingerprints(String basisHash, String fullHash) {
    }

    static final class DurableCommitException extends RuntimeException {
        private DurableCommitException(Throwable cause) {
            super("feedback_receipt_commit_failed", cause);
        }
    }
}
