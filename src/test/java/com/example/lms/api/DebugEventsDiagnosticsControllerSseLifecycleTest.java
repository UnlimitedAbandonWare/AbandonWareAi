package com.example.lms.api;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugEventsDiagnosticsControllerSseLifecycleTest {

    private static final Duration WAIT_BOUND = Duration.ofSeconds(2);

    private final List<DebugEventsSseRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void shutDownRuntimes() throws InterruptedException {
        for (DebugEventsSseRuntime runtime : runtimes) {
            runtime.shutdown();
            runtime.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void productionConstructorIsExplicitAndTimeoutIsAlwaysFinite() throws Exception {
        Constructor<?> production = Arrays.stream(DebugEventsDiagnosticsController.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .findFirst()
                .orElseThrow();

        assertNotNull(production.getAnnotation(Autowired.class));
        assertEquals(3, production.getParameterCount());
        Value timeoutValue = annotation(production.getParameterAnnotations()[2], Value.class);
        assertNotNull(timeoutValue);
        assertEquals("${lms.debug.events.sse.timeout-ms:300000}", timeoutValue.value());

        long[][] cases = {
                { 12_345L, 12_345L },
                { 0L, 300_000L },
                { -1L, 300_000L },
                { 999L, 1_000L },
                { 3_600_001L, 3_600_000L }
        };
        for (long[] timeoutCase : cases) {
            RecordingThreadFactory threads = new RecordingThreadFactory();
            DebugEventsSseRuntime runtime = runtime(1, threads);
            AtomicReference<ImmediateCompletionEmitter> made = new AtomicReference<>();
            DebugEventsDiagnosticsController controller = new DebugEventsDiagnosticsController(
                    emptyStore(), runtime, timeoutCase[0], timeout -> {
                        ImmediateCompletionEmitter emitter = new ImmediateCompletionEmitter(timeout);
                        made.set(emitter);
                        return emitter;
                    });

            SseEmitter returned = controller.stream(1, 5_000L, 60_000L, null);

            assertSame(made.get(), returned);
            assertEquals(timeoutCase[1], made.get().getTimeout());
            assertEquals(0L, runtime.executionAttempts());
            assertEquals(0, threads.starts.get());
        }
    }

    @Test
    void completionInterruptsTheOwnedTaskWithoutWaitingForPollInterval() throws Exception {
        RecordingThreadFactory threads = new RecordingThreadFactory();
        DebugEventsSseRuntime runtime = runtime(1, threads);
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        DebugEventsDiagnosticsController controller = controller(runtime, emitter);

        controller.stream(1, 5_000L, 60_000L, null);
        assertTrue(emitter.firstSend.await(2, TimeUnit.SECONDS));

        emitter.fireCompletion();

        await(() -> runtime.completedTaskCount() == 1L);
        assertTrue(threads.interrupts.get() >= 1);
        assertEquals(0, emitter.completeCalls.get());
        assertEquals(0, emitter.errorCalls.get());
        assertEquals(0, runtime.activeCount());
    }

    @Test
    void completionDuringRegistrationSkipsRuntimeExecutionEntirely() {
        RecordingThreadFactory threads = new RecordingThreadFactory();
        DebugEventsSseRuntime runtime = runtime(1, threads);
        ImmediateCompletionEmitter emitter = new ImmediateCompletionEmitter(300_000L);
        DebugEventsDiagnosticsController controller = controller(runtime, emitter);

        assertSame(emitter, controller.stream(1, 5_000L, 60_000L, null));

        assertEquals(0L, runtime.executionAttempts());
        assertEquals(0, runtime.activeCount());
        assertEquals(0, runtime.queueSize());
        assertEquals(0, threads.starts.get());
        RecordingEmitter recorded = emitter;
        assertEquals(0, recorded.completeCalls.get());
        assertEquals(0, recorded.errorCalls.get());
    }

    @Test
    void timeoutErrorAndCompletionShareOneIdempotentCancellation() throws Exception {
        RecordingThreadFactory threads = new RecordingThreadFactory();
        DebugEventsSseRuntime runtime = runtime(1, threads);
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        DebugEventsDiagnosticsController controller = controller(runtime, emitter);

        controller.stream(1, 5_000L, 60_000L, null);
        assertTrue(emitter.firstSend.await(2, TimeUnit.SECONDS));

        emitter.fireTimeout();
        emitter.fireError(new IOException("controlled"));
        emitter.fireCompletion();

        await(() -> runtime.completedTaskCount() == 1L);
        assertTrue(threads.interrupts.get() >= 1);
        assertEquals(0, emitter.completeCalls.get());
        assertEquals(0, emitter.errorCalls.get());
        assertEquals(0, runtime.activeCount());
    }

    @Test
    void saturationRejectsWithoutQueueAndCapacityRecovers() throws Exception {
        RecordingThreadFactory threads = new RecordingThreadFactory();
        DebugEventsSseRuntime runtime = runtime(1, threads);
        List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();
        DebugEventsDiagnosticsController controller = new DebugEventsDiagnosticsController(
                emptyStore(), runtime, 300_000L, timeout -> {
                    RecordingEmitter emitter = new RecordingEmitter(timeout);
                    emitters.add(emitter);
                    return emitter;
                });

        controller.stream(1, 5_000L, 60_000L, null);
        assertTrue(emitters.get(0).firstSend.await(2, TimeUnit.SECONDS));

        controller.stream(1, 5_000L, 60_000L, null);
        RecordingEmitter rejected = emitters.get(1);
        assertEquals(1, rejected.errorCalls.get());
        assertNotNull(rejected.lastError.get());
        assertEquals("debug_events_sse_capacity", rejected.lastError.get().getMessage());
        assertEquals(0, runtime.queueSize());
        assertEquals(1, threads.starts.get());

        emitters.get(0).fireCompletion();
        await(() -> runtime.completedTaskCount() == 1L);

        controller.stream(1, 5_000L, 60_000L, null);
        RecordingEmitter recovered = emitters.get(2);
        assertTrue(recovered.firstSend.await(2, TimeUnit.SECONDS));
        assertEquals(3L, runtime.executionAttempts());
        assertEquals(0, runtime.queueSize());
        recovered.fireCompletion();
        await(() -> runtime.completedTaskCount() == 2L);
    }

    @Test
    void runtimeShutdownInterruptsAndCompletesAcceptedStream() throws Exception {
        RecordingThreadFactory threads = new RecordingThreadFactory();
        DebugEventsSseRuntime runtime = runtime(1, threads);
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        DebugEventsDiagnosticsController controller = controller(runtime, emitter);

        controller.stream(1, 5_000L, 60_000L, null);
        assertTrue(emitter.firstSend.await(2, TimeUnit.SECONDS));

        runtime.shutdown();

        assertTrue(runtime.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(runtime.isShutdown());
        assertTrue(threads.interrupts.get() >= 1);
        assertEquals(1, emitter.completeCalls.get());
        assertEquals(0, emitter.errorCalls.get());
    }

    @Test
    void sendFailureReleasesCapacityForTheNextStream() throws Exception {
        RecordingThreadFactory threads = new RecordingThreadFactory();
        DebugEventsSseRuntime runtime = runtime(1, threads);
        List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        DebugEventsDiagnosticsController controller = new DebugEventsDiagnosticsController(
                emptyStore(), runtime, 300_000L, timeout -> {
                    RecordingEmitter emitter = new RecordingEmitter(timeout);
                    emitter.failSend.set(failFirst.getAndSet(false));
                    emitters.add(emitter);
                    return emitter;
                });

        controller.stream(1, 5_000L, 60_000L, null);
        await(() -> runtime.completedTaskCount() == 1L);
        assertEquals(1, emitters.get(0).completeCalls.get());
        assertEquals(0, runtime.activeCount());

        controller.stream(1, 5_000L, 60_000L, null);
        RecordingEmitter recovered = emitters.get(1);
        assertTrue(recovered.firstSend.await(2, TimeUnit.SECONDS));
        assertEquals(0, runtime.queueSize());
        recovered.fireCompletion();
        await(() -> runtime.completedTaskCount() == 2L);
    }

    @Test
    void initialAndTailDeliveryPreserveOrderAndDistinctIdsAtTheSameTimestamp() throws Exception {
        DebugEvent older = event("older", 100L);
        DebugEvent first = event("first", 200L);
        DebugEvent sameTime = event("same-time", 200L);
        DebugEvent newer = event("newer", 300L);
        AtomicReference<List<DebugEvent>> events = new AtomicReference<>(List.of(first, older));
        AtomicInteger reads = new AtomicInteger();
        DebugEventStore store = mock(DebugEventStore.class);
        when(store.list(anyInt())).thenAnswer(ignored -> {
            reads.incrementAndGet();
            return new ArrayList<>(events.get());
        });
        DebugEventsSseRuntime runtime = runtime(1, new RecordingThreadFactory());
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        new DebugEventsDiagnosticsController(store, runtime, 300_000L, ignored -> emitter)
                .stream(50, 200L, 60_000L, null);
        await(() -> emitter.events.size() == 2);
        assertEquals(List.of(older, first), emitter.events);
        events.set(List.of(newer, sameTime, first, older));
        await(() -> emitter.events.size() == 4);
        int afterDelivery = reads.get();
        await(() -> reads.get() > afterDelivery);
        emitter.fireCompletion();
        await(() -> runtime.completedTaskCount() == 1L);
        assertEquals(List.of(older, first, sameTime, newer), emitter.events);
    }

    @Test
    void reconnectRetainsInitialSnapshotReplayWithoutRepeatingItInTheTail() throws Exception {
        DebugEvent older = event("older", 100L);
        DebugEvent last = event("last", 200L);
        DebugEvent newer = event("newer", 300L);
        DebugEventStore store = mock(DebugEventStore.class);
        AtomicInteger reads = new AtomicInteger();
        when(store.get("last")).thenReturn(last);
        when(store.list(anyInt())).thenAnswer(ignored -> {
            reads.incrementAndGet();
            return new ArrayList<>(List.of(newer, last, older));
        });
        DebugEventsSseRuntime runtime = runtime(1, new RecordingThreadFactory());
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        new DebugEventsDiagnosticsController(store, runtime, 300_000L, ignored -> emitter)
                .stream(50, 200L, 60_000L, " last ");
        await(() -> reads.get() >= 3);
        emitter.fireCompletion();
        await(() -> runtime.completedTaskCount() == 1L);
        assertEquals(List.of(older, last, newer), emitter.events);
    }

    @Test
    void initialDataSendFailureStopsTheBatchAndReleasesTheWorker() {
        DebugEventStore store = mock(DebugEventStore.class);
        when(store.list(anyInt())).thenAnswer(ignored -> new ArrayList<>(
                List.of(event("newer", 200L), event("older", 100L))));
        DebugEventsSseRuntime runtime = runtime(1, new RecordingThreadFactory());
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        emitter.failDataSend.set(true);
        new DebugEventsDiagnosticsController(store, runtime, 300_000L, ignored -> emitter)
                .stream(50, 200L, 60_000L, null);
        await(() -> runtime.completedTaskCount() == 1L);
        assertEquals(2, emitter.sendCalls.get()); // hello + first data attempt
        assertTrue(emitter.events.isEmpty());
        assertEquals(1, emitter.completeCalls.get());
        assertEquals(0, runtime.activeCount());
    }

    private static DebugEvent event(String id, long tsMs) {
        return new DebugEvent(id, Instant.ofEpochMilli(tsMs), tsMs, null, null, "fixture", "fixture",
                null, null, null, null, null, Map.of(), null, null);
    }

    private DebugEventsDiagnosticsController controller(DebugEventsSseRuntime runtime, RecordingEmitter emitter) {
        return new DebugEventsDiagnosticsController(emptyStore(), runtime, 300_000L, ignored -> emitter);
    }

    private DebugEventsSseRuntime runtime(int capacity, ThreadFactory threadFactory) {
        DebugEventsSseRuntime runtime = new DebugEventsSseRuntime(capacity, threadFactory);
        runtimes.add(runtime);
        return runtime;
    }

    private static DebugEventStore emptyStore() {
        DebugEventStore store = mock(DebugEventStore.class);
        when(store.list(anyInt())).thenReturn(List.of());
        return store;
    }

    private static <T extends Annotation> T annotation(Annotation[] annotations, Class<T> type) {
        return Arrays.stream(annotations)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    private static void await(BooleanSupplier condition) {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(WAIT_BOUND, () -> {
            while (!condition.getAsBoolean()) {
                Thread.yield();
            }
        });
    }

    private static class RecordingEmitter extends SseEmitter {
        private final AtomicReference<Runnable> completion = new AtomicReference<>();
        private final AtomicReference<Runnable> timeout = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final AtomicInteger errorCalls = new AtomicInteger();
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();
        private final AtomicBoolean failSend = new AtomicBoolean();
        private final AtomicBoolean failDataSend = new AtomicBoolean();
        private final List<DebugEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstSend = new CountDownLatch(1);

        RecordingEmitter(long timeoutMs) {
            super(timeoutMs);
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion.set(callback);
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout.set(callback);
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error.set(callback);
        }

        @Override
        public void send(SseEventBuilder event) throws IOException {
            sendCalls.incrementAndGet();
            firstSend.countDown();
            if (failSend.get()) {
                throw new IOException("controlled");
            }
            for (DataWithMediaType part : event.build()) {
                if (part.getData() instanceof DebugEvent data) {
                    if (failDataSend.get()) {
                        throw new IOException("controlled-data");
                    }
                    events.add(data);
                }
            }
        }

        @Override
        public void complete() {
            completeCalls.incrementAndGet();
        }

        @Override
        public void completeWithError(Throwable failure) {
            errorCalls.incrementAndGet();
            lastError.set(failure);
        }

        void fireCompletion() {
            require(completion.get(), "completion callback").run();
        }

        void fireTimeout() {
            require(timeout.get(), "timeout callback").run();
        }

        void fireError(Throwable failure) {
            require(error.get(), "error callback").accept(failure);
        }

        private static <T> T require(T value, String label) {
            if (value == null) {
                fail(label + " was not registered");
            }
            return value;
        }
    }

    private static final class ImmediateCompletionEmitter extends RecordingEmitter {
        ImmediateCompletionEmitter(long timeoutMs) {
            super(timeoutMs);
        }

        @Override
        public void onCompletion(Runnable callback) {
            callback.run();
        }
    }

    private static final class RecordingThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger interrupts = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            RecordingThread thread = new RecordingThread(
                    task,
                    "debug-events-sse-test-" + sequence.incrementAndGet(),
                    starts,
                    interrupts);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class RecordingThread extends Thread {
        private final AtomicInteger starts;
        private final AtomicInteger interrupts;

        RecordingThread(Runnable task, String name, AtomicInteger starts, AtomicInteger interrupts) {
            super(task, name);
            this.starts = starts;
            this.interrupts = interrupts;
        }

        @Override
        public void run() {
            starts.incrementAndGet();
            super.run();
        }

        @Override
        public void interrupt() {
            interrupts.incrementAndGet();
            super.interrupt();
        }
    }
}
