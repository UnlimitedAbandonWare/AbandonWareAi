package com.example.lms.search.provider;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class HybridWebSearchDeadlineBudgetTest {
    @AfterEach
    void clear() {
        GuardContextHolder.clear();
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void desiredFloorCannotExceedTheRemainingRequestBudget() throws Exception {
        HybridWebSearchProvider provider = provider();
        ReflectionTestUtils.setField(provider, "awaitMinLiveBudgetMs", 1_000L);
        ReflectionTestUtils.setField(provider, "awaitFloorTinyBudget", true);
        Future<String> future = mock(Future.class);
        AtomicLong requestedWait = new AtomicLong(-1L);
        when(future.get(anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            requestedWait.set(((TimeUnit) call.getArgument(1)).toMillis(call.getArgument(0)));
            throw new TimeoutException("fixture");
        });
        String result = ReflectionTestUtils.invokeMethod(provider, "awaitWithDeadline", future,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600), "fallback", "Brave");
        assertEquals("fallback", result);
        assertTrue(requestedWait.get() > 0 && requestedWait.get() <= 600,
                "effective wait must not grant a new floor beyond the remaining budget");
    }

    @Test
    void exhaustedOfficialOnlyBudgetDoesNotStartAnotherWait() throws Exception {
        HybridWebSearchProvider provider = provider();
        ReflectionTestUtils.setField(provider, "awaitMinLiveBudgetMsOfficialOnly", 900L);
        ReflectionTestUtils.setField(provider, "awaitFloorBudgetExhaustedOfficialOnly", true);
        GuardContext guard = new GuardContext();
        guard.setOfficialOnly(true);
        GuardContextHolder.set(guard);
        Future<String> future = mock(Future.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("fixture"));
        String result = ReflectionTestUtils.invokeMethod(provider, "awaitWithDeadline", future,
                System.nanoTime() - 1L, "fallback", "Brave");
        assertEquals("fallback", result);
        verify(future, never()).get(anyLong(), any(TimeUnit.class));
        assertTrue(String.valueOf(TraceStore.get("web.await.last")).contains("budget_exhausted"));
    }

    @Test
    void alreadyCompletedValueIsCollectedWithoutStartingABudgetedWait() throws Exception {
        HybridWebSearchProvider provider = provider();
        Future<String> future = mock(Future.class);
        when(future.isDone()).thenReturn(true);
        when(future.get()).thenReturn("completed");
        String result = ReflectionTestUtils.invokeMethod(provider, "awaitWithDeadline", future,
                System.nanoTime() - 1L, "fallback", "Brave");
        assertEquals("completed", result);
        verify(future, never()).get(anyLong(), any(TimeUnit.class));
    }

    private static HybridWebSearchProvider provider() {
        return new HybridWebSearchProvider(mock(NaverSearchService.class), mock(BraveSearchService.class));
    }

    @Test
    void naverBlockFloorCannotExceedItsExplicitCapOrRequestRemainder() {
        HybridWebSearchProvider provider = provider();
        ReflectionTestUtils.setField(provider, "naverBlockTimeoutCapMs", 50L);
        ReflectionTestUtils.setField(provider, "awaitDeadlineMarginMs", 120L);
        long actual = ReflectionTestUtils.invokeMethod(provider, "resolveNaverBlockTimeoutMs",
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100), 0L, "fixture");
        assertTrue(actual > 0 && actual <= 50, "the 250ms preferred floor cannot override a smaller cap");
    }

    @Test
    void expiredNaverBlockBudgetStaysZero() {
        long actual = ReflectionTestUtils.invokeMethod(provider(), "resolveNaverBlockTimeoutMs",
                System.nanoTime() - 1, 0L, "fixture");
        assertEquals(0, actual);
    }
}
