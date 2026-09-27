package com.example.lms.search.provider;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HybridSearchExecutionTest {
    @AfterEach void clear() { TraceStore.clear(); }

    @Test
    void terminalBeforeQueuedStartPreventsWorkAndCompletesTheWaiterOnce() {
        List<Runnable> queue = new ArrayList<>();
        ExecutorService executor = queued(queue);
        AtomicInteger calls = new AtomicInteger();
        HybridSearchExecution scope = new HybridSearchExecution(new TimeBudget(3_000));
        Future<Integer> future = scope.submit(executor, calls::incrementAndGet);
        scope.close();
        scope.close();
        queue.get(0).run();
        assertEquals(0, calls.get());
        assertTrue(future.isCancelled());
        assertFalse(future.cancel(true));
        assertEquals(1, TraceStore.get("web.hybrid.execution.waitersCompleted"));
        assertEquals(1, TraceStore.get("web.hybrid.execution.cancellationRequests"));
        assertEquals(0, TraceStore.get("web.hybrid.execution.workersRunningAtReturn"));
        assertFalse(executor.isShutdown(), "scope does not own the shared executor");
    }

    @Test
    void completedBeforeCloseDoesNotAcquireCancellationOrDuplicateCompletion() throws Exception {
        List<Runnable> queue = new ArrayList<>();
        HybridSearchExecution scope = new HybridSearchExecution(new TimeBudget(3_000));
        Future<String> future = scope.submit(queued(queue), () -> "result");
        queue.get(0).run();
        assertEquals("result", future.get());
        scope.close();
        scope.close();
        assertEquals(1, TraceStore.get("web.hybrid.execution.waitersCompleted"));
        assertEquals(1, TraceStore.get("web.hybrid.execution.workersFinishedAtReturn"));
        assertEquals(0, TraceStore.get("web.hybrid.execution.cancellationRequests"));
    }

    @Test
    void requestCancellationCannotCancelASharedOwnerOrReleaseItsResourceEarly() throws Exception {
        CompletableFuture<String> sharedOwner = new CompletableFuture<>();
        CompletableFuture<String> otherWaiter = sharedOwner.thenApply(value -> value);
        AtomicBoolean resourceReleased = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HybridSearchExecution scope = new HybridSearchExecution(new TimeBudget(3_000));
        try {
            Future<String> waiter = scope.submit(executor, () -> {
                running.countDown();
                try { return sharedOwner.thenApply(value -> value).get(5, TimeUnit.SECONDS); }
                finally { resourceReleased.set(true); finished.countDown(); }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            scope.close();
            assertTrue(waiter.isCancelled());
            assertFalse(sharedOwner.isDone());
            assertFalse(resourceReleased.get());
            assertEquals(1, TraceStore.get("web.hybrid.execution.workersRunningAtReturn"));
            sharedOwner.complete("shared result");
            assertEquals("shared result", otherWaiter.get(5, TimeUnit.SECONDS));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(resourceReleased.get());
            assertEquals(1, TraceStore.get("web.hybrid.execution.workersRunningAtReturn"),
                    "terminal evidence remains a snapshot");
        } finally {
            sharedOwner.complete("cleanup");
            scope.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void workerCannotRewriteProtectedContextOrMutateInternalMetadataAfterClose() throws Exception {
        TraceStore.put("sessionId", "synthetic-session");
        TraceStore.put("ctx.memory", Map.of("fixture", "read-only"));
        TraceStore.putInternal("fixture.internal", "original");
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> retained =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<Runnable> queue = new ArrayList<>();
        HybridSearchExecution scope = new HybridSearchExecution(new TimeBudget(3_000));
        Future<?> future = scope.submit(queued(queue), () -> {
            retained.set(TraceStore.context());
            TraceStore.put("sessionId", "changed");
            TraceStore.put("ctx.memory", Map.of());
            return null;
        });
        queue.get(0).run();
        future.get();
        scope.close();
        assertEquals("synthetic-session", TraceStore.get("sessionId"));
        assertEquals(Map.of("fixture", "read-only"), TraceStore.get("ctx.memory"));
        Map<String, Object> caller = TraceStore.context();
        try {
            TraceStore.installContext(retained.get());
            TraceStore.put("fixture.internal", "late");
        } finally { TraceStore.installContext(caller); }
        assertEquals("original", TraceStore.get("fixture.internal"));
        assertFalse(TraceStore.getAll().containsKey("fixture.internal"));
    }

    private static ExecutorService queued(List<Runnable> queue) {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { queue.add(command); }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
    }
}
