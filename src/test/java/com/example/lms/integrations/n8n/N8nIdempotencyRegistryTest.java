package com.example.lms.integrations.n8n;

import com.example.lms.lifecycle.DurableLifecycleReceiptStore;
import com.example.lms.lifecycle.JsonlLifecycleReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class N8nIdempotencyRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptedWebhookSurvivesRestartAsUnknownWithoutFabricatingJobId() throws Exception {
        Path receipts = tempDir.resolve("n8n-lifecycle.jsonl");
        Clock clock = fixedClock();
        String rawKey = "private-idempotency-key-sentinel";
        String rawBody = "private-webhook-body-sentinel";
        String keyHash = DurableLifecycleReceiptStore.hash("n8n-key", rawKey);
        String bodyHash = DurableLifecycleReceiptStore.hash("n8n-body", rawBody);
        AtomicInteger calls = new AtomicInteger();

        N8nIdempotencyRegistry first = new N8nIdempotencyRegistry(
                clock,
                new JsonlLifecycleReceiptStore(receipts, new ObjectMapper().findAndRegisterModules()));
        N8nIdempotencyRegistry.Decision accepted = first.accept(
                keyHash,
                bodyHash,
                () -> "job-" + calls.incrementAndGet());

        N8nIdempotencyRegistry restarted = new N8nIdempotencyRegistry(
                clock,
                new JsonlLifecycleReceiptStore(receipts, new ObjectMapper().findAndRegisterModules()));
        N8nIdempotencyRegistry.Decision replay = restarted.accept(
                keyHash,
                bodyHash,
                () -> "job-" + calls.incrementAndGet());

        assertEquals("job-1", accepted.jobId());
        assertEquals("", replay.jobId());
        assertTrue(replay.replayed());
        assertTrue(replay.durableReplay());
        assertFalse(replay.conflict());
        assertTrue(replay.receiptHash().matches("[0-9a-f]{64}"));
        assertEquals(1, calls.get());
        String persisted = Files.readString(receipts, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(rawKey));
        assertFalse(persisted.contains(rawBody));
        assertFalse(persisted.contains("job-1"));
    }

    @Test
    void sameKeyAndBodyReturnsOriginalJobWithoutSecondSupplierCall() {
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(fixedClock());
        AtomicInteger calls = new AtomicInteger();

        N8nIdempotencyRegistry.Decision first = registry.accept(
                hash('k', 1), hash('b', 1), () -> "job-" + calls.incrementAndGet());
        N8nIdempotencyRegistry.Decision replay = registry.accept(
                hash('k', 1), hash('b', 1), () -> "job-" + calls.incrementAndGet());

        assertEquals("job-1", first.jobId());
        assertFalse(first.replayed());
        assertFalse(first.conflict());
        assertEquals("job-1", replay.jobId());
        assertTrue(replay.replayed());
        assertFalse(replay.conflict());
        assertEquals(1, calls.get());
    }

    @Test
    void sameKeyWithDifferentBodyConflictsWithoutSecondSupplierCall() {
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(fixedClock());
        AtomicInteger calls = new AtomicInteger();
        registry.accept(hash('k', 2), hash('b', 1), () -> "job-" + calls.incrementAndGet());

        N8nIdempotencyRegistry.Decision conflict = registry.accept(
                hash('k', 2), hash('b', 2), () -> "job-" + calls.incrementAndGet());

        assertTrue(conflict.conflict());
        assertFalse(conflict.replayed());
        assertEquals("", conflict.jobId());
        assertEquals(1, calls.get());
    }

    @Test
    void missingOrBlankKeyExecutesSupplierEveryTime() {
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(fixedClock());
        AtomicInteger calls = new AtomicInteger();

        N8nIdempotencyRegistry.Decision missing = registry.accept(
                null, null, () -> "job-" + calls.incrementAndGet());
        N8nIdempotencyRegistry.Decision blank = registry.accept(
                " ", hash('b', 3), () -> "job-" + calls.incrementAndGet());

        assertEquals("job-1", missing.jobId());
        assertEquals("job-2", blank.jobId());
        assertFalse(missing.replayed());
        assertFalse(blank.replayed());
        assertEquals(2, calls.get());
    }

    @Test
    void capacityNeverRetainsMoreThan4096KeysAndEvictsOldest() throws Exception {
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(fixedClock());
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 4097; i++) {
            registry.accept(hash('k', i), hash('b', i), () -> "job-" + calls.incrementAndGet());
        }

        assertEquals(4096, retainedEntries(registry).size());

        N8nIdempotencyRegistry.Decision oldest = registry.accept(
                hash('k', 0), hash('b', 0), () -> "job-" + calls.incrementAndGet());
        assertFalse(oldest.replayed());
        assertEquals(4098, calls.get());
        assertEquals(4096, retainedEntries(registry).size());
    }

    @Test
    void entryExpiresAfterTwentyFourHoursWithoutRefreshingOnReplay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(clock);
        AtomicInteger calls = new AtomicInteger();
        registry.accept(hash('k', 4), hash('b', 4), () -> "job-" + calls.incrementAndGet());

        clock.advance(Duration.ofHours(23));
        assertTrue(registry.accept(
                hash('k', 4), hash('b', 4), () -> "job-" + calls.incrementAndGet()).replayed());

        clock.advance(Duration.ofHours(1));
        N8nIdempotencyRegistry.Decision expired = registry.accept(
                hash('k', 4), hash('b', 4), () -> "job-" + calls.incrementAndGet());

        assertFalse(expired.replayed());
        assertEquals("job-2", expired.jobId());
        assertEquals(2, calls.get());
    }

    @Test
    void concurrentSameKeyAndBodyInvokesSupplierOnce() throws Exception {
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(fixedClock());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<N8nIdempotencyRegistry.Decision>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return registry.accept(
                            hash('k', 5), hash('b', 5), () -> "job-" + calls.incrementAndGet());
                }));
            }
            start.countDown();

            for (Future<N8nIdempotencyRegistry.Decision> future : futures) {
                assertEquals("job-1", future.get(5, TimeUnit.SECONDS).jobId());
            }
            assertEquals(1, calls.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retainedEntriesContainHashesAndNeverRawKeyOrBody() throws Exception {
        String rawKey = "raw-idempotency-private-key";
        String rawBody = "raw-n8n-private-body";
        N8nIdempotencyRegistry registry = new N8nIdempotencyRegistry(fixedClock());
        registry.accept(hash('k', 6), hash('b', 6), () -> "job-safe");

        String retained = String.valueOf(retainedEntries(registry));
        assertFalse(retained.contains(rawKey));
        assertFalse(retained.contains(rawBody));
        assertTrue(retained.contains(hash('k', 6)));
        assertTrue(retained.contains(hash('b', 6)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> retainedEntries(N8nIdempotencyRegistry registry) throws Exception {
        for (Field field : N8nIdempotencyRegistry.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return (Map<String, ?>) field.get(registry);
            }
        }
        throw new AssertionError("registry must retain one bounded map");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    }

    private static String hash(char prefix, int value) {
        return prefix + "-sha256-" + String.format("%064x", value);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
