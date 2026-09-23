package com.example.lms.search.provider;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridSearchMergeContractTest {
    @AfterEach void clear() { TraceStore.clear(); GuardContextHolder.clear(); }

    @Test
    void repeatingTheSameBatchIsIdempotentButDifferentProviderRenderingsSurvive() {
        List<String> first = List.of("A", "A", "B");
        assertEquals(List.of("A", "B", "C"), merge(first, List.of("B", "C"), 5));
        assertEquals(merge(first, List.of("C"), 5), merge(merge(first, first, 5), List.of("C"), 5));
        String brave = "<a href=\"https://example.test/doc?v=1\">Brave title</a>";
        String naver = "<a href=\"https://example.test/doc?v=1\">Naver title</a>";
        String otherVersion = "<a href=\"https://example.test/doc?v=2\">Brave title</a>";
        assertEquals(List.of(brave, naver, otherVersion), merge(List.of(brave), List.of(naver, otherVersion), 5));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 3})
    void providerMergeRetainsTheExistingMinimumThreeLimit(int requested) {
        assertEquals(List.of("P1", "P2", "S1"), merge(List.of("P1", "P2"), List.of("S1", "S2"), requested));
    }

    @Test
    void blanksAreRemovedBeforeLimitButTrustFilteringRemainsAfterLimitWithoutBackfill() {
        GuardContext guard = new GuardContext();
        guard.setOfficialOnly(true);
        GuardContextHolder.set(guard);
        String lowTrust = "https://blog.naver.com/synthetic";
        assertEquals(List.of("trusted A", "trusted B"), merge(
                Arrays.asList(null, "", " ", lowTrust, "trusted A", "trusted B", "trusted C"), List.of(), 3));
        assertTrue(merge(List.of(lowTrust), List.of(), 3).isEmpty(),
                "official-only requests must not restore an entirely rejected list");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialOnlyNeverRestoresAllLowTrustResultsEvenInStrikeMode(boolean strikeMode) {
        GuardContext guard = new GuardContext();
        guard.setOfficialOnly(true);
        guard.setStrikeMode(strikeMode);
        GuardContextHolder.set(guard);
        assertTrue(merge(List.of("https://blog.naver.com/synthetic"),
                List.of("https://tistory.com/synthetic"), 3).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonOfficialRequestsRetainLegacyAllFilteredFallback(boolean strikeMode) {
        GuardContext guard = new GuardContext();
        guard.setStrikeMode(strikeMode);
        GuardContextHolder.set(guard);
        String lowTrust = "https://blog.naver.com/synthetic";
        assertEquals(List.of(lowTrust), merge(List.of(lowTrust), List.of(), 3));
    }

    @Test
    void officialOnlyCacheRecoveryCannotReintroduceRejectedSnippets() {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.searchCacheOnly(anyString(), anyInt()))
                .thenReturn(List.of("https://blog.naver.com/synthetic"));
        when(naver.searchSnippetsCacheOnly(anyString(), anyInt(), any()))
                .thenReturn(List.of("https://tistory.com/synthetic"));
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "remergeOnEmptyEnabled", true);
        ReflectionTestUtils.setField(provider, "remergeOnEmptyMaxPolls", 1);
        ReflectionTestUtils.setField(provider, "braveCacheOnlyEscape", true);
        ReflectionTestUtils.setField(provider, "naverCacheOnlyEscape", true);
        GuardContext guard = new GuardContext();
        guard.setOfficialOnly(true);
        GuardContextHolder.set(guard);
        TraceStore.put("web.await.events.timeout.count", 1L);
        assertTrue(provider.search("synthetic cache query", 3).isEmpty());
        verify(brave, never()).search(anyString(), anyInt());
        verify(brave, never()).searchWithMeta(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt(), any());
    }

    @Test
    void inputMutationAfterReturnCannotChangeResultsAndMissingProviderCannotEraseHealthyResults() {
        ArrayList<String> primary = new ArrayList<>(List.of("A", "B"));
        ArrayList<String> secondary = new ArrayList<>(List.of("B", "C"));
        List<String> result = merge(primary, secondary, 5);
        assertEquals(List.of("A", "B"), primary);
        assertEquals(List.of("B", "C"), secondary);
        primary.clear();
        secondary.add("late");
        assertEquals(List.of("A", "B", "C"), result);
        assertEquals(List.of("B", "C", "late"), merge(null, secondary, 5));
    }

    @Test
    void seededBatchReplayPreservesFirstOccurrenceOrder() {
        Random random = new Random(20260906L);
        for (int i = 0; i < 20; i++) {
            ArrayList<String> batch = new ArrayList<>(List.of("A", "B", "C", "D"));
            Collections.shuffle(batch, random);
            List<String> original = List.copyOf(batch);
            batch.addAll(original);
            assertEquals(original, merge(batch, batch, 4));
        }
    }

    @Test
    void realEntryReusesTheCacheOnlyMergeWithoutAnyLiveProviderCall() {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.searchCacheOnly(anyString(), anyInt())).thenReturn(List.of("brave", "shared"));
        when(naver.searchSnippetsCacheOnly(anyString(), anyInt(), any())).thenReturn(List.of("shared", "naver"));
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "remergeOnEmptyEnabled", true);
        ReflectionTestUtils.setField(provider, "remergeOnEmptyMaxPolls", 1);
        ReflectionTestUtils.setField(provider, "braveCacheOnlyEscape", true);
        ReflectionTestUtils.setField(provider, "naverCacheOnlyEscape", true);
        TraceStore.put("web.await.events.timeout.count", 1L);

        assertEquals(List.of("brave", "shared", "naver"), provider.search("synthetic cache query", 3));
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.remergeOnce.hit"));
        var order = inOrder(brave, naver);
        order.verify(brave).searchCacheOnly(anyString(), eq(3));
        order.verify(naver).searchSnippetsCacheOnly(anyString(), eq(3), any());
        verify(brave, never()).search(anyString(), anyInt());
        verify(brave, never()).searchWithMeta(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt(), any());
    }

    @Test
    void actualKeywordRetryKeepsItsExactRequestedLimitAndEarlierBatchPriority() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(
                BraveSearchResult.ok(List.of("first"), 1),
                BraveSearchResult.ok(List.of("first", "later"), 1));
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "soakEnabled", true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(provider, "searchIoExecutor", executor);
        try {
            assertEquals(List.of("first"), provider.search("RTX 5090 가격 알려줘", 1));
            verify(brave, times(2)).searchWithMeta(anyString(), anyInt());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void realEntryKeepsProviderPriorityAcrossControlledCompletionOrdersAndOneFailure() throws Exception {
        // Fixed counterexamples first, then reproducible equivalent completion permutations.
        verifyCompletionOrder(0, false);
        verifyCompletionOrder(1, false);
        verifyCompletionOrder(0, true);
        Random random = new Random(20260906L);
        for (int i = 0; i < 4; i++) verifyCompletionOrder(random.nextInt(2), false);
    }

    private static void verifyCompletionOrder(int first, boolean naverFails) throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(naver.isEnabled()).thenReturn(true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] finished = {new CountDownLatch(1), new CountDownLatch(1)};
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            ready.countDown();
            assertTrue(release[0].await(5, TimeUnit.SECONDS));
            return BraveSearchResult.ok(List.of("brave first", "shared"), 1);
        });
        when(naver.searchSnippetsSync(anyString(), anyInt(), any())).thenAnswer(call -> {
            ready.countDown();
            assertTrue(release[1].await(5, TimeUnit.SECONDS));
            if (naverFails) throw new IllegalStateException("synthetic provider failure");
            return List.of("shared", "naver second");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ExecutorService requester = Executors.newSingleThreadExecutor();
        AtomicInteger submissions = new AtomicInteger();
        ExecutorService observed = new AbstractExecutorService() {
            @Override public void execute(Runnable task) {
                int index = submissions.getAndIncrement();
                workers.execute(() -> { try { task.run(); } finally { finished[index].countDown(); } });
            }
            @Override public void shutdown() { workers.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return workers.shutdownNow(); }
            @Override public boolean isShutdown() { return workers.isShutdown(); }
            @Override public boolean isTerminated() { return workers.isTerminated(); }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return workers.awaitTermination(timeout, unit);
            }
        };
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "koreanHedgeDelayMs", 0L);
        ReflectionTestUtils.setField(provider, "timeoutSec", 30);
        ReflectionTestUtils.setField(provider, "searchIoExecutor", observed);
        try {
            var response = requester.submit(() -> provider.search("합성 검색", 5));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release[first].countDown();
            assertTrue(finished[first].await(5, TimeUnit.SECONDS));
            release[1 - first].countDown();
            assertEquals(naverFails ? List.of("brave first", "shared")
                    : List.of("brave first", "shared", "naver second"), response.get(5, TimeUnit.SECONDS));
            assertEquals(2, submissions.get());
            verify(brave, times(1)).searchWithMeta(anyString(), anyInt());
            verify(naver, times(1)).searchSnippetsSync(anyString(), anyInt(), any());
        } finally {
            release[0].countDown();
            release[1].countDown();
            requester.shutdownNow();
            workers.shutdownNow();
            assertTrue(requester.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static List<String> merge(List<String> first, List<String> second, int topK) {
        return ReflectionTestUtils.invokeMethod(null, HybridWebSearchProvider.class,
                "mergeAndLimit", first, second, topK);
    }
}
