package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit contracts for the real synchronous facades; wire behavior has separate local HTTP tests. */
class NaverSearchSyncBudgetTest {
    @AfterEach void clear() {
        TimeBudgetContext.clear();
        TraceContext.cleanupCurrentThread();
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void explicitBlockTimeoutIsCappedByTheCurrentRequestBudget() {
        TimeBudget budget = mock(TimeBudget.class);
        when(budget.remainingMillis()).thenReturn(600L);
        when(budget.capWaitMillis(anyLong())).thenAnswer(call -> Math.min(600L, call.getArgument(0, Long.class)));
        TimeBudgetContext.set(budget);
        Duration actual = ReflectionTestUtils.invokeMethod(service(), "resolveSyncBlockTimeout", Duration.ofMillis(1_000));
        assertEquals(Duration.ofMillis(600), actual);
    }

    @Test
    void expiredSnippetFacadeDoesNotSubscribeOrTurnZeroIntoAnUnlimitedWait() {
        TimeBudgetContext.set(mock(TimeBudget.class));
        AtomicBoolean subscribed = new AtomicBoolean();
        NaverSearchService service = service();
        doReturn(Mono.defer(() -> { subscribed.set(true); return Mono.just(List.of("unexpected")); }))
                .when(service).searchSnippetsMono(anyString(), anyInt());
        assertEquals(List.of(), service.searchSnippetsSync("synthetic query", 3));
        assertFalse(subscribed.get());
        assertEquals("request_budget_exhausted", TraceStore.get("web.naver.failureReason"));
    }

    @Test
    void expiredTraceFacadeDoesNotSubscribeAndPreservesTheTerminalReason() {
        TimeBudgetContext.set(mock(TimeBudget.class));
        AtomicBoolean subscribed = new AtomicBoolean();
        NaverSearchService service = service();
        doReturn(Mono.defer(() -> {
            subscribed.set(true);
            return Mono.just(new NaverSearchService.SearchResult(List.of("unexpected"), null));
        })).when(service).searchWithTraceMono(anyString(), anyInt());
        NaverSearchService.SearchResult result = service.searchWithTraceSync("synthetic query", 3);
        assertEquals(List.of(), result.snippets());
        assertFalse(subscribed.get());
        assertNotNull(result.trace());
        assertEquals("request_budget_exhausted", result.trace().steps.get(0).query);
    }

    @Test
    void interruptedFacadeDoesNotSubscribeOrEraseCancellation() {
        NaverSearchService service = service();
        AtomicBoolean subscribed = new AtomicBoolean();
        doReturn(Mono.defer(() -> { subscribed.set(true); return Mono.just(List.of("unexpected")); }))
                .when(service).searchSnippetsMono(anyString(), anyInt());
        Thread.currentThread().interrupt();
        assertEquals(List.of(), service.searchSnippetsSync("synthetic query", 3));
        assertFalse(subscribed.get());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals("cancelled", TraceStore.get("web.naver.failureReason"));
    }

    private static NaverSearchService service() {
        NaverSearchService service = mock(NaverSearchService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "syncBlockTimeoutMs", 1_500L);
        return service;
    }
}
