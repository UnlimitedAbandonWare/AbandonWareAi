package com.example.lms.resilience;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SingleFlightManagerTest {

    private final List<SingleFlightManager> managers = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        for (SingleFlightManager manager : managers) {
            try {
                manager.close();
            } catch (Exception ignored) {
                // Best-effort test cleanup; assertions exercise close() explicitly.
            }
        }
        TraceStore.clear();
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        MDC.clear();
    }

    @Test
    void assertionErrorCompletesBothJoinedCallersWithoutRunningOwnerTwice() throws Exception {
        SingleFlightManager manager = track(new SingleFlightManager());
        ExecutorService callers = testPool(2, "sf-error-caller");
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);
        AtomicInteger ownerCalls = new AtomicInteger();

        Callable<String> owner = () -> {
            ownerCalls.incrementAndGet();
            ownerEntered.countDown();
            if (!releaseOwner.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("owner was not released");
            }
            throw new AssertionError("synthetic owner failure");
        };

        try {
            Future<Throwable> first = callers.submit(() -> failureOf(() -> manager.run("error-key", owner)));
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS), "owner never started");
            Future<Throwable> second = callers.submit(() -> {
                followerStarted.countDown();
                return failureOf(() -> manager.run("error-key", owner));
            });
            assertTrue(followerStarted.await(1, TimeUnit.SECONDS), "follower never called run");
            Thread.sleep(50L);
            releaseOwner.countDown();

            assertInstanceOf(AssertionError.class, first.get(1, TimeUnit.SECONDS));
            assertInstanceOf(AssertionError.class, second.get(1, TimeUnit.SECONDS));
            assertEquals(1, ownerCalls.get(), "joined callers must share exactly one owner");
        } finally {
            releaseOwner.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void timeoutReleasesEveryWaiterInterruptsOwnerAndAllowsImmediateKeyReuse() throws Exception {
        SingleFlightManager manager = configuredManager(400L, testPool(2, "sf-timeout-owner"));
        ExecutorService callers = testPool(2, "sf-timeout-caller");
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch ownerInterrupted = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);
        AtomicInteger ownerCalls = new AtomicInteger();

        Callable<String> owner = () -> {
            ownerCalls.incrementAndGet();
            ownerEntered.countDown();
            try {
                Thread.sleep(5_000L);
                return "late";
            } catch (InterruptedException interrupted) {
                ownerInterrupted.countDown();
                throw interrupted;
            }
        };

        try {
            Future<Throwable> first = callers.submit(() -> failureOf(() -> manager.run("timeout-key", owner)));
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS), "owner never started");
            Future<Throwable> second = callers.submit(() -> {
                followerStarted.countDown();
                return failureOf(() -> manager.run("timeout-key", owner));
            });
            assertTrue(followerStarted.await(1, TimeUnit.SECONDS), "follower never called run");
            Thread.sleep(50L);

            Throwable firstTimeout = first.get(2, TimeUnit.SECONDS);
            Throwable secondTimeout = second.get(2, TimeUnit.SECONDS);
            assertInstanceOf(TimeoutException.class, firstTimeout);
            assertInstanceOf(TimeoutException.class, secondTimeout);
            assertSame(firstTimeout, secondTimeout, "all joined waiters must observe the shared timeout outcome");
            assertTrue(ownerInterrupted.await(1, TimeUnit.SECONDS), "timeout must reach the owner as an interrupt");
            assertEquals(1, ownerCalls.get(), "timeout waiters must still share one owner");
            assertEquals("fresh", manager.run("timeout-key", () -> "fresh"),
                    "a timed-out key must be reusable immediately");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void interruptedFollowerRestoresItsFlagWithoutCancellingSharedOwner() throws Exception {
        SingleFlightManager manager = configuredManager(2_000L, testPool(2, "sf-interrupt-owner"));
        ExecutorService ownerCaller = testPool(1, "sf-interrupt-caller");
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch followerCalling = new CountDownLatch(1);
        AtomicBoolean ownerInterrupted = new AtomicBoolean();
        AtomicBoolean followerFlagRestored = new AtomicBoolean();
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();

        Future<String> ownerResult = ownerCaller.submit(() -> manager.run("interrupt-key", () -> {
            ownerEntered.countDown();
            try {
                releaseOwner.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                ownerInterrupted.set(true);
                throw interrupted;
            }
            return "owner-result";
        }));

        Thread follower = new Thread(() -> {
            followerCalling.countDown();
            try {
                manager.run("interrupt-key", () -> "must-not-run");
            } catch (Throwable failure) {
                followerFailure.set(failure);
                followerFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "sf-interrupted-follower");
        follower.setDaemon(true);

        try {
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS), "owner never started");
            follower.start();
            assertTrue(followerCalling.await(1, TimeUnit.SECONDS), "follower never called run");
            Thread.sleep(50L);
            follower.interrupt();
            follower.join(1_000L);

            assertFalse(follower.isAlive(), "interrupted follower must terminate");
            assertInstanceOf(InterruptedException.class, followerFailure.get());
            assertTrue(followerFlagRestored.get(), "run must restore the caller interrupt flag");
            assertFalse(ownerInterrupted.get(), "one interrupted waiter must not cancel the shared owner");

            releaseOwner.countDown();
            assertEquals("owner-result", ownerResult.get(1, TimeUnit.SECONDS));
            assertFalse(ownerInterrupted.get(), "shared owner must finish normally");
        } finally {
            releaseOwner.countDown();
            follower.interrupt();
            ownerCaller.shutdownNow();
        }
    }

    @Test
    void closeCancelsWaitersInterruptsOwnersAndRejectsLaterRuns() throws Exception {
        SingleFlightManager manager = configuredManager(5_000L, testPool(2, "sf-close-owner"));
        ExecutorService callers = testPool(2, "sf-close-caller");
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch ownerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);

        Callable<String> owner = () -> {
            ownerEntered.countDown();
            try {
                releaseOwner.await(5, TimeUnit.SECONDS);
                return "released";
            } catch (InterruptedException interrupted) {
                ownerInterrupted.countDown();
                throw interrupted;
            }
        };

        try {
            Future<Throwable> first = callers.submit(() -> failureOf(() -> manager.run("close-key", owner)));
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS), "owner never started");
            Future<Throwable> second = callers.submit(() -> {
                followerStarted.countDown();
                return failureOf(() -> manager.run("close-key", owner));
            });
            assertTrue(followerStarted.await(1, TimeUnit.SECONDS), "follower never called run");
            Thread.sleep(50L);

            close(manager);

            assertInstanceOf(CancellationException.class, first.get(1, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, second.get(1, TimeUnit.SECONDS));
            assertTrue(ownerInterrupted.await(1, TimeUnit.SECONDS), "close must interrupt active owners");
            assertThrows(RejectedExecutionException.class,
                    () -> manager.run("after-close", () -> "must-not-run"));
        } finally {
            releaseOwner.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void rawWorkerStillPropagatesMdcGuardTraceAndTimeBudget() throws Exception {
        SingleFlightManager manager = configuredManager(1_000L, testPool(2, "sf-context-owner"));
        GuardContext guard = new GuardContext();
        TimeBudget budget = new TimeBudget(5_000L);
        TraceStore.put("singleflight.test.trace", "trace-value");
        GuardContextHolder.set(guard);
        TimeBudgetContext.set(budget);
        MDC.put("singleflight.test.mdc", "mdc-value");

        ContextSnapshot snapshot = manager.run("context-key", () -> new ContextSnapshot(
                TraceStore.get("singleflight.test.trace"),
                GuardContextHolder.get(),
                TimeBudgetContext.get(),
                MDC.get("singleflight.test.mdc")));

        assertEquals("trace-value", snapshot.traceValue());
        assertSame(guard, snapshot.guard());
        assertSame(budget, snapshot.budget());
        assertEquals("mdc-value", snapshot.mdcValue());
    }

    private SingleFlightManager configuredManager(long timeoutMs, ExecutorService executor) {
        try {
            Constructor<SingleFlightManager> constructor =
                    SingleFlightManager.class.getDeclaredConstructor(long.class, ExecutorService.class);
            constructor.setAccessible(true);
            return track(constructor.newInstance(timeoutMs, executor));
        } catch (ReflectiveOperationException missingContract) {
            executor.shutdownNow();
            return fail("SingleFlightManager(long, ExecutorService) test seam is required", missingContract);
        }
    }

    private SingleFlightManager track(SingleFlightManager manager) {
        managers.add(manager);
        return manager;
    }

    private static void close(SingleFlightManager manager) throws Exception {
        manager.close();
    }

    private static Throwable failureOf(Callable<?> call) {
        try {
            call.call();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static ExecutorService testPool(int size, String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(size, factory);
    }

    private record ContextSnapshot(Object traceValue, GuardContext guard, TimeBudget budget, String mdcValue) {
    }
}
