package com.example.lms.service.routing.plan;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterDecisionCacheTest {

    @BeforeEach
    void clearTrace() {
        TraceStore.clear();
        TimeBudgetContext.clear();
    }

    @AfterEach
    void clearBudget() {
        TimeBudgetContext.clear();
    }

    @Test
    void fallbackWaitPreservesCompatibilityBudgetDefault() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);
        Field fallback = RouterDecisionCache.class.getDeclaredField("fallbackWaitMillis");
        fallback.setAccessible(true);

        assertEquals(1_500L, fallback.getLong(cache));
    }

    @Test
    void supplierFailurePropagatesWithRedactedSuppressionTrace() {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> cache.getOrCompute(
                        "tenant:ownerToken=raw-secret",
                        "decision:api_key=raw-secret",
                        "slice-a",
                        String.class,
                        () -> {
                            throw new IllegalStateException("ownerToken=raw-secret");
                        }));

        assertEquals("ownerToken=raw-secret", thrown.getMessage());
        assertEquals(Boolean.TRUE, TraceStore.get("router.plan.cache.suppressed"));
        assertEquals("compute", TraceStore.get("router.plan.cache.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("router.plan.cache.suppressed.errorType"));
        assertTrue(String.valueOf(TraceStore.get("router.plan.cache.suppressed.keyHash")).startsWith("hash:"));
        assertEquals(74, TraceStore.get("router.plan.cache.suppressed.keyLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken=raw-secret"));
        assertFalse(trace.contains("api_key=raw-secret"));
    }

    @Test
    void expiredLeaderLeaseReleasesFollowerAndLateLeaderCannotDeleteReplacementFlight() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch leader1Entered = new CountDownLatch(1);
        CountDownLatch releaseLeader1 = new CountDownLatch(1);
        CountDownLatch leader2Entered = new CountDownLatch(1);
        CountDownLatch releaseLeader2 = new CountDownLatch(1);
        AtomicInteger leader1Calls = new AtomicInteger();
        AtomicInteger leader2Calls = new AtomicInteger();
        AtomicInteger unexpectedCalls = new AtomicInteger();
        AtomicReference<Thread> firstFollowerThread = new AtomicReference<>();
        try {
            Future<String> leader1 = executor.submit(() -> withBudget(1_000, () -> cache.getOrCompute(
                    "ns", "decision", "slice", String.class, () -> {
                        leader1Calls.incrementAndGet();
                        leader1Entered.countDown();
                        awaitLatch(releaseLeader1, "release leader 1");
                        return "late-old";
                    })));
            assertTrue(leader1Entered.await(2, TimeUnit.SECONDS), "leader 1 did not enter supplier");

            Future<String> firstFollower = executor.submit(() -> withBudget(2_000, () -> {
                firstFollowerThread.set(Thread.currentThread());
                return cache.getOrCompute("ns", "decision", "slice", String.class, () -> {
                    unexpectedCalls.incrementAndGet();
                    return "unexpected";
                });
            }));
            awaitCondition(() -> isWaiting(firstFollowerThread.get()), 500,
                    "first follower did not attach to leader 1");
            ExecutionException followerFailure = assertThrows(ExecutionException.class,
                    () -> firstFollower.get(2, TimeUnit.SECONDS));
            assertTrue(rootCause(followerFailure) instanceof TimeoutException, followerFailure.toString());
            awaitCondition(() -> inflightCount(cache) == 0, 1_000, "expired flight was not removed");

            Future<String> leader2 = executor.submit(() -> withBudget(3_000, () -> cache.getOrCompute(
                    "ns", "decision", "slice", String.class, () -> {
                        leader2Calls.incrementAndGet();
                        leader2Entered.countDown();
                        awaitLatch(releaseLeader2, "release leader 2");
                        return "replacement";
                    })));
            assertTrue(leader2Entered.await(2, TimeUnit.SECONDS), "replacement leader did not enter supplier");

            AtomicReference<Thread> followerThread = new AtomicReference<>();
            Future<String> replacementFollower = executor.submit(() -> withBudget(3_000, () -> {
                followerThread.set(Thread.currentThread());
                return cache.getOrCompute("ns", "decision", "slice", String.class, () -> {
                    unexpectedCalls.incrementAndGet();
                    return "unexpected";
                });
            }));
            awaitCondition(() -> isWaiting(followerThread.get()), 1_000,
                    "replacement follower did not wait on leader 2");

            releaseLeader1.countDown();
            ExecutionException lateLeaderFailure = assertThrows(ExecutionException.class,
                    () -> leader1.get(1, TimeUnit.SECONDS));
            assertTrue(rootCause(lateLeaderFailure) instanceof TimeoutException, lateLeaderFailure.toString());
            assertEquals(1, inflightCount(cache), "late leader cleanup deleted the replacement flight");

            releaseLeader2.countDown();
            assertEquals("replacement", leader2.get(1, TimeUnit.SECONDS));
            assertEquals("replacement", replacementFollower.get(1, TimeUnit.SECONDS));
            assertEquals(1, leader1Calls.get());
            assertEquals(1, leader2Calls.get());
            assertEquals(0, unexpectedCalls.get());
            awaitCondition(() -> inflightCount(cache) == 0, 1_000, "replacement flight was not cleaned");
        } finally {
            releaseLeader1.countDown();
            releaseLeader2.countDown();
            shutdown(executor);
        }
    }

    @Test
    void timedOutLeaderCannotOverwriteAlreadyPublishedReplacementInL2() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", true, 10, 60);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch staleLeaderEntered = new CountDownLatch(1);
        CountDownLatch releaseStaleLeader = new CountDownLatch(1);
        AtomicInteger unexpectedCalls = new AtomicInteger();
        try {
            Future<String> staleLeader = executor.submit(() -> withBudget(300, () -> cache.getOrCompute(
                    "ns", "published", "slice", String.class, () -> {
                        staleLeaderEntered.countDown();
                        awaitLatch(releaseStaleLeader, "release stale leader");
                        return "late-old";
                    })));
            assertTrue(staleLeaderEntered.await(2, TimeUnit.SECONDS), "stale leader did not enter supplier");
            awaitCondition(() -> inflightCount(cache) == 0, 1_000, "expired stale flight was not removed");

            assertEquals("replacement", withBudget(2_000, () -> cache.getOrCompute(
                    "ns", "published", "slice", String.class, () -> "replacement")));

            releaseStaleLeader.countDown();
            ExecutionException staleFailure = assertThrows(ExecutionException.class,
                    () -> staleLeader.get(1, TimeUnit.SECONDS));
            assertTrue(rootCause(staleFailure) instanceof TimeoutException, staleFailure.toString());

            TraceStore.clear();
            assertEquals("replacement",
                    cache.getIfPresent("ns", "published", "slice", String.class).orElseThrow());
            TraceStore.clear();
            assertEquals("replacement", cache.getOrCompute(
                    "ns", "published", "slice", String.class, () -> {
                        unexpectedCalls.incrementAndGet();
                        return "unexpected";
                    }));
            assertEquals(0, unexpectedCalls.get(), "late leader overwrote the replacement cache entry");
            assertEquals(0, inflightCount(cache));
        } finally {
            releaseStaleLeader.countDown();
            shutdown(executor);
        }
    }

    @Test
    void shortFollowerDeadlineDoesNotCancelHealthyLeaderOrEvictItsFlight() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicReference<Thread> validFollowerThread = new AtomicReference<>();
        AtomicReference<Map<String, Object>> shortFollowerTrace = new AtomicReference<>();
        try {
            Future<String> leader = executor.submit(() -> withBudget(3_000, () -> cache.getOrCompute(
                    "ns", "decision", "slice", String.class, () -> {
                        supplierCalls.incrementAndGet();
                        leaderEntered.countDown();
                        awaitLatch(releaseLeader, "release healthy leader");
                        return "shared";
                    })));
            assertTrue(leaderEntered.await(2, TimeUnit.SECONDS), "healthy leader did not enter supplier");

            Future<String> shortFollower = executor.submit(() -> {
                try {
                    return withBudget(100, () -> cache.getOrCompute(
                            "ns", "decision", "slice", String.class, () -> {
                                supplierCalls.incrementAndGet();
                                return "unexpected";
                            }));
                } catch (RuntimeException failure) {
                    shortFollowerTrace.set(Map.copyOf(TraceStore.getAll()));
                    throw failure;
                }
            });
            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> shortFollower.get(1, TimeUnit.SECONDS));
            assertTrue(rootCause(timeout) instanceof TimeoutException, timeout.toString());
            assertEquals(1, inflightCount(cache), "local follower timeout must not evict a healthy shared flight");
            Map<String, Object> timeoutTrace = shortFollowerTrace.get();
            assertTrue(timeoutTrace != null, "follower timeout trace was not captured");
            assertEquals("follower", timeoutTrace.get("router.plan.cache.wait.role"));
            assertEquals("timeout", timeoutTrace.get("router.plan.cache.wait.outcome"));
            assertEquals("follower_deadline_expired", timeoutTrace.get("router.plan.cache.wait.reason"));
            assertEquals("not_owner", timeoutTrace.get("router.plan.cache.wait.cleanupResult"));
            assertEquals(1, timeoutTrace.get("router.plan.cache.wait.inflightCount"));
            assertTrue(String.valueOf(timeoutTrace.get("router.plan.cache.wait.keyHash")).startsWith("hash:"));
            String traceText = timeoutTrace.toString();
            assertFalse(traceText.contains("decision"), traceText);
            assertFalse(traceText.contains("slice"), traceText);

            Future<String> validFollower = executor.submit(() -> withBudget(3_000, () -> {
                validFollowerThread.set(Thread.currentThread());
                return cache.getOrCompute("ns", "decision", "slice", String.class, () -> {
                    supplierCalls.incrementAndGet();
                    return "unexpected";
                });
            }));
            awaitCondition(() -> isWaiting(validFollowerThread.get()), 1_000,
                    "valid follower did not join the healthy flight");
            releaseLeader.countDown();
            assertEquals("shared", leader.get(1, TimeUnit.SECONDS));
            assertEquals("shared", validFollower.get(1, TimeUnit.SECONDS));
            assertEquals(1, supplierCalls.get());
        } finally {
            releaseLeader.countDown();
            shutdown(executor);
        }
    }

    @Test
    void interruptedFollowerTerminatesAndRestoresFlagWithoutCancellingLeader() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        AtomicBoolean leaderInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();
        AtomicBoolean followerInterruptRestored = new AtomicBoolean();
        Thread follower = new Thread(() -> {
            TimeBudgetContext.set(new TimeBudget(3_000));
            try {
                cache.getOrCompute("ns", "decision", "slice", String.class, () -> "unexpected");
            } catch (Throwable failure) {
                followerFailure.set(failure);
                followerInterruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                TimeBudgetContext.clear();
            }
        }, "router-cache-interrupt-follower");
        try {
            Future<String> leader = executor.submit(() -> withBudget(3_000, () -> cache.getOrCompute(
                    "ns", "decision", "slice", String.class, () -> {
                        leaderEntered.countDown();
                        try {
                            if (!releaseLeader.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("release healthy leader timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            leaderInterrupted.set(true);
                            Thread.currentThread().interrupt();
                            throw new CancellationException("leader interrupted");
                        }
                        return "shared";
                    })));
            assertTrue(leaderEntered.await(2, TimeUnit.SECONDS), "leader did not enter supplier");
            follower.start();
            awaitCondition(() -> isWaiting(follower), 1_000, "follower did not block on shared flight");

            follower.interrupt();
            follower.join(1_000);
            assertFalse(follower.isAlive(), "interrupted follower remained blocked");
            assertTrue(followerFailure.get() instanceof CancellationException,
                    String.valueOf(followerFailure.get()));
            assertTrue(followerInterruptRestored.get(), "follower interrupt flag was not restored");
            assertFalse(leaderInterrupted.get(), "follower interruption leaked into leader ownership");

            releaseLeader.countDown();
            assertEquals("shared", leader.get(1, TimeUnit.SECONDS));
        } finally {
            releaseLeader.countDown();
            follower.join(2_000);
            shutdown(executor);
        }
    }

    @Test
    void healthyConcurrentCallersShareSupplierAndResult() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        try {
            Future<String> leader = executor.submit(() -> withBudget(3_000, () -> cache.getOrCompute(
                    "ns", "decision", "slice", String.class, () -> {
                        supplierCalls.incrementAndGet();
                        leaderEntered.countDown();
                        awaitLatch(releaseLeader, "release shared leader");
                        return "shared";
                    })));
            assertTrue(leaderEntered.await(2, TimeUnit.SECONDS), "leader did not enter supplier");
            Future<String> follower = executor.submit(() -> withBudget(3_000, () -> {
                followerThread.set(Thread.currentThread());
                return cache.getOrCompute("ns", "decision", "slice", String.class, () -> {
                    supplierCalls.incrementAndGet();
                    return "unexpected";
                });
            }));
            awaitCondition(() -> isWaiting(followerThread.get()), 1_000, "follower did not join shared flight");

            releaseLeader.countDown();
            assertEquals("shared", leader.get(1, TimeUnit.SECONDS));
            assertEquals("shared", follower.get(1, TimeUnit.SECONDS));
            assertEquals(1, supplierCalls.get());
        } finally {
            releaseLeader.countDown();
            shutdown(executor);
        }
    }

    @Test
    void leaderFailureCompletesFollowerWithoutHanging() throws Exception {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        try {
            Future<String> leader = executor.submit(() -> withBudget(3_000, () -> cache.getOrCompute(
                    "ns", "decision", "slice", String.class, () -> {
                        supplierCalls.incrementAndGet();
                        leaderEntered.countDown();
                        awaitLatch(releaseFailure, "release leader failure");
                        throw new IllegalStateException("planner_failed");
                    })));
            assertTrue(leaderEntered.await(2, TimeUnit.SECONDS), "leader did not enter supplier");
            Future<String> follower = executor.submit(() -> withBudget(3_000, () -> {
                followerThread.set(Thread.currentThread());
                return cache.getOrCompute("ns", "decision", "slice", String.class, () -> {
                    supplierCalls.incrementAndGet();
                    return "unexpected";
                });
            }));
            awaitCondition(() -> isWaiting(followerThread.get()), 1_000, "follower did not join failed flight");

            releaseFailure.countDown();
            ExecutionException leaderFailure = assertThrows(ExecutionException.class,
                    () -> leader.get(1, TimeUnit.SECONDS));
            ExecutionException followerFailure = assertThrows(ExecutionException.class,
                    () -> follower.get(1, TimeUnit.SECONDS));
            assertTrue(rootCause(leaderFailure) instanceof IllegalStateException, leaderFailure.toString());
            assertTrue(rootCause(followerFailure) instanceof IllegalStateException, followerFailure.toString());
            assertEquals(1, supplierCalls.get());
            awaitCondition(() -> inflightCount(cache) == 0, 1_000, "failed flight was not cleaned");
        } finally {
            releaseFailure.countDown();
            shutdown(executor);
        }
    }

    private static <T> T withBudget(long budgetMillis, Supplier<T> action) {
        TimeBudgetContext.set(new TimeBudget(budgetMillis));
        try {
            return action.get();
        } finally {
            TimeBudgetContext.clear();
        }
    }

    private static void awaitLatch(CountDownLatch latch, String label) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(label + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException(label + " interrupted");
        }
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMillis, String failureMessage) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(failureMessage);
            }
            Thread.yield();
        }
    }

    private static boolean isWaiting(Thread thread) {
        if (thread == null) {
            return false;
        }
        return thread.getState() == Thread.State.WAITING
                || thread.getState() == Thread.State.TIMED_WAITING;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static int inflightCount(RouterDecisionCache cache) {
        try {
            Field field = RouterDecisionCache.class.getDeclaredField("inflight");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(cache)).size();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect inflight cache", failure);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "test executor did not terminate");
    }
}
