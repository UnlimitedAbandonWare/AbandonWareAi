package com.example.lms.service.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class EmbeddingCacheInterruptContractTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    @Test
    void restoresInterruptedFollowerWithoutDisturbingSingleFlightLeader() throws Exception {
        EmbeddingCache cache = new EmbeddingCache.InMemory();
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        AtomicInteger computeCount = new AtomicInteger();
        AtomicBoolean leaderInterrupted = new AtomicBoolean();
        ExecutorService leaderExecutor = Executors.newSingleThreadExecutor();
        Future<float[]> leader = leaderExecutor.submit(() -> cache.getOrCompute(
                "shared-interrupt-key",
                () -> {
                    computeCount.incrementAndGet();
                    leaderEntered.countDown();
                    try {
                        if (!releaseLeader.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("leader release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        leaderInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        return new float[0];
                    }
                    return new float[] { 1.0f };
                },
                TTL));

        Thread follower = null;
        try {
            assertTrue(leaderEntered.await(2, TimeUnit.SECONDS));

            AtomicReference<float[]> followerValue = new AtomicReference<>();
            AtomicReference<Throwable> followerFailure = new AtomicReference<>();
            AtomicBoolean followerSupplierCalled = new AtomicBoolean();
            AtomicBoolean followerInterruptRestored = new AtomicBoolean();

            follower = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    followerValue.set(cache.getOrCompute(
                            "shared-interrupt-key",
                            () -> {
                                followerSupplierCalled.set(true);
                                return new float[] { 2.0f };
                            },
                            TTL));
                    followerInterruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    followerFailure.set(failure);
                } finally {
                    Thread.interrupted();
                }
            }, "embedding-cache-interrupted-follower");

            follower.start();
            follower.join(2_000L);

            assertFalse(follower.isAlive(), "interrupted follower must finish within the bound");
            assertNull(followerFailure.get());
            assertArrayEquals(new float[0], followerValue.get());
            assertFalse(followerSupplierCalled.get());
            assertTrue(followerInterruptRestored.get(), "follower interrupt must be restored before returning");
            assertFalse(leader.isDone(), "follower interruption must not complete or cancel the leader");
            assertFalse(leaderInterrupted.get());
            assertEquals(1, computeCount.get());

            releaseLeader.countDown();
            assertArrayEquals(new float[] { 1.0f }, leader.get(2, TimeUnit.SECONDS));
            assertFalse(leaderInterrupted.get());

            cache.invalidate("shared-interrupt-key");
            assertArrayEquals(
                    new float[] { 3.0f },
                    cache.getOrCompute(
                            "shared-interrupt-key",
                            () -> {
                                computeCount.incrementAndGet();
                                return new float[] { 3.0f };
                            },
                            TTL));
            assertEquals(2, computeCount.get(), "completed flight must allow later key reuse");
        } finally {
            releaseLeader.countDown();
            if (follower != null && follower.isAlive()) {
                follower.interrupt();
                follower.join(2_000L);
            }
            leaderExecutor.shutdown();
            if (!leaderExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                leaderExecutor.shutdownNow();
                assertTrue(leaderExecutor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void nonInterruptedFollowerSharesResultWithoutSettingInterrupt() throws Exception {
        EmbeddingCache cache = new EmbeddingCache.InMemory();
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);
        AtomicInteger computeCount = new AtomicInteger();
        ExecutorService leaderExecutor = Executors.newSingleThreadExecutor();
        Future<float[]> leader = leaderExecutor.submit(() -> cache.getOrCompute(
                "shared-normal-key",
                () -> {
                    computeCount.incrementAndGet();
                    leaderEntered.countDown();
                    try {
                        releaseLeader.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return new float[0];
                    }
                    return new float[] { 4.0f };
                },
                TTL));

        AtomicReference<float[]> followerValue = new AtomicReference<>();
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();
        AtomicBoolean followerSupplierCalled = new AtomicBoolean();
        AtomicBoolean followerInterrupted = new AtomicBoolean(true);
        Thread follower = new Thread(() -> {
            followerStarted.countDown();
            try {
                followerValue.set(cache.getOrCompute(
                        "shared-normal-key",
                        () -> {
                            followerSupplierCalled.set(true);
                            return new float[] { 5.0f };
                        },
                        TTL));
                followerInterrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                followerFailure.set(failure);
            }
        }, "embedding-cache-normal-follower");

        try {
            assertTrue(leaderEntered.await(2, TimeUnit.SECONDS));
            follower.start();
            assertTrue(followerStarted.await(2, TimeUnit.SECONDS));
            releaseLeader.countDown();
            follower.join(2_000L);

            assertFalse(follower.isAlive());
            assertNull(followerFailure.get());
            assertArrayEquals(new float[] { 4.0f }, followerValue.get());
            assertFalse(followerSupplierCalled.get());
            assertFalse(followerInterrupted.get());
            assertArrayEquals(new float[] { 4.0f }, leader.get(2, TimeUnit.SECONDS));
            assertEquals(1, computeCount.get());
        } finally {
            releaseLeader.countDown();
            if (follower.isAlive()) {
                follower.interrupt();
                follower.join(2_000L);
            }
            leaderExecutor.shutdown();
            if (!leaderExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                leaderExecutor.shutdownNow();
                assertTrue(leaderExecutor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }
}
