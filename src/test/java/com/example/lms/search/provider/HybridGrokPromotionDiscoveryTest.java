package com.example.lms.search.provider;

import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.GrokPromotionDiscovery;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridGrokPromotionDiscoveryTest {
    private static final String QUERY = "SuperGrok Heavy discount existing account Google Play";

    @AfterEach
    void clear() {
        com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void defaultBoundedRouteExploresAndVerifiesEvenWhenFirstSearchHasResults() {
        BraveSearchService brave = brave();
        NaverSearchService naver = naver();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(2))).thenReturn(
                List.of("observed-offer", "second-offer"), List.of("observed-terms"));
        HybridWebSearchProvider provider = provider(brave, naver, gateway);

        assertEquals(List.of("observed-offer", "observed-terms"), provider.search(QUERY, 2));

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(brave, times(2)).search(queries.capture(), eq(2));
        assertEquals(List.of(QUERY + GrokPromotionDiscovery.DISCOVERY_SUFFIX,
                QUERY + GrokPromotionDiscovery.VERIFICATION_SUFFIX), queries.getAllValues());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
        verify(gateway, never()).expandSearchQueryOnce(anyString());
        assertEquals(2, TraceStore.get("web.boundedRoute.providerCycles"));
        assertEquals("base_results_not_exhaustive", TraceStore.get("web.promotionDiscovery.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(QUERY));
    }

    @Test
    void missingFirstOfferStillResearchesTermsWithoutPaidQueryExpansion() {
        BraveSearchService brave = brave();
        NaverSearchService naver = naver();
        when(brave.search(anyString(), eq(1))).thenReturn(List.of(), List.of("terms-only-lead"));
        when(naver.searchSnippetsSync(anyString(), eq(1))).thenReturn(List.of());
        HybridWebSearchProvider provider = provider(brave, naver, null);

        assertEquals(List.of("terms-only-lead"), provider.search(QUERY, 1));
        verify(brave, times(2)).search(anyString(), eq(1));
        verify(naver, times(1)).searchSnippetsSync(anyString(), eq(1));
        assertEquals(0, TraceStore.get("web.boundedRoute.geminiAttempts"));
    }

    @Test
    void emptyVerificationRetainsOfferEvidenceWithoutClaimingEligibility() {
        BraveSearchService brave = brave();
        NaverSearchService naver = naver();
        when(brave.search(anyString(), eq(1))).thenReturn(List.of("observed-offer"), List.of());
        when(naver.searchSnippetsSync(anyString(), eq(1))).thenReturn(List.of());
        assertEquals(List.of("observed-offer"), provider(brave, naver, null).search(QUERY, 1));
        assertEquals("promotion-terms-evidence-needed", TraceStore.get("web.boundedRoute.terminalReason"));
        verify(brave, times(2)).search(anyString(), eq(1));
    }

    @Test
    void traceEntryPointUsesTheSamePromotionRoute() {
        BraveSearchService brave = brave();
        when(brave.search(anyString(), eq(2))).thenReturn(List.of("offer"), List.of("terms"));
        NaverSearchService.SearchResult result = provider(brave, naver(), null).searchWithTrace(QUERY, 2);
        assertEquals(List.of("offer", "terms"), result.snippets());
        assertEquals("BOUNDED:promotion-evidence-collected", result.trace().steps.get(0).query);
        assertFalse(result.trace().query.contains(QUERY));
    }

    @Test
    void privacyAndInterruptionStillPreventAllOutboundResearch() {
        BraveSearchService brave = brave();
        NaverSearchService naver = naver();
        HybridWebSearchProvider provider = provider(brave, naver, null);
        ReflectionTestUtils.setField(provider, "blockWebSearch", true);
        assertTrue(provider.search(QUERY, 2).isEmpty());
        ReflectionTestUtils.setField(provider, "blockWebSearch", false);
        try {
            Thread.currentThread().interrupt();
            assertTrue(provider.search(QUERY, 2).isEmpty());
        } finally { Thread.interrupted(); }
        verify(brave, never()).search(anyString(), anyInt());
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
    }

    @Test
    void unrelatedSuccessfulSearchKeepsItsSingleCycle() {
        BraveSearchService brave = brave();
        when(brave.search(anyString(), eq(1))).thenReturn(List.of("reference"));
        assertEquals(List.of("reference"), provider(brave, naver(), null).search("Java reference", 1));
        verify(brave, times(1)).search("Java reference", 1);
    }

    @Test
    void exhaustedParentBudgetRetainsOfferWithoutStartingTermsOrRenewingBudget() {
        java.util.concurrent.atomic.AtomicLong remaining = new java.util.concurrent.atomic.AtomicLong(600);
        com.abandonware.ai.addons.budget.TimeBudget parent = mock(com.abandonware.ai.addons.budget.TimeBudget.class);
        when(parent.remainingMillis()).thenAnswer(call -> remaining.get());
        when(parent.expired()).thenAnswer(call -> remaining.get() <= 0);
        when(parent.capWaitMillis(anyLong())).thenAnswer(call ->
                Math.min(Math.max(0L, call.getArgument(0, Long.class)), remaining.get()));
        com.abandonware.ai.addons.budget.TimeBudgetContext.set(parent);
        BraveSearchService brave = brave();
        NaverSearchService naver = naver();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(2))).thenAnswer(call -> {
            assertSame(parent, com.abandonware.ai.addons.budget.TimeBudgetContext.get());
            remaining.set(0);
            return List.of("observed-offer");
        });

        assertEquals(List.of("observed-offer"), provider(brave, naver, gateway).search(QUERY, 2));
        assertSame(parent, com.abandonware.ai.addons.budget.TimeBudgetContext.get());
        assertEquals("budget_exhausted", TraceStore.get("web.boundedRoute.terminalReason"));
        verify(brave, times(1)).search(anyString(), eq(2));
        verify(naver, never()).searchSnippetsSync(anyString(), anyInt());
        verifyNoInteractions(gateway);
    }

    private HybridWebSearchProvider provider(BraveSearchService brave, NaverSearchService naver,
                                              GeminiGateway gateway) {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", true);
        ReflectionTestUtils.setField(provider, "geminiGateway", gateway);
        return provider;
    }

    private BraveSearchService brave() {
        BraveSearchService brave = mock(BraveSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        return brave;
    }

    private NaverSearchService naver() {
        NaverSearchService naver = mock(NaverSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        return naver;
    }
}
