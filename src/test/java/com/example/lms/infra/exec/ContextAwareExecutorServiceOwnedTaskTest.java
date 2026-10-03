package com.example.lms.infra.exec;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextAwareExecutorServiceOwnedTaskTest {
    @Test
    void cancellationBeforeEnqueueStillRemovesTheActualTask() throws Exception {
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        AtomicBoolean cancelOnExecute = new AtomicBoolean();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2)) {
            @Override public void execute(Runnable task) {
                if (cancelOnExecute.get()) ((Future<?>) task).cancel(false);
                super.execute(task);
            }
        };
        AtomicInteger calls = new AtomicInteger(), completions = new AtomicInteger();
        try {
            pool.execute(() -> { started.countDown(); try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancelOnExecute.set(true);
            Future<?> task = new ContextAwareExecutorService(pool)
                    .submitCancellable(calls::incrementAndGet, completions::incrementAndGet);
            assertTrue(task.isCancelled());
            assertEquals(0, pool.getQueue().size());
            assertEquals(0, calls.get());
            assertEquals(1, completions.get());
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void logicalCancellationDoesNotReleaseRunningPermitOrLeakContext() throws Exception {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        ContextAwareExecutorService executor = new ContextAwareExecutorService(pool);
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        Semaphore permit = new Semaphore(1);
        AtomicInteger completions = new AtomicInteger();
        AtomicBoolean contextMatches = new AtomicBoolean();
        TimeBudget budget = new TimeBudget(5000);
        GuardContext guard = GuardContext.defaultContext();
        MDC.put("owner", "synthetic-owner");
        GuardContextHolder.set(guard);
        TraceStore.put("fixture.owner", "synthetic-owner");
        TimeBudgetContext.set(budget);
        try {
            Future<?> task = executor.submitCancellable(() -> {
                permit.acquire();
                try {
                    contextMatches.set(TimeBudgetContext.get() == budget && GuardContextHolder.get() == guard
                            && "synthetic-owner".equals(MDC.get("owner"))
                            && "synthetic-owner".equals(TraceStore.get("fixture.owner")));
                    started.countDown();
                    while (release.getCount() != 0) {
                        try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
                    }
                    return null;
                } finally { permit.release(); finished.countDown(); }
            }, completions::incrementAndGet);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(task.cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(task.isDone(), "waiter is terminal");
            assertEquals(1, finished.getCount(), "worker has not exited");
            assertEquals(0, permit.availablePermits(), "permit belongs to the actual worker lifetime");
            assertEquals(1, completions.get());
            assertTrue(contextMatches.get());
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(pool.submit(() -> TimeBudgetContext.get() == null
                    && GuardContextHolder.get() == null && MDC.get("owner") == null
                    && TraceStore.get("fixture.owner") == null).get(2, TimeUnit.SECONDS));
            assertEquals(1, permit.availablePermits());
            assertEquals(1, completions.get());
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS);
            TimeBudgetContext.clear(); GuardContextHolder.clear(); TraceStore.clear(); MDC.clear();
        }
    }

    @Test
    void shutdownRejectionNotifiesExactlyOnceWithoutRunningWork() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdown();
        AtomicInteger calls = new AtomicInteger(), completions = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> new ContextAwareExecutorService(pool)
                .submitCancellable(calls::incrementAndGet, completions::incrementAndGet));
        assertEquals(0, calls.get());
        assertEquals(1, completions.get());
    }
}
