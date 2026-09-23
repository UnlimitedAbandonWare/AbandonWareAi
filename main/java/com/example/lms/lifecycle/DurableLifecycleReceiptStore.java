package com.example.lms.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Durable, privacy-bounded lifecycle evidence. Implementations may persist only
 * the seven fields carried by {@link Receipt}; callers must hash private input
 * before crossing this boundary.
 */
public interface DurableLifecycleReceiptStore {

    int SCHEMA_VERSION = 1;

    enum Lifecycle {
        FEEDBACK,
        N8N,
        ATTACHMENT
    }

    enum State {
        PENDING,
        COMMITTED,
        FAILED,
        INTENT,
        ACCEPTED,
        CREATED,
        DELETE_REQUESTED,
        DELETED,
        DELETE_FAILED
    }

    enum Reason {
        NONE,
        MUTATION_FAILED,
        ENQUEUE_FAILED,
        DURABLE_REPLAY,
        STORAGE_DELETE_FAILED,
        ROLLBACK
    }

    record Receipt(
            int schemaVersion,
            Lifecycle lifecycle,
            String subjectHash,
            String payloadHash,
            State state,
            long atEpochMs,
            Reason reason) {
    }

    Optional<Receipt> find(Lifecycle lifecycle, String subjectHash);

    Optional<Receipt> latest(Lifecycle lifecycle);

    void record(Receipt receipt);

    static DurableLifecycleReceiptStore none() {
        return NoOpHolder.INSTANCE;
    }

    static String hash(String domain, String value) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain");
        }
        if (value == null) {
            throw new IllegalArgumentException("value");
        }
        return sha256(domain + "\0" + value);
    }

    static String receiptHash(Receipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("receipt");
        }
        return sha256(receipt.schemaVersion()
                + "\0" + receipt.lifecycle()
                + "\0" + receipt.subjectHash()
                + "\0" + receipt.payloadHash()
                + "\0" + receipt.state()
                + "\0" + receipt.atEpochMs()
                + "\0" + receipt.reason());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    final class NoOpHolder {
        private static final DurableLifecycleReceiptStore INSTANCE = new DurableLifecycleReceiptStore() {
            @Override
            public Optional<Receipt> find(Lifecycle lifecycle, String subjectHash) {
                return Optional.empty();
            }

            @Override
            public Optional<Receipt> latest(Lifecycle lifecycle) {
                return Optional.empty();
            }

            @Override
            public void record(Receipt receipt) {
                // Explicitly process-local compatibility constructor for unit tests.
            }
        };

        private NoOpHolder() {
        }
    }
}
