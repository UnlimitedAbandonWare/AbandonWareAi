package com.example.lms.infra.exec;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAwareExecutorServiceCancelShieldTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void submitFutureDowngradesCancelTrueWithoutInterruptingWorker() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ContextAwareExecutorService executor = new ContextAwareExecutorService(delegate);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        try {
            Future<?> future = executor.submit(() -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    TraceStore.put("test.contextAwareCancelShield.submit.workerInterrupted", true);
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            future.cancel(true);
            release.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
            assertTrue(String.valueOf(TraceStore.get("ops.cancelShield.last.ownerHash")).startsWith("hash:"));
            assertEquals("contextAwareExecutor".length(), TraceStore.get("ops.cancelShield.last.ownerLength"));
            assertFalse(TraceStore.getAll().containsKey("ops.cancelShield.last.owner"));
            assertEquals("cancel(true)->cancel(false)", TraceStore.get("ops.cancelShield.last.mode"));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void timedInvokeAllUsesCancelShieldWithoutInterruptingWorker() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ContextAwareExecutorService executor = new ContextAwareExecutorService(delegate);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        try {
            List<Future<Boolean>> futures = executor.invokeAll(List.of(() -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    TraceStore.put("test.contextAwareCancelShield.invokeAllTimed.workerInterrupted", true);
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
                return true;
            }), 30, TimeUnit.MILLISECONDS);

            assertEquals(1, futures.size());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            release.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
            assertTrue(String.valueOf(TraceStore.get("ops.cancelShield.invokeAll.timeout.ownerHash")).startsWith("hash:"));
            assertEquals("contextAwareExecutor".length(),
                    TraceStore.get("ops.cancelShield.invokeAll.timeout.ownerLength"));
            assertFalse(TraceStore.getAll().containsKey("ops.cancelShield.invokeAll.timeout.owner"));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void interruptedUntimedInvokeAllUsesCancelShieldWithoutInterruptingWorker() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ContextAwareExecutorService executor = new ContextAwareExecutorService(delegate);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch waiterInterrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean workerInterrupted = new AtomicBoolean(false);
        AtomicReference<Map<String, Object>> waiterTrace = new AtomicReference<>(Map.of());
        AtomicReference<Throwable> waiterError = new AtomicReference<>();

        try {
            Thread waiter = new Thread(() -> {
                try {
                    executor.invokeAll(List.of(() -> {
                        started.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            TraceStore.put("test.contextAwareCancelShield.invokeAllInterrupted.workerInterrupted", true);
                            workerInterrupted.set(true);
                            Thread.currentThread().interrupt();
                        } finally {
                            finished.countDown();
                        }
                        return true;
                    }));
                    waiterError.set(new AssertionError("invokeAll should be interrupted"));
                } catch (InterruptedException expected) {
                    TraceStore.put("test.contextAwareCancelShield.invokeAll.waiterInterrupted", true);
                    waiterInterrupted.countDown();
                } catch (Throwable t) {
                    TraceStore.put("test.contextAwareCancelShield.invokeAll.waiterError", true);
                    waiterError.set(t);
                } finally {
                    waiterTrace.set(TraceStore.getAll());
                    TraceStore.clear();
                }
            }, "context-aware-invokeAll-waiter");

            waiter.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(waiterInterrupted.await(1, TimeUnit.SECONDS));
            release.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            waiter.join(1_000);
            assertFalse(waiter.isAlive());
            assertFalse(workerInterrupted.get());
            assertEquals(null, waiterError.get());
            assertTrue(String.valueOf(waiterTrace.get().get("ops.cancelShield.invokeAll.interrupted.ownerHash"))
                    .startsWith("hash:"));
            assertEquals("contextAwareExecutor".length(),
                    waiterTrace.get().get("ops.cancelShield.invokeAll.interrupted.ownerLength"));
            assertFalse(waiterTrace.get().containsKey("ops.cancelShield.invokeAll.interrupted.owner"),
                    String.valueOf(waiterTrace.get()));
            assertEquals(1, waiterTrace.get().get("ops.cancelShield.invokeAll.interrupted.cancelAttempted"));
        } finally {
            release.countDown();
            delegate.shutdownNow();
        }
    }

    @Test
    void invokeAnyUsesCancelShieldWithoutInterruptingLoserTask() throws Exception {
        ExecutorService delegate = Executors.newFixedThreadPool(2);
        ContextAwareExecutorService executor = new ContextAwareExecutorService(delegate);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowFinished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        try {
            Boolean result = executor.invokeAny(List.of(
                    () -> {
                        slowStarted.countDown();
                        try {
                            releaseSlow.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            TraceStore.put("test.contextAwareCancelShield.invokeAny.workerInterrupted", true);
                            interrupted.set(true);
                            Thread.currentThread().interrupt();
                        } finally {
                            slowFinished.countDown();
                        }
                        return false;
                    },
                    () -> {
                        assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
                        return true;
                    }));

            assertTrue(result);
            releaseSlow.countDown();
            assertTrue(slowFinished.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
            assertTrue(String.valueOf(TraceStore.get("ops.cancelShield.invokeAny.ownerHash")).startsWith("hash:"));
            assertEquals("contextAwareExecutor".length(),
                    TraceStore.get("ops.cancelShield.invokeAny.ownerLength"));
        } finally {
            releaseSlow.countDown();
            delegate.shutdownNow();
        }
    }

    @Test
    void contextPropagationGuardFailuresLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/infra/exec/ContextPropagation.java"));

        assertTrue(source.contains("traceSuppressed(\"contextPropagation.guardGet\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"contextPropagation.guardApply\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"context.propagation.suppressed.\" + safeStage, true);"));
    }
}
