package com.example.lms.search.provider;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridWebSearchAttemptOwnershipTest {
    @AfterEach
    void clear() {
        TimeBudgetContext.clear();
        TraceContext.cleanupCurrentThread();
        TraceStore.clear();
    }

    @Test
    void saturatedCallerRunsExecutorDoesNotRunProviderIoOnRequestThread() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        ExecutorService requestThread = Executors.newSingleThreadExecutor(
                work -> new Thread(work, "hybrid-saturation-request"));
        BraveSearchService brave = brave();
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            assertEquals("hybrid-saturation-request", Thread.currentThread().getName());
            providerEntered.countDown();
            assertTrue(releaseProvider.await(5, TimeUnit.SECONDS));
            return BraveSearchResult.ok(List.of(), 1);
        });
        try {
            pool.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            HybridWebSearchProvider provider = provider(brave, new ContextAwareExecutorService(pool));
            Future<List<String>> result = requestThread.submit(() -> {
                TimeBudgetContext.set(new TimeBudget(3_000));
                try {
                    return provider.search("합성 검색", 3);
                } finally {
                    TimeBudgetContext.clear();
                    TraceContext.cleanupCurrentThread();
                    TraceStore.clear();
                }
            });

            assertFalse(providerEntered.await(300, TimeUnit.MILLISECONDS),
                    "saturated CallerRuns must not start provider I/O on the request thread");
            assertEquals(List.of(), result.get(500, TimeUnit.MILLISECONDS));
            verify(brave, never()).searchWithMeta(anyString(), anyInt());
        } finally {
            releaseProvider.countDown();
            releaseWorker.countDown();
            requestThread.shutdownNow();
            pool.shutdownNow();
            assertTrue(requestThread.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeSpentInQueueCannotAuthorizeAProviderAfterExpiry() {
        AtomicLong remaining = new AtomicLong(600);
        TimeBudget parent = budget(remaining);
        TimeBudgetContext.set(parent);
        BraveSearchService brave = brave();
        ExecutorService queue = new InlineExecutor() {
            @Override public void execute(Runnable command) {
                remaining.set(0);
                command.run();
            }
        };
        assertEquals(List.of(), provider(brave, queue).search("합성 검색", 3));
        verify(brave, never()).searchWithMeta(anyString(), anyInt());
        assertSame(parent, TimeBudgetContext.get());
    }

    @Test
    void waitCompletionDoesNotClaimWorkerCompletionOrAcceptLateTraceWrites() throws Exception {
        AtomicLong remaining = new AtomicLong(600);
        TimeBudget parent = budget(remaining);
        TimeBudgetContext.set(parent);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        BraveSearchService brave = brave();
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            TraceStore.put("web.brave.fixture.phase", "started");
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                TraceStore.put("web.brave.fixture.phase", "late");
                TraceStore.append("web.brave.fixture.events", "late");
                TraceStore.inc("web.brave.fixture.count");
                return BraveSearchResult.ok(List.of("late result"), 1);
            } finally {
                workerExited.countDown();
            }
        });
        ExecutorService actualWorker = Executors.newSingleThreadExecutor();
        ExecutorService queued = new InlineExecutor() {
            @Override public void execute(Runnable command) {
                actualWorker.execute(command);
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    remaining.set(0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        };
        try {
            List<String> result = provider(brave, new ContextAwareExecutorService(queued)).search("합성 검색", 3);
            assertEquals(List.of(), result);
            assertEquals(1, workerExited.getCount(), "request wait ended while the worker is still alive");
            assertEquals("started", TraceStore.get("web.brave.fixture.phase"));
            release.countDown();
            assertTrue(workerExited.await(5, TimeUnit.SECONDS));
            actualWorker.shutdown();
            assertTrue(actualWorker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("started", TraceStore.get("web.brave.fixture.phase"));
            assertNull(TraceStore.get("web.brave.fixture.events"));
            assertNull(TraceStore.get("web.brave.fixture.count"));
            assertEquals(List.of(), result);
        } finally {
            release.countDown();
            actualWorker.shutdownNow();
            assertTrue(actualWorker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualWorkerReceivesTheSameBudgetAndRestoresItsOwnContext() throws Exception {
        TimeBudget parent = new TimeBudget(3_000);
        TimeBudgetContext.set(parent);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        BraveSearchService brave = brave();
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            assertSame(parent, TimeBudgetContext.get());
            return BraveSearchResult.ok(List.of("completed result"), 1);
        });
        try {
            assertEquals(List.of("completed result"), provider(brave, pool).search("합성 검색", 3));
            assertNull(pool.submit(TimeBudgetContext::get).get(5, TimeUnit.SECONDS));
            assertSame(parent, TimeBudgetContext.get());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void directProviderCallbackAlsoCannotPublishAfterTheSearchReturns() {
        BraveSearchService brave = brave();
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> callbackTrace =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            callbackTrace.set(TraceStore.context());
            TraceStore.put("web.brave.fixture.phase", "returned");
            return BraveSearchResult.ok(List.of("completed result"), 1);
        });
        HybridWebSearchProvider provider = provider(brave, new InlineExecutor());
        ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", true);
        assertEquals(List.of("completed result"), provider.search("synthetic search", 3));
        callbackTrace.get().put("web.brave.fixture.phase", "late");
        assertEquals("returned", TraceStore.get("web.brave.fixture.phase"));
    }

    @Test
    void tighterTraceParentIsReusedByTheWorkerAndTheAddonParentIsRestored() throws Exception {
        TimeBudget addonParent = new TimeBudget(30_000);
        TimeBudgetContext.set(addonParent);
        TimeBudget traceParent = TraceContext.current().startWithBudget(java.time.Duration.ofSeconds(3)).timeBudget();
        BraveSearchService brave = brave();
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            assertSame(traceParent, TimeBudgetContext.get());
            return BraveSearchResult.ok(List.of("completed result"), 1);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertEquals(List.of("completed result"), provider(brave, pool).search("합성 검색", 3));
            assertSame(addonParent, TimeBudgetContext.get());
            assertSame(traceParent, TraceContext.current().timeBudget());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static BraveSearchService brave() {
        BraveSearchService brave = mock(BraveSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(BraveSearchResult.ok(List.of(), 1));
        return brave;
    }

    private static HybridWebSearchProvider provider(BraveSearchService brave, ExecutorService executor) {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(mock(NaverSearchService.class), brave);
        ReflectionTestUtils.setField(provider, "searchIoExecutor", executor);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "timeoutSec", 3);
        return provider;
    }

    private static TimeBudget budget(AtomicLong remaining) {
        TimeBudget budget = mock(TimeBudget.class);
        when(budget.remainingMillis()).thenAnswer(call -> remaining.get());
        when(budget.expired()).thenAnswer(call -> remaining.get() <= 0);
        when(budget.capWaitMillis(anyLong())).thenAnswer(call ->
                Math.min(Math.max(0L, call.getArgument(0, Long.class)), remaining.get()));
        return budget;
    }

    private static class InlineExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
