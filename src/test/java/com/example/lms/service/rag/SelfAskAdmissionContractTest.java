package com.example.lms.service.rag;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.provider.WebSearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelfAskAdmissionContractTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancelledWrappedTaskReleasesQueueSlot(boolean shield) throws Exception {
        try (Fixture f = new Fixture(new ThreadPoolExecutor.AbortPolicy(), shield)) {
            LinkedBlockingQueue<Integer> completions = new LinkedBlockingQueue<>();
            Future<?> attempt = f.submit(completions);
            assertEquals(1, f.pool.getQueue().size());
            assertTrue(attempt.cancel(true));
            assertEquals(0, f.pool.getQueue().size(), "cancel must remove the actual contextual queue item");
            assertEquals(7, completions.poll());
            assertNull(completions.poll(), "one terminal completion");
            assertEquals(0, f.providerCalls.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rejectedAttemptCompletesOnceAsExecutorSaturated(boolean shield) throws Exception {
        try (Fixture f = new Fixture(new ThreadPoolExecutor.AbortPolicy(), shield)) {
            f.pool.execute(() -> {});
            LinkedBlockingQueue<Integer> completions = new LinkedBlockingQueue<>();
            Future<?> attempt = assertDoesNotThrow(() -> f.submit(completions));
            assertEquals("executor-saturated", failureClass(attempt.get(1, TimeUnit.SECONDS)));
            assertEquals(7, completions.poll());
            assertNull(completions.poll());
            assertEquals(0, f.providerCalls.get(), "local rejection is no remote attempt");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void saturatedCallerRunsNeverInvokesProviderOnSubmitter(boolean shield) throws Exception {
        try (Fixture f = new Fixture(new ThreadPoolExecutor.CallerRunsPolicy(), shield)) {
            f.pool.execute(() -> {});
            LinkedBlockingQueue<Integer> completions = new LinkedBlockingQueue<>();
            Future<?> attempt = f.submit(completions);
            assertEquals(0, f.providerCalls.get(), "provider I/O must not run on the coordinator");
            assertEquals("executor-saturated", failureClass(attempt.get(1, TimeUnit.SECONDS)));
            assertEquals(7, completions.poll());
            assertNull(completions.poll());
        }
    }

    @Test
    void queueResidenceConsumesTheOriginalRequestBudget() throws Exception {
        var budget = new com.abandonware.ai.addons.budget.TimeBudget(5000);
        com.abandonware.ai.addons.budget.TimeBudgetContext.set(budget);
        try (Fixture f = new Fixture(new ThreadPoolExecutor.AbortPolicy(), true)) {
            var completions = new LinkedBlockingQueue<Integer>();
            Future<?> attempt = f.submit(completions);
            budget.cancel();
            f.release.countDown();
            assertEquals("deadline_exhausted", failureClass(attempt.get(2, TimeUnit.SECONDS)));
            assertEquals(0, f.providerCalls.get());
            assertEquals(7, completions.poll(1, TimeUnit.SECONDS));
            assertNull(completions.poll());
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
        }
    }

    private static String failureClass(Object attempt) throws Exception {
        Method method = attempt.getClass().getDeclaredMethod("failureClass");
        method.setAccessible(true);
        return (String) method.invoke(attempt);
    }

    private static final class Fixture implements AutoCloseable {
        final ThreadPoolExecutor pool;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger providerCalls = new AtomicInteger();
        final SelfAskWebSearchRetriever retriever;
        Fixture(RejectedExecutionHandler policy, boolean shield) throws Exception {
            pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                    runnable -> { Thread t = new Thread(runnable, "f03-search"); t.setDaemon(true); return t; }, policy);
            CountDownLatch started = new CountDownLatch(1);
            pool.execute(() -> { started.countDown(); try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            WebSearchProvider provider = mock(WebSearchProvider.class);
            when(provider.isEnabled()).thenReturn(true);
            when(provider.search(anyString(), anyInt())).thenAnswer(call -> {
                providerCalls.incrementAndGet(); return java.util.List.of("synthetic result"); });
            retriever = new SelfAskWebSearchRetriever(provider, null, null, null);
            ExecutorService contextual = new ContextAwareExecutorService(pool);
            if (shield) contextual = new ai.abandonware.nova.boot.exec.CancelShieldExecutorService(contextual, "searchIoExecutor");
            ReflectionTestUtils.setField(retriever, "searchExecutor", contextual);
        }
        Future<?> submit(BlockingQueue<Integer> completions) throws Exception {
            Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                    "submitSearchAttempt", String.class, int.class, int.class, BlockingQueue.class);
            method.setAccessible(true);
            return (Future<?>) method.invoke(retriever, "synthetic query", 1, 7, completions);
        }
        public void close() throws Exception {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
            com.example.lms.search.TraceStore.clear();
        }
    }
}
