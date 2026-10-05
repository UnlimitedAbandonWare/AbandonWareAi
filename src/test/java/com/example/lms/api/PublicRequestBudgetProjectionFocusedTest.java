package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * admission 프로젝션의 실행-상한 정합성 focused 테스트 —
 * 플랜 버스트가 apply() 캡(maxFinalQueries)을 넘어도 admission이
 * 실행되지 않는 작업량을 청구하지 않는다(P0-3 회귀 방지).
 */
class PublicRequestBudgetProjectionFocusedTest {

    @Test
    void historicalDeepWorkIsCausedBySearchOptionsRatherThanExecutionPreference() {
        var plan = new com.example.lms.plan.PlanHints("budget-replay", null, null,
                java.util.List.of(), 8, 8, null, java.util.List.of(), null, null, null,
                true, true, null, null, null, null, null, null, 2, true, java.util.Map.of());
        for (var mode : java.util.List.of(com.example.lms.domain.enums.ExecutionMode.AUTO,
                com.example.lms.domain.enums.ExecutionMode.SELF_ASK)) {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Spring Security 6.3에서 POST 로그아웃과 CSRF 토큰 처리의 관계를 "
                            + "docs.spring.io 공식 자료로 확인하고 핵심 두 가지를 짧게 알려 주세요.")
                    .executionMode(mode).useRag(true).useWebSearch(true).webTopK(8)
                    .searchQueries(0).searchMode(SearchMode.FORCE_DEEP).build();
            PublicRequestBudgetGuard diagnostic = guardWithPlans();
            diagnostic.validateChatProjected(request, plan, true, true);
            assertEquals(14L, TraceStore.get("public.request.budget.plannedQueries"));
            assertEquals(2, TraceStore.get("public.request.budget.extremeZQueries"));
            assertEquals(672L, TraceStore.get("public.request.budget.retrievalWork"));
            assertEquals(4_224L, TraceStore.get("public.request.budget.providerWork"));

            var defaults = new PublicRequestBudgetGuard();
            var rejection = org.junit.jupiter.api.Assertions.assertThrows(
                    PublicRequestBudgetGuard.Rejection.class,
                    () -> defaults.validateChatProjected(request, plan, true, true));
            assertEquals("chat_retrieval_budget_exceeded", rejection.reasonCode());
            assertDoesNotThrow(() -> defaults.validateChatProjected(
                    request.toBuilder().searchMode(SearchMode.AUTO).build(), plan, true, true));
            assertEquals(128L, TraceStore.get("public.request.budget.retrievalWork"));
            assertEquals(768L, TraceStore.get("public.request.budget.providerWork"));
        }
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static PublicRequestBudgetGuard guardWithPlans() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ReflectionTestUtils.setField(guard, "planHintApplier",
                new PlanHintApplier(new DefaultResourceLoader()));
        guard.setMaxRetrievalWork(100_000);
        guard.setMaxProviderWork(100_000);
        return guard;
    }

    private static ChatRequestDto braveWebRequest() {
        return ChatRequestDto.builder()
                .message("recall this topic with bounded evidence")
                .useRag(false)
                .useWebSearch(true)
                .webTopK(8)
                .searchQueries(0)
                .searchMode(SearchMode.AUTO)
                .build();
    }

    @Test
    void bravePlanBurstBeyondPolicyCapDoesNotInflateQueryMultiplier() {
        PublicRequestBudgetGuard guard = guardWithPlans();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());

        assertDoesNotThrow(() -> guard.validateChatProjected(
                braveWebRequest(), applier.load("brave.v1"), true, false));
        // BALANCED apply 캡(10) + extremeZ 버스트(12) = 22 — 종전 24는
        // max(planQueries, workflow) 팽창의 잔재였다.
        assertEquals(22L, TraceStore.get("public.request.budget.branchCount"));
        assertEquals(396L, TraceStore.get("public.request.budget.retrievalWork"));
        assertEquals(12, ((Number) TraceStore.get("public.request.budget.extremeZQueries")).intValue());
    }

    @Test
    void forceLightNeverProjectsPlannerFanout() {
        PublicRequestBudgetGuard guard = guardWithPlans();
        ChatRequestDto request = ChatRequestDto.builder()
                .message("quick lookup please")
                .useRag(false)
                .useWebSearch(true)
                .webTopK(8)
                .searchQueries(0)
                .searchMode(SearchMode.FORCE_LIGHT)
                .build();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());

        assertDoesNotThrow(() -> guard.validateChatProjected(
                request, applier.load("brave.v1"), true, false));
        // force_light: 플래너 생략 단일 질의(1) + extremeZ additive(12) = 13.
        assertEquals(13L, TraceStore.get("public.request.budget.branchCount"));
    }

    @Test
    void missingPlanAndMissingPolicyFallBackToPlannerBoundNotThirtyTwo() {
        PublicRequestBudgetGuard guard = guardWithPlans();
        ChatRequestDto request = ChatRequestDto.builder()
                .message("bounded projected web request")
                .useRag(false)
                .useWebSearch(true)
                .webTopK(4)
                .searchQueries(0)
                .searchMode(SearchMode.OFF)
                .build();

        assertDoesNotThrow(() -> guard.validateChatProjected(request, null, true, false));
        // policy==null이던 종전 상수 32 대신 플래너 인자 상한(기본 2)을 청구한다.
        assertEquals(2L, TraceStore.get("public.request.budget.plannedQueries"));
    }

    @Test
    void rejectionCarriesMachineDiagnosticFields() {
        PublicRequestBudgetGuard guard = guardWithPlans();
        guard.setMaxRetrievalWork(10);
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());

        try {
            guard.validateChatProjected(
                    braveWebRequest(), applier.load("brave.v1"), true, false);
            fail("expected rejection");
        } catch (PublicRequestBudgetGuard.Rejection rejection) {
            assertEquals("chat_retrieval_budget_exceeded", rejection.reasonCode());
            assertEquals(396L,
                    rejection.getBody().getProperties().get("projectedRetrievalWork"));
            assertEquals("web_topK", rejection.getBody().getProperties().get("dominantTerm"));
        }
    }
}
