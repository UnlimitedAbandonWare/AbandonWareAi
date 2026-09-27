package com.example.lms.integrations.n8n;

import com.example.lms.lifecycle.DurableLifecycleReceiptStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Process-local, hash-only idempotency registry for accepted n8n webhooks.
 */
@Component
public class N8nIdempotencyRegistry {

    static final int MAX_ENTRIES = 4096;
    static final long TTL_MILLIS = 86_400_000L;

    private final Object mutex = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Clock clock;
    private final DurableLifecycleReceiptStore receiptStore;

    public N8nIdempotencyRegistry() {
        this(Clock.systemUTC(), DurableLifecycleReceiptStore.none());
    }

    @Autowired
    public N8nIdempotencyRegistry(DurableLifecycleReceiptStore receiptStore) {
        this(Clock.systemUTC(), receiptStore);
    }

    N8nIdempotencyRegistry(Clock clock) {
        this(clock, DurableLifecycleReceiptStore.none());
    }

    N8nIdempotencyRegistry(Clock clock, DurableLifecycleReceiptStore receiptStore) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    }

    public Decision accept(
            String keyHash,
            String bodyHash,
            Supplier<String> enqueueSupplier) {
        Objects.requireNonNull(enqueueSupplier, "enqueueSupplier");
        String normalizedKeyHash = normalizeHash(keyHash);
        if (normalizedKeyHash.isEmpty()) {
            return accepted(enqueueSupplier.get(), false);
        }
        String normalizedBodyHash = normalizeHash(bodyHash);
        if (normalizedBodyHash.isEmpty()) {
            return conflict();
        }

        synchronized (mutex) {
            long now = clock.millis();
            evictExpiredLocked(now);
            Entry existing = entries.get(normalizedKeyHash);
            if (existing != null) {
                if (!existing.bodyHash().equals(normalizedBodyHash)) {
                    return conflict();
                }
                return new Decision(existing.jobId(), true, false, false, "");
            }

            DurableLifecycleReceiptStore.Receipt durable = receiptStore.find(
                            DurableLifecycleReceiptStore.Lifecycle.N8N,
                            normalizedKeyHash)
                    .orElse(null);
            if (durable != null && !isExpired(now, durable.atEpochMs())) {
                if (!durable.payloadHash().equals(normalizedBodyHash)) {
                    return conflict();
                }
                if (durable.state() == DurableLifecycleReceiptStore.State.ACCEPTED
                        || durable.state() == DurableLifecycleReceiptStore.State.INTENT) {
                    return new Decision(
                            "",
                            true,
                            false,
                            true,
                            DurableLifecycleReceiptStore.receiptHash(durable));
                }
            }

            receiptStore.record(receipt(
                    normalizedKeyHash,
                    normalizedBodyHash,
                    DurableLifecycleReceiptStore.State.INTENT,
                    now,
                    DurableLifecycleReceiptStore.Reason.NONE));
            String jobId;
            try {
                jobId = normalizeJobId(enqueueSupplier.get());
            } catch (RuntimeException | Error failure) {
                receiptStore.record(receipt(
                        normalizedKeyHash,
                        normalizedBodyHash,
                        DurableLifecycleReceiptStore.State.FAILED,
                        clock.millis(),
                        DurableLifecycleReceiptStore.Reason.ENQUEUE_FAILED));
                throw failure;
            }
            if (!jobId.isEmpty()) {
                receiptStore.record(receipt(
                        normalizedKeyHash,
                        normalizedBodyHash,
                        DurableLifecycleReceiptStore.State.ACCEPTED,
                        clock.millis(),
                        DurableLifecycleReceiptStore.Reason.NONE));
                entries.put(normalizedKeyHash, new Entry(
                        normalizedKeyHash, normalizedBodyHash, jobId, now));
                evictOldestLocked();
            } else {
                receiptStore.record(receipt(
                        normalizedKeyHash,
                        normalizedBodyHash,
                        DurableLifecycleReceiptStore.State.FAILED,
                        clock.millis(),
                        DurableLifecycleReceiptStore.Reason.ENQUEUE_FAILED));
            }
            return new Decision(jobId, false, false, false, "");
        }
    }

    private static Decision accepted(String jobId, boolean replayed) {
        return new Decision(normalizeJobId(jobId), replayed, false, false, "");
    }

    private static Decision conflict() {
        return new Decision("", false, true, false, "");
    }

    private static DurableLifecycleReceiptStore.Receipt receipt(
            String keyHash,
            String bodyHash,
            DurableLifecycleReceiptStore.State state,
            long atEpochMs,
            DurableLifecycleReceiptStore.Reason reason) {
        return new DurableLifecycleReceiptStore.Receipt(
                DurableLifecycleReceiptStore.SCHEMA_VERSION,
                DurableLifecycleReceiptStore.Lifecycle.N8N,
                keyHash,
                bodyHash,
                state,
                atEpochMs,
                reason);
    }

    private void evictExpiredLocked(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (isExpired(now, entry.acceptedAtEpochMs())) {
                iterator.remove();
            }
        }
    }

    private void evictOldestLocked() {
        Iterator<String> iterator = entries.keySet().iterator();
        while (entries.size() > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean isExpired(long now, long acceptedAtEpochMs) {
        if (now < acceptedAtEpochMs) {
            return false;
        }
        return now - acceptedAtEpochMs >= TTL_MILLIS;
    }

    private static String normalizeHash(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeJobId(String value) {
        return value == null ? "" : value.trim();
    }

    private record Entry(
            String keyHash,
            String bodyHash,
            String jobId,
            long acceptedAtEpochMs) {
    }

    public record Decision(
            String jobId,
            boolean replayed,
            boolean conflict,
            boolean durableReplay,
            String receiptHash) {
    }
}
