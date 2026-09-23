package com.example.lms.search.provider;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridWebSearchRequestBudgetTest {
    private final NaverSearchService naver = mock(NaverSearchService.class);
    private final BraveSearchService brave = mock(BraveSearchService.class);
    private final GeminiGateway gemini = mock(GeminiGateway.class);

    @AfterEach
    void clear() {
        TimeBudgetContext.clear();
        TraceContext.cleanupCurrentThread();
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void expiredParentBudgetPreventsTheFirstProviderCall() {
        TimeBudgetContext.set(budget(new AtomicLong(0)));
        HybridWebSearchProvider provider = provider(true);
        assertEquals(List.of(), provider.search("synthetic search", 5));
        verify(brave, never()).search(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
        verifyNoInteractions(gemini);
    }

    @Test
    void boundedFallbackCannotRenewTheParentBudgetAfterBrave() {
        AtomicLong remaining = new AtomicLong(600);
        TimeBudget parent = budget(remaining);
        TimeBudgetContext.set(parent);
        when(brave.searchWithMeta(anyString(), eq(5))).thenAnswer(call -> {
            assertSame(parent, TimeBudgetContext.get());
            remaining.set(0);
            return com.example.lms.service.web.BraveSearchResult.ok(List.of(), 1);
        });
        assertEquals(List.of(), provider(true).search("synthetic search", 5));
        verify(brave).searchWithMeta("synthetic search", 5);
        verify(naver, never()).searchWithTraceSync(anyString(), anyInt());
        verifyNoInteractions(gemini);
        assertSame(parent, TimeBudgetContext.get(), "the search must not clear its caller's budget");
        assertEquals("budget_exhausted", TraceStore.get("web.boundedRoute.terminalReason"));
    }

    @Test
    void traceAndPlainEntryShareOneOwnedBudgetAndReleaseIt() {
        AtomicReference<TimeBudget> observed = new AtomicReference<>();
        when(brave.searchWithMeta(anyString(), eq(5))).thenAnswer(call -> {
            observed.set(TimeBudgetContext.get());
            return com.example.lms.service.web.BraveSearchResult.ok(List.of(), 1);
        });
        when(naver.searchWithTraceSync(anyString(), eq(5))).thenAnswer(call -> {
            assertNotNull(observed.get());
            assertSame(observed.get(), TimeBudgetContext.get());
            return new NaverSearchService.SearchResult(List.of("synthetic result"), new NaverSearchService.SearchTrace());
        });
        assertEquals(List.of("synthetic result"), provider(true).searchWithTrace("synthetic search", 5).snippets());
        assertNull(TimeBudgetContext.get());
    }

    @Test
    void legacyTraceFallbackAlsoStopsAtTheSameDeadline() {
        AtomicLong remaining = new AtomicLong(600);
        TimeBudgetContext.set(budget(remaining));
        when(brave.search(anyString(), eq(5))).thenAnswer(call -> {
            remaining.set(0);
            return List.of();
        });
        assertEquals(List.of(), provider(false).searchWithTrace("synthetic search", 5).snippets());
        verify(naver, never()).searchWithTraceSync(anyString(), anyInt());
    }

    @Test
    void interruptedPlainEntryCannotStartProvidersOrClearTheFlag() {
        HybridWebSearchProvider provider = provider(true);
        Thread.currentThread().interrupt();
        assertEquals(List.of(), provider.search("synthetic search", 5));
        assertTrue(Thread.currentThread().isInterrupted());
        verify(brave, never()).search(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
        verifyNoInteractions(gemini);
        assertNull(TimeBudgetContext.get());
    }

    private HybridWebSearchProvider provider(boolean bounded) {
        when(brave.isEnabled()).thenReturn(true);
        when(naver.isEnabled()).thenReturn(true);
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", bounded);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "timeoutSec", 3);
        ReflectionTestUtils.setField(provider, "geminiGateway", gemini);
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
}
