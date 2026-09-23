package ai.abandonware.nova.boot.exec;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.cfvm.CfvmFailureRecoveryHandler;
import com.example.lms.cfvm.CfvmJbCbCalculator;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.learn.CfvmKallocLearningProperties;
import com.example.lms.strategy.RetrievalOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPropagatingExecutorServiceCancelShieldTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void submitFutureDowngradesCancelTrueWithoutInterruptingWorker() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ContextPropagatingExecutorService executor = new ContextPropagatingExecutorService(delegate);
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
                    TraceStore.put("test.cancelShield.submit.workerInterrupted", true);
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
            assertEquals("contextPropagatingExecutor".length(), TraceStore.get("ops.cancelShield.last.ownerLength"));
            assertFalse(TraceStore.getAll().containsKey("ops.cancelShield.last.owner"));
            assertEquals("cancel(true)->cancel(false)", TraceStore.get("ops.cancelShield.last.mode"));
            assertEquals(Boolean.TRUE, TraceStore.get("cancel.suppressed"));
            assertEquals("cancel_true_downgraded", TraceStore.get("cancel.suppressed.reason"));
            assertEquals("cancel_true_downgraded", TraceStore.get("cancel.suppressed.errorType"));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void runningDelegateThatRefusesSoftCancelStillBecomesCallerCancelled() {
        RunningNonCancellableFuture<String> delegate =
                new RunningNonCancellableFuture<>("background-result");
        Future<String> future = new CancelShieldFuture<>(delegate, "contextPropagatingExecutor");

        assertTrue(future.cancel(true));
        assertEquals(1, delegate.cancelCalls.get());
        assertFalse(delegate.mayInterruptRequested.get());
        assertTrue(future.isCancelled());
        assertTrue(future.isDone());
        assertThrows(CancellationException.class, future::get);
        assertEquals("cancel(true)->cancel(false)", TraceStore.get("ops.cancelShield.last.mode"));
    }

    @Test
    void cancelTrueTimeoutDowngradeFeedsCfvmFailureRecoveryDebugEventAndKallocBreadcrumbs() {
        RunningNonCancellableFuture<String> delegate =
                new RunningNonCancellableFuture<>("background-result");
        CfvmKallocLearningProperties props = new CfvmKallocLearningProperties();
        props.setBoltzmannTemperature(0.1d);
        RawMatrixBuffer buffer = new RawMatrixBuffer(props);
        DebugEventStore debugEvents = new DebugEventStore();
        CfvmFailureRecoveryHandler recoveryHandler = new CfvmFailureRecoveryHandler(
                provider(buffer),
                provider(new CfvmJbCbCalculator()),
                provider(new RetrievalOrderService()),
                provider(debugEvents));
        TraceStore.put("timeout.stage", "cancel_shield_invoke_all");
        TraceStore.put("chain.steps.planned", 4);
        TraceStore.put("chain.steps.executed", 3);
        TraceStore.put("chain.steps.failed", 1);
        TraceStore.put("resource.valueScore", 0.85d);
        TraceStore.put("web.await.root.engine", "web");
        Future<String> future = new CancelShieldFuture<>(
                delegate,
                "owner-token=should-not-leak",
                () -> debugEvents,
                () -> recoveryHandler);

        assertTrue(future.cancel(true));

        assertEquals("cancel(true)->cancel(false)", TraceStore.get("ops.cancelShield.last.mode"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.triggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.cancelTrueDowngraded"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.recovery.applied"));
        assertEquals("fail_soft_fallback", TraceStore.get("cfvm.kalloc.traceAnchor.routeHint"));
        assertEquals("CFVM", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(1, debugEvents.listByProbe(DebugProbeType.AGENT_REPORT_CFVM, 10).size());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("should-not-leak"));
    }

    @Test
    void cancelTraceDoesNotExposeRawSensitiveOwnerOrId() throws Exception {
        String secret = "sk-" + "cancelShieldOwnerSecret1234567890";

        java.util.concurrent.FutureTask<Void> delegateFuture = new java.util.concurrent.FutureTask<>(() -> null);
        new CancelShieldFuture<>(delegateFuture, "owner-token=" + secret).cancel(true);

        Map<String, Object> futureTrace = TraceStore.getAll();
        String serializedFutureTrace = String.valueOf(futureTrace);
        assertTrue(String.valueOf(futureTrace.get("ops.cancelShield.last.ownerHash")).startsWith("hash:"));
        assertTrue(String.valueOf(futureTrace.get("ops.cancelShield.last.idHash")).startsWith("hash:"));
        assertFalse(futureTrace.containsKey("ops.cancelShield.last.owner"));
        assertFalse(futureTrace.containsKey("ops.cancelShield.last.id"));
        assertFalse(serializedFutureTrace.contains(secret));
        assertFalse(serializedFutureTrace.contains("owner-token="));
        assertEquals("cancel(true)->cancel(false)", TraceStore.get("ops.cancelShield.last.mode"));

        TraceStore.clear();
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        CancelShieldExecutorService executor = new CancelShieldExecutorService(delegate, "owner-token=" + secret);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.invokeAll(List.of(() -> {
                release.await(2, TimeUnit.SECONDS);
                return true;
            }), 30, TimeUnit.MILLISECONDS);

            Map<String, Object> timeoutTrace = TraceStore.getAll();
            String serializedTimeoutTrace = String.valueOf(timeoutTrace);
            assertTrue(String.valueOf(timeoutTrace.get("ops.cancelShield.invokeAll.timeout.ownerHash")).startsWith("hash:"));
            assertTrue(((Number) timeoutTrace.get("ops.cancelShield.invokeAll.timeout.ownerLength")).intValue() > 0);
            assertFalse(timeoutTrace.containsKey("ops.cancelShield.invokeAll.timeout.owner"), serializedTimeoutTrace);
            assertTrue(serializedTimeoutTrace.contains("ownerHash"), serializedTimeoutTrace);
            assertTrue(serializedTimeoutTrace.contains("ownerLength"), serializedTimeoutTrace);
            assertFalse(serializedTimeoutTrace.contains("owner="), serializedTimeoutTrace);
            assertFalse(serializedTimeoutTrace.contains(secret));
            assertFalse(serializedTimeoutTrace.contains("owner-token="));
        } finally {
            release.countDown();
            delegate.shutdownNow();
        }
    }

    @Test
    void invokeAnyTraceDoesNotExposeRawSensitiveOwner() throws Exception {
        String secret = "sk-" + "invokeAnyOwnerSecret1234567890";
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        CancelShieldExecutorService executor = new CancelShieldExecutorService(delegate, "owner-token=" + secret);
        try {
            Boolean result = executor.invokeAny(List.of(() -> true));

            assertTrue(result);
            Map<String, Object> trace = TraceStore.getAll();
            String serializedTrace = String.valueOf(trace);
            assertTrue(String.valueOf(trace.get("ops.cancelShield.invokeAny.ownerHash")).startsWith("hash:"));
            assertTrue(((Number) trace.get("ops.cancelShield.invokeAny.ownerLength")).intValue() > 0);
            assertFalse(trace.containsKey("ops.cancelShield.invokeAny.owner"), serializedTrace);
            assertFalse(serializedTrace.contains(secret), serializedTrace);
            assertFalse(serializedTrace.contains("owner-token="), serializedTrace);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void softCancelDebugLogDoesNotExposeRawExceptionMessage() {
        String secret = "sk-" + "cancelShieldCancelErrorSecret123456";
        Logger logger = (Logger) LoggerFactory.getLogger(CancelShieldFuture.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            Future<Void> throwingFuture = new Future<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    throw new IllegalStateException("cancel failed owner-token=" + secret);
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return false;
                }

                @Override
                public Void get() {
                    return null;
                }

                @Override
                public Void get(long timeout, TimeUnit unit) {
                    return null;
                }
            };

            new CancelShieldFuture<>(throwingFuture, "contextPropagatingExecutor").cancel(true);

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(logs.contains("soft-cancelled"));
            assertTrue(logs.contains("errorHash=hash:"));
            assertTrue(logs.contains("errorLength="));
            assertFalse(logs.contains(secret));
            assertEquals("cancel_shield_cancel_failed", TraceStore.get("ops.cancelShield.last.err"));
            assertFalse(String.valueOf(TraceStore.get("ops.cancelShield.last.err")).contains("IllegalStateException"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void cancelShieldFutureDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
        assertFalse(source.contains("catch (Throwable ignore) {\n            // best-effort\n        }"));
        assertTrue(source.contains("traceTelemetrySkipped(\"record_cancel\", recordError)"));
    }

    @Test
    void cancelShieldExecutorServiceDoesNotUseSilentIgnoreCatches() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Throwable ignore)"),
                "CancelShield executor telemetry failures need redacted stage/errorType breadcrumbs");
        assertTrue(source.contains("ops.cancelShield.telemetry.skipped.errorType"),
                "CancelShield executor should record redacted error type for suppressed telemetry failures");
        assertTrue(source.contains("[AWX][cancel-shield] telemetry trace skipped"),
                "CancelShield telemetry helper should leave a non-recursive debug breadcrumb if TraceStore fails");
    }

    @Test
    void contextPropagationFallbackLeavesRedactedBreadcrumbWhenWrappingFails() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/ContextPropagatingExecutorService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Throwable ignore)"),
                "Context propagation fallback should name suppressed wrapping failures");
        assertTrue(source.contains("traceContextPropagationSkipped(\"wrap.runnable\", propagationError)"));
        assertTrue(source.contains("traceContextPropagationSkipped(\"wrap.callable\", propagationError)"));
        assertTrue(source.contains("ctx.exec.propagation.wrap.skipped.errorType"));
    }

    @Test
    void scheduledContextPropagationUsesFailSoftWrapperMethods() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/ContextPropagatingScheduledExecutorService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("ContextPropagation.wrap("),
                "scheduled executor should share the fail-soft runnable wrapper");
        assertFalse(source.contains("ContextPropagation.wrapCallable("),
                "scheduled executor should share the fail-soft callable wrapper");
        assertTrue(source.contains("delegate.schedule(wrap(command), delay, unit)"));
        assertTrue(source.contains("delegate.schedule(wrapCallable(callable), delay, unit)"));
        assertTrue(source.contains("delegate.scheduleAtFixedRate(wrap(command), initialDelay, period, unit)"));
        assertTrue(source.contains("delegate.scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit)"));
    }

    @Test
    void softCancelDebugLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(err), 180)"));
        assertTrue(source.contains(
                "[CancelShield] delegate.cancel(false) threw; soft-cancelled ownerHash={} idHash={} errorHash={} errorLength={}{}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(err)), messageLength(err), LogCorrelation.suffix()"));
    }

    @Test
    void timedInvokeAllUsesCancelShieldWithoutInterruptingWorker() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ContextPropagatingExecutorService executor = new ContextPropagatingExecutorService(delegate);
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
                            TraceStore.put("test.cancelShield.invokeAllTimed.workerInterrupted", true);
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
            assertEquals("contextPropagatingExecutor".length(),
                    TraceStore.get("ops.cancelShield.invokeAll.timeout.ownerLength"));
            assertFalse(TraceStore.getAll().containsKey("ops.cancelShield.invokeAll.timeout.owner"));
            assertEquals("cancel_shield_invoke_all", TraceStore.get("timeout.stage"));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void zeroTimeoutInvokeAllDoesNotSubmitBackgroundWork() throws Exception {
        RecordingExecutorService delegate = new RecordingExecutorService();
        CancelShieldExecutorService executor = new CancelShieldExecutorService(delegate, "contextPropagatingExecutor");

        List<Future<Boolean>> futures = executor.invokeAll(List.of(() -> true, () -> true),
                0, TimeUnit.MILLISECONDS);

        assertEquals(2, futures.size());
        assertEquals(0, delegate.executeCalls.get());
        assertTrue(futures.stream().allMatch(Future::isCancelled));
        assertEquals(Boolean.TRUE, TraceStore.get("ops.cancelShield.invokeAll.timeout.timedOut"));
        assertEquals(0, TraceStore.get("ops.cancelShield.invokeAll.timeout.submitted"));
        assertEquals(2, TraceStore.get("ops.cancelShield.invokeAll.timeout.remaining"));
    }

    @Test
    void rejectedTimedInvokeAllLeavesTraceBreadcrumb() throws Exception {
        RejectingExecutorService delegate = new RejectingExecutorService();
        CancelShieldExecutorService executor = new CancelShieldExecutorService(delegate, "contextPropagatingExecutor");

        List<Future<Boolean>> futures = executor.invokeAll(List.of(() -> true), 1, TimeUnit.SECONDS);

        assertEquals(1, futures.size());
        assertTrue(futures.get(0).isCancelled());
        assertEquals(1L, TraceStore.getLong("ops.cancelShield.invokeAll.rejected.count"));
        assertEquals("timed", TraceStore.get("ops.cancelShield.invokeAll.rejected.stage"));
    }

    @Test
    void zeroTimeoutInvokeAnyDoesNotSubmitBackgroundWork() {
        RecordingExecutorService delegate = new RecordingExecutorService();
        CancelShieldExecutorService executor = new CancelShieldExecutorService(delegate, "contextPropagatingExecutor");

        assertThrows(TimeoutException.class,
                () -> executor.invokeAny(List.of(() -> true, () -> true), 0, TimeUnit.MILLISECONDS));

        assertEquals(0, delegate.executeCalls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("ops.cancelShield.invokeAny.timedOut"));
        assertEquals(0, TraceStore.get("ops.cancelShield.invokeAny.submitted"));
        assertEquals(0, TraceStore.get("ops.cancelShield.invokeAny.cancelAttempted"));
        assertEquals("cancel_shield_invoke_any", TraceStore.get("timeout.stage"));
    }

    @Test
    void interruptedUntimedInvokeAllUsesCancelShieldWithoutInterruptingWorker() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ContextPropagatingExecutorService executor = new ContextPropagatingExecutorService(delegate);
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
                            TraceStore.put("test.cancelShield.invokeAllInterrupted.workerInterrupted", true);
                            workerInterrupted.set(true);
                            Thread.currentThread().interrupt();
                        } finally {
                            finished.countDown();
                        }
                        return true;
                    }));
                    waiterError.set(new AssertionError("invokeAll should be interrupted"));
                } catch (InterruptedException expected) {
                    TraceStore.put("test.cancelShield.invokeAll.waiterInterrupted", true);
                    waiterInterrupted.countDown();
                } catch (Throwable t) {
                    TraceStore.put("test.cancelShield.invokeAll.waiterError", true);
                    waiterError.set(t);
                } finally {
                    waiterTrace.set(TraceStore.getAll());
                    TraceStore.clear();
                }
            }, "context-propagating-invokeAll-waiter");

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
            assertEquals("contextPropagatingExecutor".length(),
                    waiterTrace.get().get("ops.cancelShield.invokeAll.interrupted.ownerLength"));
            assertFalse(waiterTrace.get().containsKey("ops.cancelShield.invokeAll.interrupted.owner"),
                    String.valueOf(waiterTrace.get()));
            assertFalse(String.valueOf(waiterTrace.get()).contains("owner=contextPropagatingExecutor"),
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
        ContextPropagatingExecutorService executor = new ContextPropagatingExecutorService(delegate);
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
                            TraceStore.put("test.cancelShield.invokeAny.workerInterrupted", true);
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
            assertEquals("contextPropagatingExecutor".length(),
                    TraceStore.get("ops.cancelShield.invokeAny.ownerLength"));
        } finally {
            releaseSlow.countDown();
            delegate.shutdownNow();
        }
    }

    @Test
    void invokeAnyFailureEventDoesNotExposeRawExceptionClassNames() {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        CancelShieldExecutorService executor = new CancelShieldExecutorService(delegate, "contextPropagatingExecutor");
        String secret = "sk-" + "invokeAnyFailureSecret1234567890";
        try {
            assertThrows(ExecutionException.class, () -> executor.invokeAny(List.of(
                    () -> {
                        throw new IllegalStateException("failed owner-token=" + secret);
                    },
                    () -> {
                        throw new IllegalArgumentException("failed owner-token=" + secret);
                    })));

            String trace = String.valueOf(TraceStore.getAll());
            assertTrue(trace.contains("ops.cancelShield.invokeAny.events"), trace);
            assertTrue(trace.contains("invoke_any_task_failed"), trace);
            assertFalse(trace.contains("java.util.concurrent.ExecutionException"), trace);
            assertFalse(trace.contains("IllegalStateException"), trace);
            assertFalse(trace.contains("IllegalArgumentException"), trace);
            assertFalse(trace.contains(secret), trace);
        } finally {
            delegate.shutdownNow();
        }
    }

    private static class RecordingExecutorService extends AbstractExecutorService {
        private final AtomicInteger executeCalls = new AtomicInteger();
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            executeCalls.incrementAndGet();
        }
    }

    private static final class RejectingExecutorService extends RecordingExecutorService {
        @Override
        public void execute(Runnable command) {
            super.execute(command);
            throw new java.util.concurrent.RejectedExecutionException("test-rejected-secret");
        }
    }

    private static final class RunningNonCancellableFuture<T> implements Future<T> {
        private final T value;
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicBoolean mayInterruptRequested = new AtomicBoolean();

        private RunningNonCancellableFuture(T value) {
            this.value = value;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            mayInterruptRequested.set(mayInterruptIfRunning);
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public T get(long timeout, TimeUnit unit) {
            return value;
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? Collections.emptyIterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
