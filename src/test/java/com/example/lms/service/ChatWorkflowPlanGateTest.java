package com.example.lms.service;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanExecutionSpec;
import com.example.lms.search.TraceStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused coverage for the chat-path plan gate helpers in
 * {@link ChatWorkflow}: {@code plan.when} scope evaluation, expansion-key
 * suppression, post-retrieval metric observation, and stage-ledger evidence
 * mapping. All assertions are on real TraceStore markers — no fabricated
 * evidence.
 */
class ChatWorkflowPlanGateTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        com.example.lms.nova.NovaRequestContext.setBrave(false);
        trace.TraceContext.setBrave(false);
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
        com.example.lms.nova.NovaRequestContext.setBrave(false);
        trace.TraceContext.setBrave(false);
    }

    private static PlanExecutionSpec braveSpec() {
        Map<String, Object> planNode = new LinkedHashMap<>();
        planNode.put("when", Map.of("any", List.of(
                "request.header.X-Brave-Mode == \"on\"",
                "metrics.initial_recall < 0.35")));
        planNode.put("pipeline", List.of(
                "analyze.selfAsk",
                "expand.queryBurst",
                "retrieve.dynamicChain",
                "fuse.rrf.weighted",
                "rerank.crossEncoder.onnx"));
        return PlanExecutionSpec.parse(planNode);
    }

    @Test
    void chatScopeExposesBraveHeaderChannel() {
        com.example.lms.nova.NovaRequestContext.setBrave(true);
        Map<String, Object> scope = ChatWorkflow.chatPlanScope(null);
        assertEquals("on", scope.get("request.header.x-brave-mode"));

        com.example.lms.nova.NovaRequestContext.setBrave(false);
        trace.TraceContext.setBrave(true);
        scope = ChatWorkflow.chatPlanScope(null);
        assertEquals("on", scope.get("request.header.x-brave-mode"));

        trace.TraceContext.setBrave(false);
        scope = ChatWorkflow.chatPlanScope(null);
        assertEquals("off", scope.get("request.header.x-brave-mode"));
    }

    @Test
    void falseWhenSuppressesPlanDrivenExpansionOnChatScope() {
        PlanExecutionSpec spec = braveSpec();
        assertTrue(spec.declaresExpansion());
        // Not brave and no metric producer -> header condition FALSE, metric
        // UNKNOWN -> any-OR keeps the verdict UNKNOWN, not FALSE.
        PlanExecutionSpec.WhenVerdict verdict = spec.evaluateWhen(ChatWorkflow.chatPlanScope(null));
        assertEquals(PlanExecutionSpec.TriState.UNKNOWN, verdict.state());

        // A plan whose only condition is the brave header resolves FALSE when
        // the header is off; that is the only case allowed to suppress.
        Map<String, Object> planNode = new LinkedHashMap<>();
        planNode.put("when", Map.of("any", List.of("request.header.X-Brave-Mode == \"on\"")));
        planNode.put("pipeline", List.of("expand.queryBurst"));
        PlanExecutionSpec strictSpec = PlanExecutionSpec.parse(planNode);
        assertEquals(PlanExecutionSpec.TriState.FALSE,
                strictSpec.evaluateWhen(ChatWorkflow.chatPlanScope(null)).state());

        trace.TraceContext.setBrave(true);
        assertEquals(PlanExecutionSpec.TriState.TRUE,
                strictSpec.evaluateWhen(ChatWorkflow.chatPlanScope(null)).state());
    }

    @Test
    void stripPlanExpansionKeysPreservesCallerKeys() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("selfask.enabled", "true");
        meta.put("expand.selfAsk.count", 3);
        meta.put("queryBurst.maxQueries", 5);
        meta.put("overdrive.enabled", true);
        meta.put("extremeZ.level", "high");
        meta.put("enableSelfAsk", "true");
        meta.put("allowWeb", "true");
        meta.put("rerank.keepN", 8);
        meta.put("plan.expansion.gated", "when_false");

        ChatWorkflow.stripPlanExpansionKeys(meta);

        assertNull(meta.get("selfask.enabled"));
        assertNull(meta.get("expand.selfAsk.count"));
        assertNull(meta.get("queryBurst.maxQueries"));
        assertNull(meta.get("overdrive.enabled"));
        assertNull(meta.get("extremeZ.level"));
        assertEquals("true", meta.get("enableSelfAsk"));
        assertEquals("true", meta.get("allowWeb"));
        assertEquals(8, meta.get("rerank.keepN"));
        assertEquals("when_false", meta.get("plan.expansion.gated"));
    }

    @Test
    void stageEvidenceReflectsOnlyRealMarkers() {
        // No markers -> no evidence, no fabrication.
        Map<String, Object> evidence = ChatWorkflow.chatStageEvidence(List.of(), "onnx");
        assertFalse(evidence.containsKey("stage.web"));
        assertFalse(evidence.containsKey("stage.vector"));
        assertFalse(evidence.containsKey("stage.kg"));
        assertFalse(evidence.containsKey("stage.fuse"));
        assertFalse(evidence.containsKey("selfAsk"));
        assertFalse(evidence.containsKey("stage.onnx"));

        TraceStore.put("rag.fusion.sizes.web", 4);
        TraceStore.put("rag.fusion.sizes.vector", 6);
        TraceStore.put("rag.fusion.sizes.kg", 2);
        TraceStore.put("rag.fusion.final.totalCount", 9);
        TraceStore.put("rag.selfask.count", 3);
        evidence = ChatWorkflow.chatStageEvidence(List.of(), "onnx");
        assertEquals("success:4", evidence.get("stage.web"));
        assertEquals("success:6", evidence.get("stage.vector"));
        assertEquals("success:2", evidence.get("stage.kg"));
        assertEquals(9, evidence.get("stage.fuse"));
        assertEquals("enabled", evidence.get("selfAsk"));

        // web skipped marker wins over sizes; rerank fallback reports error.
        TraceStore.put("retrieval.web.skipped", true);
        TraceStore.put("rerank.fallback", true);
        evidence = ChatWorkflow.chatStageEvidence(
                List.of(dev.langchain4j.rag.content.Content.from("doc")), "onnx");
        assertEquals("disabled", evidence.get("stage.web"));
        assertEquals("error:rerank_fallback", evidence.get("stage.onnx"));
    }

    @Test
    void stageFlagsGateExpansionOnlyOnConclusiveFalse() {
        OrchestrationHints hints = OrchestrationHints.builder()
                .enableSelfAsk(true)
                .enableCrossEncoder(true)
                .build();
        PlanExecutionSpec spec = braveSpec();

        PlanExecutionSpec.WhenVerdict unknown =
                spec.evaluateWhen(Map.of());
        PlanExecutionSpec.StageFlags flags =
                ChatWorkflow.chatStageFlags(hints, Map.of(), unknown);
        assertTrue(flags.expansionEligible());
        assertTrue(flags.selfAskOn());
        assertTrue(flags.onnxOn());
        assertFalse(flags.biEncoderOn());
        assertFalse(flags.diversityOn());

        PlanExecutionSpec.WhenVerdict falseVerdict =
                new PlanExecutionSpec.WhenVerdict(PlanExecutionSpec.TriState.FALSE, List.of());
        flags = ChatWorkflow.chatStageFlags(hints, Map.of(), falseVerdict);
        assertFalse(flags.expansionEligible());

        // A null verdict (spec not loaded) must not suppress eligibility.
        assertTrue(ChatWorkflow.chatStageFlags(hints, Map.of(), null).expansionEligible());
    }

    @Test
    void observedMetricsComeFromFusionMarkersOnly() {
        assertTrue(ChatWorkflow.chatPlanObservedMetrics().isEmpty());

        TraceStore.put("rag.fusion.final.totalCount", 7);
        Map<String, Object> metrics = ChatWorkflow.chatPlanObservedMetrics();
        assertEquals(7, metrics.get("metrics.result_count"));
        assertEquals(false, metrics.get("metrics.empty_result"));

        TraceStore.put("rag.fusion.final.totalCount", 0);
        metrics = ChatWorkflow.chatPlanObservedMetrics();
        assertEquals(true, metrics.get("metrics.empty_result"));
        // initial_recall has no producer on the chat path -> stays absent.
        assertFalse(metrics.containsKey("metrics.initial_recall"));
    }

    @Test
    void endToEndLedgerClaimsOnlyMarkedStages() {
        PlanExecutionSpec spec = braveSpec();
        TraceStore.put("rag.fusion.sizes.web", 5);
        TraceStore.put("rag.fusion.final.totalCount", 5);

        OrchestrationHints hints = OrchestrationHints.builder()
                .enableSelfAsk(false)
                .enableCrossEncoder(false)
                .build();
        List<PlanExecutionSpec.StageEntry> ledger = spec.stageLedger(
                ChatWorkflow.chatStageEvidence(List.of(), "onnx"),
                ChatWorkflow.chatStageFlags(hints, Map.of(),
                        spec.evaluateWhen(ChatWorkflow.chatPlanScope(null))));
        Map<String, String> statusByStage = new LinkedHashMap<>();
        for (PlanExecutionSpec.StageEntry entry : ledger) {
            statusByStage.put(entry.stage(), entry.status().name());
        }
        // Retrieval fused 5 docs -> real evidence; declared-but-unrun stages
        // must not claim execution.
        assertEquals("EXECUTED", statusByStage.get("retrieve.dynamicChain"));
        assertEquals("EXECUTED", statusByStage.get("fuse.rrf.weighted"));
        String selfAskStatus = statusByStage.get("analyze.selfAsk");
        assertNotNull(selfAskStatus);
        assertFalse("EXECUTED".equals(selfAskStatus));
        String onnxStatus = statusByStage.get("rerank.crossEncoder.onnx");
        assertNotNull(onnxStatus);
        assertFalse("EXECUTED".equals(onnxStatus));
    }
}
