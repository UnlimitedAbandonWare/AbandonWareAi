package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static org.junit.jupiter.api.Assertions.*;

class ChatPlanBudgetFocusedTest {
    @AfterEach void clear() { TraceStore.clear(); }
    private final PlanHintApplier plans = new PlanHintApplier(new DefaultResourceLoader());
    @Test void safeMicroRecallUsesTwoAdditionalQueriesRatherThanTwelve() {
        var guard = new PublicRequestBudgetGuard();
        var request = ChatRequestDto.builder().message("bounded concept question")
                .useWebSearch(true).useRag(false).webTopK(8).searchQueries(0)
                .searchMode(SearchMode.FORCE_LIGHT).build();
        assertDoesNotThrow(() -> guard.validateChatProjected(request, plans.load("safe.v1"), true, false));
        assertEquals(3L, TraceStore.get("public.request.budget.branchCount"),
                "one base query plus the authored two optional ExtremeZ queries");
    }
    @Test void koreanConceptWithRagAndWebIsAdmittedAtUnchangedLimits() {
        var guard = new PublicRequestBudgetGuard();
        var request = ChatRequestDto.builder().message("하이젠베르크 불확정성 원리가 뭐니?")
                .useWebSearch(true).useRag(true).webTopK(8).searchQueries(0)
                .searchMode(SearchMode.AUTO).build();
        assertDoesNotThrow(() -> guard.validateChatProjected(request, plans.load("safe.v1"), true, true));
        assertTrue(((Number)TraceStore.get("public.request.budget.retrievalWork")).longValue() <= 384);
        assertTrue(((Number)TraceStore.get("public.request.budget.providerWork")).longValue() <= 4096);
    }
    @Test void braveKeepsIndependentPlannerAndExtremeZExpansionAndEnforcesBoundary() {
        var guard = new PublicRequestBudgetGuard();
        guard.setMaxRetrievalWork(396); guard.setMaxProviderWork(10_000);
        var request = ChatRequestDto.builder().message("recall this topic with bounded evidence")
                .useWebSearch(true).useRag(false).webTopK(8).searchQueries(0)
                .searchMode(SearchMode.AUTO).build();
        assertDoesNotThrow(() -> guard.validateChatProjected(request, plans.load("brave.v1"), true, false));
        assertEquals(396L, TraceStore.get("public.request.budget.retrievalWork"));
        assertEquals(10L, TraceStore.get("public.request.budget.plannedQueries"));
        assertEquals(12, TraceStore.get("public.request.budget.extremeZQueries"));
        assertEquals(22L, TraceStore.get("public.request.budget.branchCount"));
        guard.setMaxRetrievalWork(395);
        assertThrows(PublicRequestBudgetGuard.Rejection.class,
                () -> guard.validateChatProjected(request, plans.load("brave.v1"), true, false));
    }
}
