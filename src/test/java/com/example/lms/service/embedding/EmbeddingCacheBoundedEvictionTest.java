package com.example.lms.service.embedding;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.lms.search.TraceStore;

class EmbeddingCacheBoundedEvictionTest {
    private static final Duration TTL = Duration.ofMinutes(1);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    static Stream<Duration> nonPositiveTtls() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1));
    }

    @ParameterizedTest
    @MethodSource("nonPositiveTtls")
    void nonPositiveTtlReturnsComputedVectorWithoutRetainingIt(Duration ttl) throws Exception {
        var cache = new EmbeddingCache.InMemory();
        assertArrayEquals(new float[] {1}, cache.getOrCompute("synthetic", () -> new float[] {1}, ttl));
        assertEquals(0, entries(cache, "map").size());
        assertArrayEquals(new float[] {2}, cache.getOrCompute("synthetic", () -> new float[] {2}, ttl));
        assertEquals(0, entries(cache, "inflight").size());
    }

    @Test
    void expiredLookupRemovesStorageWhileReturningStaleOnEmptyCompute() throws Exception {
        var cache = new EmbeddingCache.InMemory();
        Constructor<?> entry = Class.forName(EmbeddingCache.InMemory.class.getName() + "$Entry")
                .getDeclaredConstructor(float[].class, long.class);
        entry.setAccessible(true);
        entries(cache, "map").put("expired", entry.newInstance(new float[] {7}, 1L));
        assertArrayEquals(new float[] {7}, cache.getOrCompute("expired", () -> new float[0], TTL));
        assertEquals(0, entries(cache, "map").size());
        assertEquals(0, entries(cache, "inflight").size());
    }

    @Test
    void defaultCapacityBoundsDistinctCompletedKeys() throws Exception {
        var cache = new EmbeddingCache.InMemory();
        for (int i = 0; i < 1025; i++) {
            cache.getOrCompute("synthetic-" + i, () -> new float[] {1}, TTL);
        }
        assertEquals(1024, entries(cache, "map").size());
    }

    @Test
    void invalidatedOldLeaderCannotOverwriteReplacementResult() throws Exception {
        var cache = new EmbeddingCache.InMemory();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var old = executor.submit(() -> cache.getOrCompute("race", () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return new float[] {1};
        }, TTL));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cache.invalidate("race");
            assertArrayEquals(new float[] {2}, cache.getOrCompute("race", () -> new float[] {2}, TTL));
            release.countDown();
            assertArrayEquals(new float[] {1}, old.get(2, TimeUnit.SECONDS));
            assertArrayEquals(new float[] {2}, cache.getOrCompute("race", () -> new float[] {3}, TTL));
            assertEquals(0, entries(cache, "inflight").size());
        } finally {
            release.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void accessOrderEvictsOldestAndInsertionSweepsExpiredEntries() throws Exception {
        var clock = new MutableClock(1000);
        var cache = new EmbeddingCache.InMemory(clock, 2);
        cache.getOrCompute("a", () -> new float[] {1}, TTL);
        cache.getOrCompute("b", () -> new float[] {2}, TTL);
        assertArrayEquals(new float[] {1}, cache.getOrCompute("a", () -> fail("cache hit"), TTL));
        cache.getOrCompute("c", () -> new float[] {3}, TTL);
        assertEquals(java.util.Set.of("a", "c"), entries(cache, "map").keySet());
        assertEquals(1L, TraceStore.get("embeddingCache.hit.count"));
        assertEquals(1L, TraceStore.get("embeddingCache.evicted.count"));
        clock.advance(TTL.toMillis() + 1);
        cache.getOrCompute("d", () -> new float[] {4}, TTL);
        assertEquals(java.util.Set.of("d"), entries(cache, "map").keySet());
        assertEquals(2L, TraceStore.get("embeddingCache.expired.count"));
        assertEquals(1, TraceStore.get("embeddingCache.size"));
    }

    @Test
    void exactExpiryBoundaryStaysFreshThenFailedRefreshReturnsStaleOnce() throws Exception {
        var clock = new MutableClock(1000);
        var cache = new EmbeddingCache.InMemory(clock, 2);
        cache.getOrCompute("clock", () -> new float[] {1}, Duration.ofMillis(10));
        clock.advance(10);
        assertArrayEquals(new float[] {1}, cache.getOrCompute("clock", () -> fail("still fresh"), TTL));
        clock.advance(1);
        assertArrayEquals(new float[] {1}, cache.getOrCompute("clock", () -> {
            throw new IllegalStateException("synthetic failure");
        }, TTL));
        assertEquals(0, entries(cache, "map").size());
        assertEquals(1L, TraceStore.get("embeddingCache.computeFailed.count"));
        assertArrayEquals(new float[0], cache.getOrCompute("clock", () -> new float[0], TTL));
        assertEquals(0, entries(cache, "inflight").size());
    }

    @Test
    void timeSpentComputingDoesNotRenewExpiredStartTimeTtl() throws Exception {
        var clock = new MutableClock(1000);
        var cache = new EmbeddingCache.InMemory(clock, 2);
        assertArrayEquals(new float[] {1}, cache.getOrCompute("slow", () -> {
            clock.advance(11);
            return new float[] {1};
        }, Duration.ofMillis(10)));
        assertEquals(0, entries(cache, "map").size());
        assertEquals(0, entries(cache, "inflight").size());
    }

    @Test
    void extremePositiveDurationDoesNotStrandFlightOrBecomeNegativeExpiry() throws Exception {
        var cache = new EmbeddingCache.InMemory(new MutableClock(1000), 2);
        assertArrayEquals(new float[] {1}, cache.getOrCompute("overflow", () -> new float[] {1},
                Duration.ofSeconds(Long.MAX_VALUE)));
        assertArrayEquals(new float[] {1}, cache.getOrCompute("overflow", () -> fail("cache hit"), TTL));
        assertEquals(0, entries(cache, "inflight").size());
    }

    static Stream<float[]> emptyVectors() {
        return Stream.of(null, new float[0]);
    }

    @ParameterizedTest
    @MethodSource("emptyVectors")
    void missingOrEmptyVectorDoesNotPoisonLaterRetry(float[] value) throws Exception {
        var cache = new EmbeddingCache.InMemory(new MutableClock(1000), 2);
        assertArrayEquals(new float[0], cache.getOrCompute("empty", () -> value, TTL));
        assertEquals(0, entries(cache, "map").size());
        assertArrayEquals(new float[] {8}, cache.getOrCompute("empty", () -> new float[] {8}, TTL));
    }

    @Test
    void invalidConstructorInputsFailBeforeAnyCacheStateExists() {
        assertThrows(NullPointerException.class, () -> new EmbeddingCache.InMemory(null, 2));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingCache.InMemory(Clock.systemUTC(), 0));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingCache.InMemory(Clock.systemUTC(), -1));
    }

    @Test
    void expiredFlightKeepsStaleForInterruptedFollowerAndNeverInterruptsOwner() throws Exception {
        var clock = new MutableClock(1000);
        var cache = new EmbeddingCache.InMemory(clock, 1);
        cache.getOrCompute("shared", () -> new float[] {7}, Duration.ofMillis(1));
        clock.advance(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var ownerInterrupted = new AtomicBoolean();
        var leader = executor.submit(() -> cache.getOrCompute("shared", () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            } catch (InterruptedException interrupted) {
                ownerInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new float[0];
        }, TTL));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, entries(cache, "map").size());
            Thread.currentThread().interrupt();
            try {
                assertArrayEquals(new float[] {7}, cache.getOrCompute("shared", () -> fail("shared flight"), TTL));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertFalse(leader.isDone());
            release.countDown();
            assertArrayEquals(new float[] {7}, leader.get(2, TimeUnit.SECONDS));
            assertFalse(ownerInterrupted.get());
            assertEquals(0, entries(cache, "inflight").size());
        } finally {
            release.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void saturatedFlightsRemainBoundedAndExistingKeyStillSharesResult() throws Exception {
        var cache = new EmbeddingCache.InMemory(new MutableClock(1000), 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var calls = new AtomicInteger();
        var leader = executor.submit(() -> cache.getOrCompute("shared", () -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new float[] {4};
        }, TTL));
        var value = new AtomicReference<float[]>();
        var failure = new AtomicReference<Throwable>();
        var follower = new Thread(() -> {
            try {
                value.set(cache.getOrCompute("shared", () -> {
                    calls.incrementAndGet();
                    return new float[] {5};
                }, TTL));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "f43-capacity-follower");
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var overflowCalls = new AtomicInteger();
            for (int i = 0; i < 8; i++) {
                assertArrayEquals(new float[0], cache.getOrCompute("overflow-" + i,
                        () -> {
                            overflowCalls.incrementAndGet();
                            return new float[] {9};
                        }, TTL));
                assertEquals(1, entries(cache, "inflight").size());
            }
            assertEquals(0, overflowCalls.get());
            follower.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (follower.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.TIMED_WAITING, follower.getState());
            assertEquals(1, calls.get());
            release.countDown();
            assertArrayEquals(new float[] {4}, leader.get(2, TimeUnit.SECONDS));
            follower.join(2000);
            assertFalse(follower.isAlive());
            assertNull(failure.get());
            assertArrayEquals(new float[] {4}, value.get());
            assertEquals(1, calls.get());
            assertEquals(0, entries(cache, "inflight").size());
            assertArrayEquals(new float[] {6}, cache.getOrCompute("available-again", () -> new float[] {6}, TTL));
        } finally {
            release.countDown();
            if (follower.isAlive()) {
                follower.interrupt();
                follower.join(2000);
            }
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now;

        MutableClock(long millis) { now = new AtomicLong(millis); }
        void advance(long millis) { now.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
        @Override public long millis() { return now.get(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entries(EmbeddingCache.InMemory cache, String name) throws Exception {
        Field field = EmbeddingCache.InMemory.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(cache);
    }
}
