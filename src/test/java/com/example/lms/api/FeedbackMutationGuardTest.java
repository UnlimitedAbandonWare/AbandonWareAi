package com.example.lms.api;

import com.example.lms.lifecycle.JsonlLifecycleReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackMutationGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void committedFeedbackReplaySurvivesRestartWithoutRetainingRawInputs() throws Exception {
        Path receipts = tempDir.resolve("feedback-lifecycle.jsonl");
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        String owner = "anon\0private-owner-sentinel";
        String answer = "private-answer-sentinel";
        String correction = "private-correction-sentinel";

        FeedbackMutationGuard first = new FeedbackMutationGuard(
                clock,
                new JsonlLifecycleReceiptStore(receipts, new ObjectMapper().findAndRegisterModules()));
        assertEquals(
                FeedbackMutationGuard.Decision.ACCEPT,
                first.accept(owner, 7L, 11L, answer, "POSITIVE", correction));
        first.commit(owner, 7L, 11L, answer, "POSITIVE", correction);

        FeedbackMutationGuard restarted = new FeedbackMutationGuard(
                clock,
                new JsonlLifecycleReceiptStore(receipts, new ObjectMapper().findAndRegisterModules()));

        assertEquals(
                FeedbackMutationGuard.Decision.REPLAY,
                restarted.accept(owner, 7L, 11L, answer, "POSITIVE", correction));
        assertEquals(
                FeedbackMutationGuard.Decision.CONFLICT,
                restarted.accept(owner, 7L, 11L, answer, "NEGATIVE", correction));
        String persisted = Files.readString(receipts, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(owner));
        assertFalse(persisted.contains(answer));
        assertFalse(persisted.contains(correction));
    }

    @Test
    void identicalReplayAndChangedFeedbackHaveDistinctDecisions() {
        FeedbackMutationGuard guard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));

        assertEquals(FeedbackMutationGuard.Decision.ACCEPT, accept(guard, "POSITIVE", "correction-a"));
        commit(guard, "POSITIVE", "correction-a");
        assertEquals(FeedbackMutationGuard.Decision.REPLAY, accept(guard, "POSITIVE", "correction-a"));
        assertEquals(FeedbackMutationGuard.Decision.CONFLICT, accept(guard, "NEGATIVE", "correction-a"));
        assertEquals(FeedbackMutationGuard.Decision.CONFLICT, accept(guard, "POSITIVE", "correction-b"));
        assertEquals(1, guard.retainedEntryCount());
    }

    @Test
    void nullCorrectionDoesNotCollideWithAnyLiteralCorrectionValue() {
        FeedbackMutationGuard guard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));

        assertEquals(FeedbackMutationGuard.Decision.ACCEPT, accept(guard, "POSITIVE", null));
        assertEquals(FeedbackMutationGuard.Decision.CONFLICT, accept(guard, "POSITIVE", "null:"));
        assertEquals(FeedbackMutationGuard.Decision.CONFLICT, accept(guard, "POSITIVE", "<null>"));
    }

    @Test
    void replayDoesNotRefreshTtlAndEntryExpiresAtExactly24Hours() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        FeedbackMutationGuard guard = new FeedbackMutationGuard(clock);

        assertEquals(FeedbackMutationGuard.Decision.ACCEPT, accept(guard, "POSITIVE", null));
        commit(guard, "POSITIVE", null);
        clock.advanceMillis(TimeUnit.HOURS.toMillis(23));
        assertEquals(FeedbackMutationGuard.Decision.REPLAY, accept(guard, "POSITIVE", null));
        clock.advanceMillis(TimeUnit.HOURS.toMillis(1));
        assertEquals(FeedbackMutationGuard.Decision.ACCEPT, accept(guard, "POSITIVE", null));
    }

    @Test
    void ttlStartsAtCommitWhenMutationWasPendingLongerThan24Hours() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        FeedbackMutationGuard guard = new FeedbackMutationGuard(clock);

        assertEquals(FeedbackMutationGuard.Decision.ACCEPT, accept(guard, "POSITIVE", null));
        clock.advanceMillis(TimeUnit.HOURS.toMillis(24));
        assertEquals(FeedbackMutationGuard.Decision.IN_PROGRESS, accept(guard, "POSITIVE", null));
        commit(guard, "POSITIVE", null);
        assertEquals(FeedbackMutationGuard.Decision.REPLAY, accept(guard, "POSITIVE", null));
        clock.advanceMillis(TimeUnit.HOURS.toMillis(24));
        assertEquals(FeedbackMutationGuard.Decision.ACCEPT, accept(guard, "POSITIVE", null));
    }

    @Test
    void insertionCapacityIs4096AndEvictsOldestBasis() {
        FeedbackMutationGuard guard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));

        for (long i = 0; i < 4097; i++) {
            assertEquals(
                    FeedbackMutationGuard.Decision.ACCEPT,
                    guard.accept("anon\0owner-" + i, i, i, "answer-" + i, "POSITIVE", null));
            guard.commit("anon\0owner-" + i, i, i, "answer-" + i, "POSITIVE", null);
        }

        assertEquals(4096, guard.retainedEntryCount());
        assertEquals(
                FeedbackMutationGuard.Decision.ACCEPT,
                guard.accept("anon\0owner-0", 0L, 0L, "answer-0", "POSITIVE", null));
        assertEquals(4096, guard.retainedEntryCount());
    }

    @Test
    void concurrentIdenticalAcceptanceHasExactlyOneWinner() throws Exception {
        FeedbackMutationGuard guard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
        int workers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FeedbackMutationGuard.Decision>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return accept(guard, "POSITIVE", "correction");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<FeedbackMutationGuard.Decision> decisions = new ArrayList<>();
            for (Future<FeedbackMutationGuard.Decision> future : futures) {
                decisions.add(future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1L, decisions.stream()
                    .filter(FeedbackMutationGuard.Decision.ACCEPT::equals)
                    .count());
            assertEquals(workers - 1L, decisions.stream()
                    .filter(FeedbackMutationGuard.Decision.IN_PROGRESS::equals)
                    .count());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void fullPendingCapacityRejectsNewBasisWithoutEvictingLiveAdmission() {
        FeedbackMutationGuard guard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
        for (long i = 0; i < 4096; i++) {
            assertEquals(
                    FeedbackMutationGuard.Decision.ACCEPT,
                    guard.accept("anon\0pending-" + i, i, i, "answer-" + i, "POSITIVE", null));
        }

        assertEquals(
                FeedbackMutationGuard.Decision.CAPACITY,
                guard.accept("anon\0overflow", 5000L, 5000L, "overflow", "POSITIVE", null));
        assertEquals(
                FeedbackMutationGuard.Decision.IN_PROGRESS,
                guard.accept("anon\0pending-0", 0L, 0L, "answer-0", "POSITIVE", null));
        assertEquals(4096, guard.retainedEntryCount());
    }

    @Test
    void retainedStateContainsHashesAndTimestampsButNoRawInputs() throws Exception {
        FeedbackMutationGuard guard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
        String owner = "anon\0owner-private-marker";
        String answer = "private-answer-marker";
        String correction = "private-correction-marker";

        guard.accept(owner, 77L, 88L, answer, "POSITIVE", correction);

        Field entriesField = FeedbackMutationGuard.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        Object value = entriesField.get(guard);
        assertTrue(value instanceof Map<?, ?>);
        String retained = String.valueOf(value);
        assertFalse(retained.contains(owner));
        assertFalse(retained.contains(answer));
        assertFalse(retained.contains(correction));
        assertTrue(retained.matches(".*[0-9a-f]{64}.*"));
    }

    private static FeedbackMutationGuard.Decision accept(
            FeedbackMutationGuard guard,
            String rating,
            String correction) {
        return guard.accept(
                "anon\0owner-a",
                7L,
                11L,
                "stored assistant answer",
                rating,
                correction);
    }

    private static void commit(
            FeedbackMutationGuard guard,
            String rating,
            String correction) {
        guard.commit(
                "anon\0owner-a",
                7L,
                11L,
                "stored assistant answer",
                rating,
                correction);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
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
