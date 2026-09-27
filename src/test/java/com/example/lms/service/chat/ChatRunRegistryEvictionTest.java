package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRunRegistryEvictionTest {

    @Test
    void deletionFencePublicationIsVolatile() throws Exception {
        int modifiers = ChatRunRegistry.Run.class
                .getDeclaredField("deletionFence")
                .getModifiers();

        assertTrue(Modifier.isVolatile(modifiers),
                "beginOrJoin reads the deletion fence without the run gate, so publication must be volatile");
    }

    @Test
    void deletionWaitsForAdmittedTerminalSideEffectAndRejectsLateWork() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch sideEffectEntered = new CountDownLatch(1);
        CountDownLatch releaseSideEffect = new CountDownLatch(1);
        AtomicBoolean effectCompleted = new AtomicBoolean();
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;
            ChatRunExecutionContext run = registry.beginOrJoin(41L).context();
            assertTrue(run.tryBeginCommit());

            FutureTask<Boolean> terminalWork = new FutureTask<>(() ->
                    run.runTerminalSideEffect(() -> {
                        sideEffectEntered.countDown();
                        try {
                            if (!releaseSideEffect.await(2, TimeUnit.SECONDS)) {
                                throw new AssertionError("terminal side effect release timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        effectCompleted.set(true);
                    }));
            Thread terminalThread = new Thread(terminalWork, "terminal-side-effect-test");
            terminalThread.start();
            assertTrue(sideEffectEntered.await(2, TimeUnit.SECONDS));

            FutureTask<Void> deletion = new FutureTask<>(() -> {
                registry.cancelSessionForDeletion(41L);
                return null;
            });
            Thread deletionThread = new Thread(deletion, "session-deletion-test");
            deletionThread.start();

            assertTrue(awaitBlocked(deletionThread, 2, TimeUnit.SECONDS),
                    "deletion must wait at the admitted terminal side-effect gate");
            assertFalse(effectCompleted.get());
            releaseSideEffect.countDown();

            assertTrue(terminalWork.get(2, TimeUnit.SECONDS));
            deletion.get(2, TimeUnit.SECONDS);
            assertTrue(effectCompleted.get());
            assertFalse(run.runTerminalSideEffect(() -> { }),
                    "no terminal side effect may start after deletion wins the fence");
        } finally {
            releaseSideEffect.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deletionFenceCancelsCommittingRunAndRejectsReplacementUntilEviction() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;
            ChatRunRegistry.BeginResult run = registry.beginOrJoin(42L);
            String token = run.context().clientToken();
            assertTrue(run.context().tryBeginCommit());

            registry.cancelSessionForDeletion(42L);

            assertTrue(run.context().isCancellationRequested());
            assertFalse(run.context().tryBeginTranscriptCommit());
            ChatRunRegistry.BeginResult blockedReplacement = registry.beginOrJoin(42L);
            assertFalse(blockedReplacement.owner(),
                    "a deleted session id must stay fenced for the terminal retention window");
            assertEquals(token, blockedReplacement.context().clientToken());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deletionWithoutAnActiveRunStillInstallsABoundedFence() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;

            registry.cancelSessionForDeletion(43L);

            assertFalse(registry.beginOrJoin(43L).owner());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deletionReturnsBoundedFailureWhileAnotherCancellationActionIsStillRunning() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch cancellationActionEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellationAction = new CountDownLatch(1);
        FutureTask<Boolean> cancellation = null;
        FutureTask<ChatRunRegistry.SessionDeletionFenceException> deletion = null;
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;
            registry.deletionCancelWaitMillis = 25L;
            ChatRunRegistry.BeginResult run = registry.beginOrJoin(44L);

            cancellation = new FutureTask<>(() -> registry.cancelExact(
                    44L,
                    run.context().clientToken(),
                    () -> {
                        cancellationActionEntered.countDown();
                        try {
                            if (!releaseCancellationAction.await(2, TimeUnit.SECONDS)) {
                                throw new AssertionError("cancellation action release timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));
            Thread cancellationThread = new Thread(cancellation, "exact-cancel-action-test");
            cancellationThread.start();
            assertTrue(cancellationActionEntered.await(2, TimeUnit.SECONDS));

            deletion = new FutureTask<>(() -> {
                try {
                    registry.cancelSessionForDeletion(44L);
                    return null;
                } catch (ChatRunRegistry.SessionDeletionFenceException failure) {
                    return failure;
                }
            });
            Thread deletionThread = new Thread(deletion, "bounded-session-deletion-test");
            deletionThread.start();

            ChatRunRegistry.SessionDeletionFenceException failure =
                    deletion.get(1, TimeUnit.SECONDS);
            assertNotNull(failure, "deletion must not report success while cancellation is unfinished");
            assertEquals(ChatRunRegistry.SESSION_DELETION_CANCEL_WAIT_TIMED_OUT, failure.reason());
            assertFalse(registry.beginOrJoin(44L).owner(),
                    "a timed-out delete attempt must retain its deletion fence");
        } finally {
            releaseCancellationAction.countDown();
            if (cancellation != null) {
                cancellation.get(2, TimeUnit.SECONDS);
            }
            if (deletion != null && !deletion.isDone()) {
                deletion.get(2, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void terminalRunsCompleteAttachedReplaySubscribers() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;

            ChatRunRegistry.BeginResult completedRun = registry.beginOrJoin(42L);
            CountDownLatch done = new CountDownLatch(1);
            registry.attach(completedRun.context()).doOnComplete(done::countDown).subscribe();
            registry.markDone(completedRun.context());

            assertTrue(done.await(1, TimeUnit.SECONDS),
                    "completed runs must close replay subscribers after the final event");

            ChatRunRegistry.BeginResult cancelledRun = registry.beginOrJoin(43L);
            CountDownLatch cancelled = new CountDownLatch(1);
            registry.attach(cancelledRun.context()).doOnComplete(cancelled::countDown).subscribe();
            registry.cancelExact(43L, cancelledRun.context().clientToken());

            assertTrue(cancelled.await(1, TimeUnit.SECONDS),
                    "cancelled runs must close replay subscribers after the terminal event");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evictionUsesOneSharedSchedulerThread() {
        AtomicInteger createdThreads = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "test-chat-run-evictor");
            thread.setDaemon(true);
            createdThreads.incrementAndGet();
            return thread;
        });
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;

            for (long id = 1L; id <= 8L; id++) {
                ChatRunRegistry.BeginResult run = registry.beginOrJoin(id);
                registry.markDone(run.context());
            }

            assertTrue(createdThreads.get() <= 1, "eviction should not create one thread per run");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startAfterCancelledRunCreatesFreshSinkImmediately() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;

            ChatRunRegistry.BeginResult cancelledRun = registry.beginOrJoin(42L);
            registry.cancelExact(42L, cancelledRun.context().clientToken());

            ChatRunRegistry.BeginResult nextRun = registry.beginOrJoin(42L);

            assertNotEquals(cancelledRun.context().clientToken(), nextRun.context().clientToken(),
                    "a new user message after Stop must not attach to the cancelled replay sink");
            assertFalse(registry.markDone(cancelledRun.context()));
            java.util.concurrent.atomic.AtomicBoolean lateHandleDisposed = new java.util.concurrent.atomic.AtomicBoolean();
            assertFalse(cancelledRun.context().registerCancellationHandle(() -> lateHandleDisposed.set(true)));
            assertTrue(lateHandleDisposed.get());
            assertFalse(registry.cancelExact(42L, cancelledRun.context().clientToken()));
            assertFalse(cancelledRun.context().permitsEmission());
            assertTrue(nextRun.context().permitsEmission());
            assertTrue(nextRun.context().admitCall(() -> {}));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void markDoneDoesNotOverwriteExplicitCancellation() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;

            ChatRunRegistry.BeginResult run = registry.beginOrJoin(42L);
            registry.cancelExact(42L, run.context().clientToken());
            registry.markDone(run.context());

            assertTrue(registry.isCancelled(42L), "cancelled runs must stay cancelled until eviction");
            assertFalse(registry.isRunning(42L), "cancelled runs must not be reported as running");
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean awaitBlocked(Thread thread, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED) {
                return true;
            }
            if (state == Thread.State.TERMINATED) {
                return false;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
