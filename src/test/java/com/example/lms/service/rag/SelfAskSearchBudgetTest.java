package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.ExecutionMode;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SelfAskSearchBudgetTest {
    private static final String ORIGINAL = "\uD3F4\uB4DC7 \uC0AC\uC591";
    private static final String BRAVE_FORM = "Samsung Galaxy Z Fold7 official specs release date price";
    private static final String FOLLOW_UP = "\uADF8\uB7FC \uADF8\uB140\uC11D \uC804\uBB34 \uBB50\uC4F0\uBA74 \uC88B\uB0D0?";
    private static final String SYNTHETIC_FORM = "synthetic-provider-representation-follow-up";

    @AfterEach
    void clear() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceContext.cleanupCurrentThread();
        TraceStore.clear();
        Thread.interrupted();
    }

    @ParameterizedTest
    @CsvSource({"BRAVE,false,false", "NAVER,false,false", "BRAVE,true,false", "NAVER,true,true"})
    void transformedProviderQueriesShareTheAlreadyAdmittedRetrievalLeg(
            String primary, boolean traceEntry, boolean followUpFixture)
            throws Exception {
        if (!followUpFixture) {
            runHybridLeg(primary, traceEntry, false, ORIGINAL, BRAVE_FORM);
            return;
        }
        // This follow-up anchors only budget identity. The representation is synthetic;
        // this test proves no entity resolution, weapon fact, or production translation.
        Class<?> queryPolicy = Class.forName("com.example.lms.search.provider.HybridSearchQueryPolicy");
        try (MockedStatic<?> conversion = mockStatic(queryPolicy, CALLS_REAL_METHODS)) {
            conversion.when(() -> ReflectionTestUtils.invokeMethod(
                    queryPolicy, "convertToEnglishSearchTerm", FOLLOW_UP)).thenReturn(SYNTHETIC_FORM);
            runHybridLeg(primary, traceEntry, false, FOLLOW_UP, SYNTHETIC_FORM);
        }
    }

    @Test
    void officialOnlyKeepsTheGuardWhileSharingTheProviderRepresentation() throws Exception {
        runHybridLeg("NAVER", false, true, ORIGINAL, BRAVE_FORM);
        assertTrue(GuardContextHolder.get().isOfficialOnly());
    }

    private void runHybridLeg(String primary, boolean traceEntry, boolean officialOnly,
                              String original, String braveForm) throws Exception {
        SelfAskSearchBudget budget = SelfAskSearchBudget.beginRequest(ExecutionMode.AUTO);
        // SelfAskWebSearchRetriever.safeSearchAttempt admits the original before Hybrid dispatch.
        assertTrue(budget.tryQuery(original));
        GuardContext guard = new GuardContext();
        guard.setOfficialOnly(officialOnly);
        GuardContextHolder.set(guard);

        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        when(brave.isEnabled()).thenReturn(true);
        AtomicInteger admittedBrave = new AtomicInteger();
        AtomicInteger admittedNaver = new AtomicInteger();
        // Mock only the external adapters. Exercise their real shared admission API.
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            assertSame(budget, SelfAskSearchBudget.current());
            assertEquals(braveForm, call.getArgument(0));
            String query = call.getArgument(0);
            if (!SelfAskSearchBudget.tryQueryAlias(query, query)
                    || !SelfAskSearchBudget.tryReserveHttp(TraceStore.context(), query)) {
                return new BraveSearchResult(List.of(), BraveSearchResult.Status.EXCEPTION, null, 0,
                        "query_alias_denied", 0);
            }
            admittedBrave.incrementAndGet();
            return BraveSearchResult.ok(List.of("brave fixture https://example.test/brave"), 1);
        });
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class))).thenAnswer(call -> {
            String query = call.getArgument(0);
            assertSame(budget, SelfAskSearchBudget.current());
            assertEquals(original, query);
            if (!SelfAskSearchBudget.tryQueryAlias(query, query)
                    || !SelfAskSearchBudget.tryReserveHttp(TraceStore.context(), query)) return List.of();
            admittedNaver.incrementAndGet();
            return List.of("naver fixture https://example.test/naver");
        });
        when(naver.searchWithTraceSync(anyString(), anyInt(), any(Duration.class))).thenAnswer(call -> {
            String query = call.getArgument(0);
            assertSame(budget, SelfAskSearchBudget.current());
            assertEquals(original, query);
            List<String> result = List.of();
            if (SelfAskSearchBudget.tryQueryAlias(query, query)
                    && SelfAskSearchBudget.tryReserveHttp(TraceStore.context(), query)) {
                admittedNaver.incrementAndGet();
                result = List.of("naver fixture https://example.test/naver");
            }
            return new NaverSearchService.SearchResult(result, new NaverSearchService.SearchTrace());
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
            ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", false);
            ReflectionTestUtils.setField(provider, "primary", primary);
            ReflectionTestUtils.setField(provider, "searchIoExecutor", executor);
            ReflectionTestUtils.setField(provider, "soakEnabled", false);
            ReflectionTestUtils.setField(provider, "koreanHedgeDelayMs", 0L);
            List<String> results = traceEntry
                    ? provider.searchWithTrace(original, 5).snippets() : provider.search(original, 5);
            assertFalse(results.isEmpty());
            assertEquals(1, admittedBrave.get(), "same-leg Brave representation must not starve");
            assertEquals(1, admittedNaver.get());
            assertEquals(1, TraceStore.get("executionMode.queryCount"));
            assertEquals(2, TraceStore.get("executionMode.httpAttempts"));
            assertEquals("AUTO", TraceStore.get("executionMode.effective"));
            assertFalse(budget.tryQuery("independent additional search"));
            assertFalse(SelfAskSearchBudget.tryQueryAlias("independent additional search", "extra representation"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"AUTO", "STRIKE", "SELF_ASK"})
    void providerNormalizationDoesNotEnableAdditionalQueriesOrExceedSixHttpAttempts(ExecutionMode mode) {
        SelfAskSearchBudget budget = SelfAskSearchBudget.beginRequest(mode);
        assertTrue(budget.tryQuery("release 5 8 ver"));
        assertTrue(SelfAskSearchBudget.tryQueryAlias("release 5 8 ver", "release 5.8 ver"));
        assertTrue(SelfAskSearchBudget.tryQueryAlias("release 5.8 ver", "release 5.8"));
        for (int i = 0; i < 6; i++) {
            assertTrue(SelfAskSearchBudget.tryReserveHttp(TraceStore.context(),
                    i % 2 == 0 ? "release 5 8 ver" : "release 5.8"));
        }
        assertFalse(SelfAskSearchBudget.tryReserveHttp(TraceStore.context(), "release 5.8"));
        assertFalse(budget.tryQuery("a genuinely additional query"));
        assertFalse(SelfAskSearchBudget.tryQueryAlias("a genuinely additional query", "additional representation"));
        assertEquals(1, TraceStore.get("executionMode.queryCount"));
        assertEquals(6, TraceStore.get("executionMode.httpAttempts"));
        assertFalse(budget.expansionAllowed());
    }

    @Test
    void explicitExpansionRetainsTheExistingThreeLogicalQueryLimit() {
        SelfAskSearchBudget budget = SelfAskSearchBudget.beginRequest(ExecutionMode.SELF_ASK);
        assertTrue(budget.tryQuery("base query"));
        assertTrue(budget.allowExpansion("user-self-ask"));
        assertTrue(SelfAskSearchBudget.tryQueryAlias("first additional query", "first representation"));
        assertTrue(SelfAskSearchBudget.tryQueryAlias("second additional query", "second representation"));
        assertFalse(SelfAskSearchBudget.tryQueryAlias("fourth logical query", "fourth representation"));
        assertEquals(3, TraceStore.get("executionMode.queryCount"));
        assertEquals("SELF_ASK", TraceStore.get("executionMode.effective"));
    }

    @Test
    void collidingProviderRepresentationsCannotAdmitAnIndependentSecondLeg() throws Exception {
        String second = "\uD3F4\uB4DC7 \uBC30\uD130\uB9AC";
        SelfAskSearchBudget budget = SelfAskSearchBudget.beginRequest(ExecutionMode.AUTO);
        assertTrue(budget.tryQuery(ORIGINAL));
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        AtomicInteger actualHttp = new AtomicInteger();
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            String query = call.getArgument(0);
            assertSame(budget, SelfAskSearchBudget.current());
            if (!SelfAskSearchBudget.tryQueryAlias(query, query)
                    || !SelfAskSearchBudget.tryReserveHttp(TraceStore.context(), query)) {
                return new BraveSearchResult(List.of(), BraveSearchResult.Status.EXCEPTION, null, 0,
                        "query_alias_denied", 0);
            }
            assertEquals(BRAVE_FORM, query);
            actualHttp.incrementAndGet();
            return BraveSearchResult.ok(List.of("brave fixture https://example.test/collision"), 1);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
            ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", false);
            ReflectionTestUtils.setField(provider, "primary", "BRAVE");
            ReflectionTestUtils.setField(provider, "searchIoExecutor", executor);
            ReflectionTestUtils.setField(provider, "soakEnabled", false);
            ReflectionTestUtils.setField(provider, "koreanHedgeDelayMs", 0L);
            assertFalse(provider.search(ORIGINAL, 5).isEmpty());
            assertEquals(1, actualHttp.get());
            // Isolate this independent leg from optional cache remerge/backup work.
            TraceStore.put("websearch.backup.used", true);
            assertTrue(provider.search(second, 5).isEmpty());
            assertEquals(1, actualHttp.get(), "an alias collision must authorize no second HTTP attempt");
            assertEquals(1, TraceStore.get("executionMode.queryCount"));
            assertEquals(1, TraceStore.get("executionMode.httpAttempts"));
            assertFalse(budget.tryQuery(second));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
