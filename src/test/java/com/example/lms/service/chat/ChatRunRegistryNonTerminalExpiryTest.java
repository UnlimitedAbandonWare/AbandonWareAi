package com.example.lms.service.chat;

import com.example.lms.dto.ChatStreamEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRunRegistryNonTerminalExpiryTest {

    @Test
    void unacknowledgedCancellationAtomicallyReplaysOrderedProvidedTerminalEvidence() throws Exception {
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult run = harness.registry.beginOrJoin(40L);
            List<ServerSentEvent<ChatStreamEvent>> terminalEvidence = List.of(
                    streamEvent(ChatStreamEvent.selectionEntropy(null)),
                    streamEvent(ChatStreamEvent.status(ChatStreamEvent.StatusSignal.of(
                            "stream", "cancelled", "stream cancelled", null, null, true))),
                    streamEvent(ChatStreamEvent.transformer(List.of())));

            assertTrue(harness.registry.cancelIfUnacknowledged(
                    run.context(), terminalEvidence));

            List<ServerSentEvent<ChatStreamEvent>> replay = harness.registry
                    .attachExact(40L, run.context().clientToken())
                    .orElseThrow()
                    .collectList()
                    .block();
            assertNotNull(replay);
            assertEquals(List.of("selection_entropy", "status", "transformer"),
                    replay.stream().map(ServerSentEvent::event).toList());
            assertEquals("cancelled", replay.get(1).data().statusSignal().code());
            assertEquals(ChatRunRegistry.Status.CANCELLED,
                    harness.registry.describeExact(40L, run.context().clientToken())
                            .orElseThrow().status());
            assertEquals(1, harness.registry.describeExact(40L, run.context().clientToken())
                    .orElseThrow().outcome().terminalEventCount());
        }
    }

    @Test
    void acknowledgedRunRejectsProvidedCancellationEvidenceWithoutAppendingAnything() {
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult run = harness.registry.beginOrJoin(41L);
            assertTrue(harness.registry.acknowledgeExact(
                    41L, run.context().clientToken()));
            List<ServerSentEvent<ChatStreamEvent>> replay = new CopyOnWriteArrayList<>();
            Disposable replaySubscription = harness.registry
                    .attachExact(41L, run.context().clientToken())
                    .orElseThrow()
                    .subscribe(replay::add);

            try {
                assertFalse(harness.registry.cancelIfUnacknowledged(
                        run.context(), List.of(streamEvent(ChatStreamEvent.selectionEntropy(null)))));

                assertTrue(replay.isEmpty());
                assertEquals(ChatRunRegistry.Status.RUNNING,
                        harness.registry.describeExact(41L, run.context().clientToken())
                                .orElseThrow().status());
            } finally {
                replaySubscription.dispose();
            }
        }
    }

    @Test
    void idleRunningRunTimesOutDisposesWorkerAndPreservesReplayUntilTerminalTtl() throws Exception {
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult first = harness.registry.beginOrJoin(42L);
            String oldToken = first.context().clientToken();
            TrackingDisposable handle = new TrackingDisposable();
            assertTrue(first.context().registerCancellationHandle(handle));
            List<ServerSentEvent<ChatStreamEvent>> terminalEvents = new CopyOnWriteArrayList<>();
            CountDownLatch completed = new CountDownLatch(1);
            harness.registry.attach(first.context())
                    .doOnNext(terminalEvents::add)
                    .doOnComplete(completed::countDown)
                    .subscribe();

            harness.clock.advanceSeconds(31);
            harness.registry.runStaleSweepNow();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(1, handle.disposeCalls.get());
            assertEquals(1, terminalEvents.size());
            ChatRunRegistry.RunView timedOut = harness.registry.describeExact(42L, oldToken).orElseThrow();
            assertEquals(ChatRunRegistry.Status.CANCELLED, timedOut.status());
            assertEquals("timed_out", timedOut.outcome().generationOutcome());
            assertEquals("stale_timeout", timedOut.outcome().terminalReason());
            assertEquals("stale_timeout", timedOut.outcome().finalDeliveryFailureReason());
            assertEquals(1, timedOut.outcome().terminalEventCount());
            assertTrue(harness.registry.attachExact(42L, oldToken).isPresent());

            ChatRunRegistry.BeginResult replacement = harness.registry.beginOrJoin(42L);
            assertTrue(replacement.owner());
            assertNotEquals(oldToken, replacement.context().clientToken());
            assertFalse(first.context().permitsEmission());
            assertFalse(harness.registry.markDone(first.context()));
            assertEquals(1, harness.scheduler.oneShots.size());

            harness.scheduler.runOneShots();

            assertTrue(harness.registry.attachExact(42L, oldToken).isEmpty());
            assertEquals(replacement.context().clientToken(), harness.registry.currentRunToken(42L).orElseThrow());
            assertTrue(harness.registry.isRunning(42L));
        }
    }

    @Test
    void idleCommittingRunCannotPersistEmitOrCompleteAfterTimeout() {
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult first = harness.registry.beginOrJoin(43L);
            assertTrue(first.context().tryBeginTranscriptCommit());

            harness.clock.advanceSeconds(31);
            harness.registry.runStaleSweepNow();

            assertFalse(first.context().markGenerationSucceeded());
            assertFalse(first.context().markPersisted());
            assertFalse(first.context().claimTerminalEvent("late_terminal"));
            assertFalse(first.context().recordFinalEmit("ok", false));
            assertFalse(first.context().recordTerminalWithoutFinal("late"));
            assertFalse(harness.registry.emit(first.context(), progressEvent()));
            assertFalse(harness.registry.markDone(first.context()));
            ChatRunRegistry.BeginResult replacement = harness.registry.beginOrJoin(43L);
            assertTrue(replacement.owner());
            assertFalse(first.context().sameRun(replacement.context()));
            assertTrue(replacement.context().permitsEmission());
        }
    }

    @Test
    void acceptedProgressExtendsIdleDeadlineAndBackwardClockCannotExpireRun() {
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult run = harness.registry.beginOrJoin(44L);

            harness.clock.advanceSeconds(29);
            assertTrue(harness.registry.emit(run.context(), progressEvent()));
            harness.clock.advanceSeconds(2);
            harness.registry.runStaleSweepNow();
            assertTrue(run.context().permitsEmission(), "creation age must not override recent progress");

            harness.clock.setMillis(1L);
            harness.registry.runStaleSweepNow();
            assertTrue(run.context().permitsEmission(), "clock rollback must not create a stale age");

            harness.clock.setMillis(60_001L);
            harness.registry.runStaleSweepNow();
            assertFalse(run.context().permitsEmission());
            assertEquals("stale_timeout",
                    harness.registry.describeExact(44L, run.context().clientToken())
                            .orElseThrow().outcome().terminalReason());
        }
    }

    @Test
    void staleSweepBreaksStalledCancellingWithoutLateDuplicateTerminal() throws Exception {
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult first = harness.registry.beginOrJoin(45L);
            TrackingDisposable handle = new TrackingDisposable();
            assertTrue(first.context().registerCancellationHandle(handle));
            List<ServerSentEvent<ChatStreamEvent>> terminalEvents = new CopyOnWriteArrayList<>();
            CountDownLatch completed = new CountDownLatch(1);
            harness.registry.attach(first.context())
                    .doOnNext(terminalEvents::add)
                    .doOnComplete(completed::countDown)
                    .subscribe();
            CountDownLatch actionEntered = new CountDownLatch(1);
            CountDownLatch releaseAction = new CountDownLatch(1);
            Future<Boolean> cancellation = canceller.submit(() -> harness.registry.cancelExact(
                    45L,
                    first.context().clientToken(),
                    () -> awaitLatch(actionEntered, releaseAction)));

            assertTrue(actionEntered.await(2, TimeUnit.SECONDS));
            assertEquals(ChatRunRegistry.Status.CANCELLING,
                    harness.registry.describeExact(45L, first.context().clientToken()).orElseThrow().status());
            assertEquals(1, handle.disposeCalls.get());

            harness.clock.advanceSeconds(31);
            harness.registry.runStaleSweepNow();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            ChatRunRegistry.BeginResult replacement = harness.registry.beginOrJoin(45L);
            assertTrue(replacement.owner());
            releaseAction.countDown();
            assertFalse(cancellation.get(2, TimeUnit.SECONDS));
            assertEquals(1, handle.disposeCalls.get());
            assertEquals(1, terminalEvents.size());
            assertEquals(1, harness.scheduler.oneShots.size());
            assertEquals(replacement.context().clientToken(), harness.registry.currentRunToken(45L).orElseThrow());
        } finally {
            canceller.shutdownNow();
            assertTrue(canceller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutConfigurationFallsBackAndClampsWithoutChangingTerminalTtl() {
        assertNormalizedTimeout(-1, 1_800, 30);
        assertNormalizedTimeout(0, 1_800, 30);
        assertNormalizedTimeout(1, 30, 15);
        assertNormalizedTimeout(100_000, 86_400, 30);
    }

    @Test
    void claimedButNotYetEmittedTerminalCannotRaceTimeoutIntoDuplicateTerminalEvents() throws Exception {
        try (Harness harness = new Harness()) {
            ChatRunRegistry.BeginResult run = harness.registry.beginOrJoin(46L);
            List<ServerSentEvent<ChatStreamEvent>> terminalEvents = new CopyOnWriteArrayList<>();
            CountDownLatch completed = new CountDownLatch(1);
            harness.registry.attach(run.context())
                    .doOnNext(terminalEvents::add)
                    .doOnComplete(completed::countDown)
                    .subscribe();
            assertTrue(run.context().claimTerminalEvent("delivery_pending"));

            harness.clock.advanceSeconds(31);
            harness.registry.runStaleSweepNow();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(terminalEvents.isEmpty(), "an already claimed terminal slot cannot be emitted twice");
            assertFalse(run.context().recordFinalEmit("ok", false));
            assertFalse(harness.registry.markDone(run.context()));
            ChatRunRegistry.RunOutcomeView outcome = harness.registry
                    .describeExact(46L, run.context().clientToken())
                    .orElseThrow()
                    .outcome();
            assertEquals("timed_out", outcome.generationOutcome());
            assertEquals("stale_timeout", outcome.terminalReason());
            assertEquals(0, outcome.terminalEventCount());
            assertEquals(1, outcome.duplicateSuppressed());
            assertEquals(1, harness.scheduler.oneShots.size());
        }
    }

    @Test
    void postConstructStartsOneSweepAndDestroyRespectsExecutorOwnership() throws Exception {
        assertNotNull(ChatRunRegistry.class.getDeclaredMethod("startStaleSweep")
                .getAnnotation(PostConstruct.class));
        assertNotNull(ChatRunRegistry.class.getDeclaredMethod("shutdown")
                .getAnnotation(PreDestroy.class));

        ManualScheduledExecutor injectedExecutor = new ManualScheduledExecutor();
        ChatRunRegistry injected = registry(injectedExecutor, new MutableClock(), false);
        injected.startStaleSweep();
        injected.startStaleSweep();
        assertEquals(1, injectedExecutor.fixedDelayTasks.size());
        ManualScheduledTask injectedSweep = injectedExecutor.fixedDelayTasks.get(0);

        injected.shutdown();

        assertTrue(injectedSweep.isCancelled());
        assertFalse(injectedExecutor.isShutdown());
        injectedExecutor.shutdownNow();

        ManualScheduledExecutor ownedExecutor = new ManualScheduledExecutor();
        ChatRunRegistry owned = registry(ownedExecutor, new MutableClock(), true);
        owned.startStaleSweep();
        ManualScheduledTask ownedSweep = ownedExecutor.fixedDelayTasks.get(0);

        owned.shutdown();

        assertTrue(ownedSweep.isCancelled());
        assertTrue(ownedExecutor.isShutdown());
    }

    @Test
    void unexpectedSweepFailureDoesNotCancelFutureRuns() {
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock = () -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("controlled");
            }
            return 0L;
        };
        ChatRunRegistry registry = registry(executor, clock, false);
        try {
            registry.startStaleSweep();
            ManualScheduledTask sweep = executor.fixedDelayTasks.get(0);

            sweep.runOnce();
            sweep.runOnce();

            assertFalse(sweep.isCancelled());
            assertFalse(sweep.isDone());
            assertTrue(calls.get() >= 2);
        } finally {
            registry.shutdown();
            executor.shutdownNow();
        }
    }

    private static ChatRunRegistry registry(
            ManualScheduledExecutor executor,
            LongSupplier clock,
            boolean ownsExecutor) {
        ChatRunRegistry registry = new ChatRunRegistry(executor, clock, ownsExecutor);
        registry.replayCapacity = 16;
        registry.ttlSeconds = 300;
        registry.inflightIdleTimeoutSeconds = 30;
        return registry;
    }

    private static void assertNormalizedTimeout(
            int configuredSeconds,
            int expectedTimeoutSeconds,
            int expectedSweepSeconds) {
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        MutableClock clock = new MutableClock();
        ChatRunRegistry registry = registry(executor, clock, false);
        registry.inflightIdleTimeoutSeconds = configuredSeconds;
        try {
            registry.startStaleSweep();
            assertEquals(1, executor.fixedDelayTasks.size());
            assertEquals(expectedSweepSeconds,
                    executor.fixedDelayTasks.get(0).getDelay(TimeUnit.SECONDS));
            ChatRunRegistry.BeginResult run = registry.beginOrJoin(500L);

            clock.advanceSeconds(expectedTimeoutSeconds - 1L);
            registry.runStaleSweepNow();
            assertTrue(run.context().permitsEmission());

            clock.advanceSeconds(1L);
            registry.runStaleSweepNow();
            assertFalse(run.context().permitsEmission());
            assertEquals(300, registry.ttlSeconds);
        } finally {
            registry.shutdown();
            executor.shutdownNow();
        }
    }

    private static ServerSentEvent<ChatStreamEvent> progressEvent() {
        ChatStreamEvent.StatusSignal signal = ChatStreamEvent.StatusSignal.of(
                "stream", "progress", "progress", null, null, false);
        return streamEvent(ChatStreamEvent.status(signal));
    }

    private static ServerSentEvent<ChatStreamEvent> streamEvent(ChatStreamEvent payload) {
        return ServerSentEvent.<ChatStreamEvent>builder(payload)
                .event(payload.type())
                .build();
    }

    private static void awaitLatch(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            assertTrue(release.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("controlled interruption", interrupted);
        }
    }

    private static ChatRunRegistry registry(
            ManualScheduledExecutor executor,
            MutableClock clock,
            boolean ownsExecutor) {
        return registry(executor, (LongSupplier) clock, ownsExecutor);
    }

    private static final class Harness implements AutoCloseable {
        private final ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
        private final MutableClock clock = new MutableClock();
        private final ChatRunRegistry registry = registry(scheduler, clock, false);

        private Harness() {
            registry.startStaleSweep();
        }

        @Override
        public void close() {
            registry.shutdown();
            scheduler.shutdownNow();
        }
    }

    private static final class MutableClock implements LongSupplier {
        private final AtomicLong millis = new AtomicLong();

        @Override
        public long getAsLong() {
            return millis.get();
        }

        void advanceSeconds(long seconds) {
            millis.addAndGet(TimeUnit.SECONDS.toMillis(seconds));
        }

        void setMillis(long value) {
            millis.set(value);
        }
    }

    private static final class TrackingDisposable implements Disposable {
        private final AtomicInteger disposeCalls = new AtomicInteger();

        @Override
        public void dispose() {
            disposeCalls.incrementAndGet();
        }

        @Override
        public boolean isDisposed() {
            return disposeCalls.get() > 0;
        }
    }

    private static final class ManualScheduledExecutor extends ScheduledThreadPoolExecutor {
        private final List<ManualScheduledTask> oneShots = new CopyOnWriteArrayList<>();
        private final List<ManualScheduledTask> fixedDelayTasks = new CopyOnWriteArrayList<>();

        ManualScheduledExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualScheduledTask task = new ManualScheduledTask(command, unit.toMillis(delay), false);
            oneShots.add(task);
            return task;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command,
                long initialDelay,
                long delay,
                TimeUnit unit) {
            ManualScheduledTask task = new ManualScheduledTask(command, unit.toMillis(delay), true);
            fixedDelayTasks.add(task);
            return task;
        }

        void runOneShots() {
            List<ManualScheduledTask> snapshot = new ArrayList<>(oneShots);
            snapshot.forEach(ManualScheduledTask::runOnce);
        }

        @Override
        public List<Runnable> shutdownNow() {
            oneShots.forEach(task -> task.cancel(false));
            fixedDelayTasks.forEach(task -> task.cancel(false));
            return super.shutdownNow();
        }
    }

    private static final class ManualScheduledTask implements ScheduledFuture<Object> {
        private final Runnable command;
        private final long delayMillis;
        private final boolean periodic;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();

        ManualScheduledTask(Runnable command, long delayMillis, boolean periodic) {
            this.command = command;
            this.delayMillis = delayMillis;
            this.periodic = periodic;
        }

        void runOnce() {
            if (cancelled.get()) {
                return;
            }
            command.run();
            if (!periodic) {
                done.set(true);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return done.get() || cancelled.get();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            if (cancelled.get()) {
                throw new CancellationException();
            }
            if (!done.get()) {
                throw new IllegalStateException("manual task has not run");
            }
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return get();
        }
    }
}
