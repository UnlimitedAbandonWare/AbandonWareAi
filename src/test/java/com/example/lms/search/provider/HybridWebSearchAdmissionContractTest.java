package com.example.lms.search.provider;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.TraceContext;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class HybridWebSearchAdmissionContractTest {
    @AfterEach
    void clearContext() {
        TimeBudgetContext.clear();
        TraceContext.cleanupCurrentThread();
        TraceStore.clear();
    }

    @ParameterizedTest
    @CsvSource({"false,BRAVE,1", "true,BRAVE,0", "true,BRAVE,1", "true,NAVER,1"})
    void rejectedSubmissionRetainsTheOtherProviderResult(boolean traced, String primary, int rejectedOrdinal) {
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        when(brave.isEnabled()).thenReturn(true);
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(List.of("synthetic naver result"));
        when(naver.searchWithTraceSync(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new NaverSearchService.SearchResult(List.of("synthetic naver result"), null));
        when(brave.searchWithMeta(anyString(), anyInt()))
                .thenReturn(BraveSearchResult.ok(List.of("synthetic brave result"), 1));
        AtomicInteger ordinal = new AtomicInteger();
        ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy()) {
            @Override public void execute(Runnable command) {
                if (ordinal.getAndIncrement() == rejectedOrdinal)
                    throw new RejectedExecutionException("synthetic admission refusal");
                super.execute(command);
            }
        };
        try {
            HybridWebSearchProvider provider = provider(naver, brave, executor, primary);
            List<String> snippets = assertDoesNotThrow(() -> traced
                    ? provider.searchWithTrace("합성 검색", 3).snippets() : provider.search("합성 검색", 3));
            boolean braveRejected = primary.equals("BRAVE") ? rejectedOrdinal == 0 : rejectedOrdinal == 1;
            assertEquals(List.of(braveRejected ? "synthetic naver result" : "synthetic brave result"), snippets);
            assertEquals("executor_saturated", TraceStore.get(
                    "web." + (braveRejected ? "brave" : "naver") + ".skipped.reason"));
            verify(brave, times(braveRejected ? 0 : 1)).searchWithMeta(anyString(), anyInt());
            verify(naver, times(!traced && !braveRejected ? 0 : (!traced ? 1 : 0)))
                    .searchSnippetsSync(anyString(), anyInt(), any(Duration.class));
            verify(naver, times(traced && braveRejected ? 1 : 0))
                    .searchWithTraceSync(anyString(), anyInt(), any(Duration.class));
        } finally {
            executor.shutdownNow();
            try { assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saturatedTraceSearchIsFailSoftUnderAbortAndCallerRuns(boolean callerRuns) throws Exception {
        var naver = mock(NaverSearchService.class);
        var brave = mock(BraveSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), callerRuns
                    ? new ThreadPoolExecutor.CallerRunsPolicy() : new ThreadPoolExecutor.AbortPolicy());
        try {
            executor.execute(() -> {
                occupied.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            var provider = provider(naver, brave, new ContextAwareExecutorService(executor), "BRAVE");
            var result = assertDoesNotThrow(() -> provider.searchWithTrace("합성 검색", 3));
            assertEquals(List.of(), result.snippets());
            assertEquals("executor_saturated", TraceStore.get("web.brave.skipped.reason"));
            verify(brave, never()).searchWithMeta(anyString(), anyInt());
            verify(naver, never()).searchWithTraceSync(anyString(), anyInt(), any(Duration.class));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static HybridWebSearchProvider provider(NaverSearchService naver, BraveSearchService brave,
            ExecutorService executor, String primary) {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "searchIoExecutor", executor);
        ReflectionTestUtils.setField(provider, "primary", primary);
        ReflectionTestUtils.setField(provider, "timeoutSec", 3);
        return provider;
    }
}
