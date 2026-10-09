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
    void earlyRejectionClearsThePreviousRequestsQueryMultipliers() {
        var guard = new PublicRequestBudgetGuard();
        guard.validateChatProjected(braveWebRequest(), null, true, false);
        assertEquals(3L, TraceStore.get("public.request.budget.webQueryMultiplier"));

        var rejection = org.junit.jupiter.api.Assertions.assertThrows(PublicRequestBudgetGuard.Rejection.class,
                () -> guard.validateChat(ChatRequestDto.builder().message("").build()));
        assertEquals("chat_message_required", rejection.reasonCode());
        org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("public.request.budget.queryMultiplier"));
        org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("public.request.budget.webQueryMultiplier"));
    }

    @Test
    void koreanAutoBraveRequestDoesNotChargeExtremeZWebQueriesBeyondSharedBudget() {
        var budget = com.example.lms.service.rag.SelfAskSearchBudget.beginRequest(
                com.example.lms.domain.enums.ExecutionMode.AUTO);
        budget.allowExpansion("evidence-gap");
        int admittedWebQueries = 0;
        for (int i = 0; i < 15; i++) {
            if (budget.tryQuery("synthetic web query " + i)) admittedWebQueries++;
        }
        assertEquals(3, admittedWebQueries);

        var request = ChatRequestDto.builder()
                .message("원신에서 스커크랑 조합이 좋은 캐릭터가 뭐냐?")
                .model("chatgpt-oauth:gpt-5.6-sol").strictModelSelection(true)
                .executionMode(com.example.lms.domain.enums.ExecutionMode.AUTO)
                .useRag(true).useWebSearch(true).webTopK(8).searchQueries(0)
                .searchMode(SearchMode.AUTO).build();
        var plans = new PlanHintApplier(new DefaultResourceLoader());
        var guard = new PublicRequestBudgetGuard();
        assertDoesNotThrow(() -> guard.validateChatProjected(request, plans.load("brave.v1"), true, true));
        // Web shares three logical queries; vector retains all twelve ExtremeZ branches.
        assertEquals(174L, TraceStore.get("public.request.budget.retrievalWork"));
        assertEquals(648L, TraceStore.get("public.request.budget.providerWork"));
        assertEquals(18L, TraceStore.get("public.request.budget.branchCount"));
        assertEquals((long) admittedWebQueries, TraceStore.get("public.request.budget.webQueryMultiplier"));
        assertEquals(15L, TraceStore.get("public.request.budget.queryMultiplier"));
        assertEquals(12, TraceStore.get("public.request.budget.extremeZQueries"));
        assertEquals(SearchMode.AUTO, request.getSearchMode());
        assertEquals(com.example.lms.domain.enums.ExecutionMode.AUTO, request.getExecutionMode());
        assertEquals(Boolean.TRUE, request.getStrictModelSelection());

        guard.setMaxRetrievalWork(174);
        assertDoesNotThrow(() -> guard.validateChatProjected(request, plans.load("brave.v1"), true, true));
        guard.setMaxRetrievalWork(173);
        var rejection = org.junit.jupiter.api.Assertions.assertThrows(PublicRequestBudgetGuard.Rejection.class,
                () -> guard.validateChatProjected(request, plans.load("brave.v1"), true, true));
        assertEquals("chat_retrieval_budget_exceeded", rejection.reasonCode());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.example.lms.domain.enums.ExecutionMode.class)
    void defaultDeepSearchFitsTheExistingCaps(com.example.lms.domain.enums.ExecutionMode executionMode) {
        for (String message : java.util.List.of(
                "Spring Security의 세션과 CSRF 관계를 공식 자료로 설명해 주세요.",
                "Explain the Spring Security session and CSRF relationship using official sources.")) {
            ChatRequestDto request = ChatRequestDto.builder().message(message)
                    .executionMode(executionMode).useRag(true).useWebSearch(true)
                    .webTopK(8).searchQueries(0).searchMode(SearchMode.FORCE_DEEP).build();
            assertDoesNotThrow(() -> new PublicRequestBudgetGuard()
                    .validateChatProjected(request, null, true, true));
        }
    }

    @Test
    void policyFanoutReservesTheExecutableEvidenceGapEnvelope() {
        var budget = com.example.lms.service.rag.SelfAskSearchBudget.beginRequest(
                com.example.lms.domain.enums.ExecutionMode.AUTO);
        budget.allowExpansion("evidence-gap");
        int admittedQueries = 0;
        for (String query : java.util.List.of("synthetic original", "synthetic supplement one",
                "synthetic supplement two", "synthetic excess")) {
            if (budget.tryQuery(query)) admittedQueries++;
        }
        assertEquals(3, admittedQueries, "the execution owner refuses a fourth logical query");
        ChatRequestDto request = braveWebRequest().toBuilder()
                .searchMode(SearchMode.FORCE_DEEP).build();
        guardWithPlans().validateChatProjected(request, null, true, false);
        assertEquals((long) admittedQueries, TraceStore.get("public.request.budget.plannedQueries"));
    }

    @Test
    void explicitClientQueryOverflowStillRejectsWithDefaultCaps() {
        ChatRequestDto request = braveWebRequest().toBuilder().useRag(true)
                .webTopK(9).searchQueries(8).searchMode(SearchMode.FORCE_DEEP).build();
        var rejection = org.junit.jupiter.api.Assertions.assertThrows(
                PublicRequestBudgetGuard.Rejection.class,
                () -> new PublicRequestBudgetGuard().validateChatProjected(request, null, true, true));
        assertEquals("chat_retrieval_budget_exceeded", rejection.reasonCode());
    }

    @Test
    void additionalExtremeZOverflowStillRejectsWithDefaultCaps() {
        var plan = new com.example.lms.plan.PlanHints("overflow", null, null,
                java.util.List.of(), 8, 8, null, java.util.List.of(), null, null, null,
                true, true, null, null, null, null, null, null, 32, true, java.util.Map.of());
        var rejection = org.junit.jupiter.api.Assertions.assertThrows(
                PublicRequestBudgetGuard.Rejection.class,
                () -> new PublicRequestBudgetGuard().validateChatProjected(
                        braveWebRequest().toBuilder().useRag(true)
                                .searchMode(SearchMode.FORCE_DEEP).build(), plan, true, true));
        assertEquals("chat_retrieval_budget_exceeded", rejection.reasonCode());
    }

    @Test
    void historicalDeepOptionsFitTheBoundedWorkflowEnvelope() {
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
            assertEquals(3L, TraceStore.get("public.request.budget.plannedQueries"));
            assertEquals(14, TraceStore.get("public.request.budget.policyMaxFinalQueries"));
            assertEquals(3, TraceStore.get("public.request.budget.workflowQueries"));
            assertEquals(2, TraceStore.get("public.request.budget.extremeZQueries"));
            // (web11 * shared3 + vector10 * (workflow3 + ExtremeZ2)) * DEEP2.
            assertEquals(166L, TraceStore.get("public.request.budget.retrievalWork"));
            assertEquals(792L, TraceStore.get("public.request.budget.providerWork"));

            var defaults = new PublicRequestBudgetGuard();
            assertDoesNotThrow(() -> defaults.validateChatProjected(request, plan, true, true));
            assertDoesNotThrow(() -> defaults.validateChatProjected(
                    request.toBuilder().searchMode(SearchMode.AUTO).build(), plan, true, true));
            assertEquals(64L, TraceStore.get("public.request.budget.retrievalWork"));
            assertEquals(288L, TraceStore.get("public.request.budget.providerWork"));
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
        // Workflow and ExtremeZ web share envelope3; tuned plan webK18.
        assertEquals(3L, TraceStore.get("public.request.budget.branchCount"));
        assertEquals(54L, TraceStore.get("public.request.budget.retrievalWork"));
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
        // force_light keeps the shared web envelope at one logical query.
        assertEquals(1L, TraceStore.get("public.request.budget.branchCount"));
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
            assertEquals(54L,
                    rejection.getBody().getProperties().get("projectedRetrievalWork"));
            assertEquals("web_topK", rejection.getBody().getProperties().get("dominantTerm"));
        }
    }
}
