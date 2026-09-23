package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.failpattern.aop.FailurePatternCooldownDiagnosticsAspect;
import ai.abandonware.nova.orch.failpattern.aop.RetrievalOrderFeedbackAspect;
import ai.abandonware.nova.boot.exec.zombie.ZombieBreederContainmentAspect;
import com.example.lms.cfvm.DecisionTraceAspect;
import com.example.lms.aop.RetrievalDiagAspect;
import com.example.lms.prompt.pose.PromptPoseApplicationAspect;
import com.example.lms.prompt.pose.PromptPoseRewardAspect;
import com.example.lms.prompt.pose.PromptPoseRoutingPlanAspect;
import com.example.lms.resilience.SemaphoreGateAspect;
import com.example.lms.resilience.SingleFlightAspect;
import com.example.lms.risk.TopKShrinkerAspect;
import com.example.lms.service.rag.graph.BrainStateChatWorkflowAspect;
import com.example.lms.trace.LlmTraceAspect;
import com.example.lms.trace.OrchestrationHotspotAspect;
import com.example.lms.trace.PromptTraceAspect;
import com.example.lms.trace.SearchTraceAspect;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AspectOrderingContractTest {

    @Test
    void criticalCrossSubsystemAspectsDeclareExplicitLowPrecedenceOrder() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 300, orderOf(ExtremeZBurstAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 250, orderOf(RagCompressionAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 200, orderOf(LlmRouterAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 150, orderOf(FallbackBannerAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 125, orderOf(OrchestrationHotspotAspect.class));
    }

    @Test
    void extremeZWrapsCompressionSoItCanObserveCompressedBaseResults() {
        assertTrue(orderOf(ExtremeZBurstAspect.class) < orderOf(RagCompressionAspect.class));
    }

    @Test
    void fallbackBannerRunsAfterLlmRoutingButBeforeTerminalTraceInjection() {
        assertTrue(orderOf(LlmRouterAspect.class) < orderOf(FallbackBannerAspect.class));
        assertTrue(orderOf(FallbackBannerAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void orchestrationHotspotRunsAsLateDiagnosticBeforeTerminalTraceInjection() {
        assertTrue(orderOf(FallbackBannerAspect.class) < orderOf(OrchestrationHotspotAspect.class));
        assertTrue(orderOf(OrchestrationHotspotAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void guardrailAnchorTailRunsInEarlyAnchorTailBand() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 45, orderOf(GuardrailQueryPreprocessorAnchorTailAspect.class));
    }

    @Test
    void anchorTailPreprocessorsShareEarlyAnchorTailBand() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 45, orderOf(QueryTransformerAnchorTailAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 45, orderOf(GuardrailQueryPreprocessorAnchorTailAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 45, orderOf(QueryAnalysisAnchorTailAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 45, orderOf(KeywordSelectionAnchorTailAspect.class));
        assertTrue(orderOf(QueryAnalysisAnchorTailAspect.class) < orderOf(FailurePatternCooldownDiagnosticsAspect.class));
        assertTrue(orderOf(KeywordSelectionAnchorTailAspect.class) < orderOf(FailurePatternCooldownDiagnosticsAspect.class));
    }

    @Test
    void braveQueryBurstRunsInQueryExpansionBand() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 100, orderOf(BraveQueryBurstAspect.class));
        assertTrue(orderOf(GuardrailQueryPreprocessorAnchorTailAspect.class) < orderOf(BraveQueryBurstAspect.class));
        assertTrue(orderOf(BraveQueryBurstAspect.class) < orderOf(FailSoftQueryAugmentAspect.class));
    }

    @Test
    void workflowPlanMisrouteHatchRunsBeforeQueryExpansion() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 90, orderOf(WorkflowPlanMisrouteHatchAspect.class));
        assertTrue(orderOf(PromptPoseRoutingPlanAspect.class) < orderOf(WorkflowPlanMisrouteHatchAspect.class));
        assertTrue(orderOf(WorkflowPlanMisrouteHatchAspect.class) < orderOf(BraveQueryBurstAspect.class));
    }

    @Test
    void uawAskPipelinesRunBeforePromptRouting() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 70, orderOf(UawIdleAutoTrainingPipelineAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 75, orderOf(UawAutolearnStrictRequestAspect.class));
        assertTrue(orderOf(UawTickTraceSeedAspect.class) < orderOf(UawIdleAutoTrainingPipelineAspect.class));
        assertTrue(orderOf(UawIdleAutoTrainingPipelineAspect.class) < orderOf(UawAutolearnStrictRequestAspect.class));
        assertTrue(orderOf(UawAutolearnStrictRequestAspect.class) < orderOf(PromptPoseRoutingPlanAspect.class));
    }

    @Test
    void failurePatternCooldownDiagnosticsRunsBeforeFailSoftQueryAugment() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 110, orderOf(FailurePatternCooldownDiagnosticsAspect.class));
        assertTrue(orderOf(BraveQueryBurstAspect.class) < orderOf(FailurePatternCooldownDiagnosticsAspect.class));
        assertTrue(orderOf(FailurePatternCooldownDiagnosticsAspect.class) < orderOf(FailSoftQueryAugmentAspect.class));
        assertTrue(orderOf(FailurePatternCooldownDiagnosticsAspect.class) < orderOf(GuardDebugTraceAspect.class));
    }

    @Test
    void retrievalOrderFeedbackRunsInsideFailPatternBand() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 115, orderOf(RetrievalOrderFeedbackAspect.class));
        assertTrue(orderOf(FailurePatternCooldownDiagnosticsAspect.class) < orderOf(RetrievalOrderFeedbackAspect.class));
        assertTrue(orderOf(RetrievalOrderFeedbackAspect.class) < orderOf(FailSoftQueryAugmentAspect.class));
    }

    @Test
    void conversationBreadcrumbWrapsWorkflowBeforeLateDiagnostics() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 8, orderOf(ConversationBreadcrumbAspect.class));
        assertTrue(orderOf(ConversationBreadcrumbAspect.class) < orderOf(GuardDebugTraceAspect.class));
    }

    @Test
    void zombieContainmentRunsBeforeInterruptAndWorkflowAspects() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 2, orderOf(ZombieBreederContainmentAspect.class));
        assertTrue(orderOf(ZombieBreederContainmentAspect.class) < orderOf(HybridWebSearchInterruptHygieneAspect.class));
        assertTrue(orderOf(ZombieBreederContainmentAspect.class) < orderOf(ConversationBreadcrumbAspect.class));
    }

    @Test
    void uawTickTraceSeedsBeforeContainmentAndWorkflowAspects() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, orderOf(UawTickTraceSeedAspect.class));
        assertTrue(orderOf(UawTickTraceSeedAspect.class) < orderOf(ZombieBreederContainmentAspect.class));
        assertTrue(orderOf(UawTickTraceSeedAspect.class) < orderOf(ConversationBreadcrumbAspect.class));
    }

    @Test
    void retrievalTopKShrinkRunsBeforeSingleFlightDedupe() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 12, orderOf(TopKShrinkerAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 14, orderOf(SingleFlightAspect.class));
        assertTrue(orderOf(NaverInterruptHygieneAspect.class) < orderOf(TopKShrinkerAspect.class));
        assertTrue(orderOf(TopKShrinkerAspect.class) < orderOf(SingleFlightAspect.class));
        assertTrue(orderOf(SingleFlightAspect.class) < orderOf(BraveOperationalGateAspect.class));
    }

    @Test
    void onnxSemaphoreGateRunsInEarlyResourceGateBand() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 18, orderOf(SemaphoreGateAspect.class));
        assertTrue(orderOf(SingleFlightAspect.class) < orderOf(SemaphoreGateAspect.class));
        assertTrue(orderOf(SemaphoreGateAspect.class) < orderOf(ProviderRateLimitBackoffAspect.class));
    }

    @Test
    void nightmareBreakerWebRateLimitPropagatesInEarlyFailSoftBand() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 25, orderOf(NightmareBreakerWebRateLimitPropagatorAspect.class));
        assertTrue(orderOf(ProviderRateLimitBackoffAspect.class) < orderOf(NightmareBreakerWebRateLimitPropagatorAspect.class));
        assertTrue(orderOf(NightmareBreakerWebRateLimitPropagatorAspect.class) < orderOf(WebProviderStructuredLogAspect.class));
    }

    @Test
    void faultMaskCapRunsBeforePenaltyAndAblationFinalize() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 230, orderOf(FaultMaskIrregularityCapAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 240, orderOf(FaultMaskAblationPenaltyAspect.class));
        assertTrue(orderOf(FaultMaskIrregularityCapAspect.class) < orderOf(FaultMaskAblationPenaltyAspect.class));
        assertTrue(orderOf(FaultMaskAblationPenaltyAspect.class) < orderOf(UawAblationFinalizeAspect.class));
    }

    @Test
    void optionalIrregularityCapRunsBeforeFaultMaskCap() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 220, orderOf(OptionalIrregularityCapAspect.class));
        assertTrue(orderOf(FailSoftQueryAugmentAspect.class) < orderOf(OptionalIrregularityCapAspect.class));
        assertTrue(orderOf(OptionalIrregularityCapAspect.class) < orderOf(FaultMaskIrregularityCapAspect.class));
    }

    @Test
    void llmCallTraceRunsAsLateDiagnosticBeforeTerminalTraceInjection() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 100, orderOf(LlmCallTraceAspect.class));
        assertTrue(orderOf(OrchestrationHotspotAspect.class) < orderOf(LlmCallTraceAspect.class));
        assertTrue(orderOf(LlmCallTraceAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void retrievalDiagnosticsRunInLateDiagnosticBand() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 120, orderOf(RetrievalDiagAspect.class));
        assertTrue(orderOf(OrchestrationHotspotAspect.class) < orderOf(RetrievalDiagAspect.class));
        assertTrue(orderOf(RetrievalDiagAspect.class) < orderOf(PromptTraceAspect.class));
    }

    @Test
    void promptAndPortTraceRunBeforeLangchainCallTrace() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 110, orderOf(PromptTraceAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 105, orderOf(LlmTraceAspect.class));
        assertTrue(orderOf(PromptPoseRoutingPlanAspect.class) < orderOf(PromptTraceAspect.class));
        assertTrue(orderOf(PromptTraceAspect.class) < orderOf(LlmTraceAspect.class));
        assertTrue(orderOf(LlmTraceAspect.class) < orderOf(LlmCallTraceAspect.class));
        assertTrue(orderOf(LlmCallTraceAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void promptPoseRewardRunsAsLateFeedbackInsideGuardDebugTrace() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, orderOf(PromptPoseApplicationAspect.class));
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 80, orderOf(PromptPoseRoutingPlanAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 90, orderOf(GuardDebugTraceAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 80, orderOf(PromptPoseRewardAspect.class));
        assertTrue(orderOf(PromptPoseApplicationAspect.class) < orderOf(PromptPoseRoutingPlanAspect.class));
        assertTrue(orderOf(GuardDebugTraceAspect.class) < orderOf(PromptPoseRewardAspect.class));
        assertTrue(orderOf(PromptPoseRewardAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void searchTraceRunsAfterPromptPoseRewardBeforeTerminalTraceInjection() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 70, orderOf(SearchTraceAspect.class));
        assertTrue(orderOf(PromptPoseRewardAspect.class) < orderOf(SearchTraceAspect.class));
        assertTrue(orderOf(SearchTraceAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void cfvmDecisionTraceRunsInLateTraceBand() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 65, orderOf(DecisionTraceAspect.class));
        assertTrue(orderOf(SearchTraceAspect.class) < orderOf(DecisionTraceAspect.class));
        assertTrue(orderOf(DecisionTraceAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    @Test
    void memoryKnowledgeAndRollingSummaryRunBeforeOutputCleaning() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 60, orderOf(MemoryDegradedAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 55, orderOf(KnowledgeBasePersistenceAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 50, orderOf(ChunkRollingSummaryAspect.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 45, orderOf(RollingSummaryHistoryAspect.class));
        assertTrue(orderOf(DecisionTraceAspect.class) < orderOf(MemoryDegradedAspect.class));
        assertTrue(orderOf(MemoryDegradedAspect.class) < orderOf(KnowledgeBasePersistenceAspect.class));
        assertTrue(orderOf(KnowledgeBasePersistenceAspect.class) < orderOf(ChunkRollingSummaryAspect.class));
        assertTrue(orderOf(ChunkRollingSummaryAspect.class) < orderOf(RollingSummaryHistoryAspect.class));
        assertTrue(orderOf(RollingSummaryHistoryAspect.class) < orderOf(CleanOutputRedactionAspect.class));
    }

    @Test
    void brainStateChatCaptureRunsAfterRollingSummaryBeforeOutputCleaning() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 40, orderOf(BrainStateChatWorkflowAspect.class));
        assertTrue(orderOf(RollingSummaryHistoryAspect.class) < orderOf(BrainStateChatWorkflowAspect.class));
        assertTrue(orderOf(BrainStateChatWorkflowAspect.class) < orderOf(CleanOutputRedactionAspect.class));
    }

    @Test
    void cleanOutputRedactionRunsJustOutsideTerminalTraceInjection() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 5, orderOf(CleanOutputRedactionAspect.class));
        assertTrue(orderOf(DecisionTraceAspect.class) < orderOf(CleanOutputRedactionAspect.class));
        assertTrue(orderOf(CleanOutputRedactionAspect.class) < orderOf(EvidenceListTraceInjectionAspect.class));
    }

    private static int orderOf(Class<?> type) {
        Order order = AnnotationUtils.findAnnotation(type, Order.class);
        assertNotNull(order, () -> type.getSimpleName() + " must declare @Order");
        return order.value();
    }
}
