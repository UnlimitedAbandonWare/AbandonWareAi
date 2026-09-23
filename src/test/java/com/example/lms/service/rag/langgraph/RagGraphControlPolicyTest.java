package com.example.lms.service.rag.langgraph;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagGraphControlPolicyTest {

    @Test
    void strikeCannotBeRelaxedAndSuppressesSpeculativeBoosters() {
        QueryRequest request = new QueryRequest();
        request.jamminiMode = "STRIKE";
        request.planId = "brave.v1";
        request.aggressive = true;
        request.deepResearch = true;
        request.enableSelfAsk = true;
        request.topK = 20;

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());
        RagGraphControlPolicy.applyPolicy(decision, request);

        assertEquals(RagGraphControlPolicy.SafetyMode.STRIKE, decision.safetyMode());
        assertEquals(RagGraphControlPolicy.RetrievalPosture.LOCKDOWN, decision.retrievalPosture());
        assertEquals("strike.signal", decision.policyRuleId());
        assertTrue(decision.controlScore() < 0.30d);
        assertFalse(decision.promotionEligible());
        assertTrue(decision.promotionBlockers().contains("low_transition_score"));
        assertFalse(request.enableSelfAsk);
        assertFalse(request.aggressive);
        assertFalse(request.deepResearch);
        assertTrue(request.whitelistOnly);
        assertTrue(request.topK <= 8);
    }

    @Test
    void strictWeakEvidenceRoutesToStrictVerify() {
        QueryRequest request = new QueryRequest();
        request.jamminiMode = "STRICT";
        QueryResponse response = responseWithSources("only-one-source");

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());
        decision = RagGraphControlPolicy.afterQuality(decision, request, response, "", "", response.results.size());

        assertEquals(RagGraphControlPolicy.FailureAction.STRICT_VERIFY, decision.failureAction());
        assertEquals("strict_verify", RagGraphControlPolicy.route(decision));
        assertTrue(decision.transitionScore() < decision.controlScore());
        assertTrue(decision.promotionBlockers().contains("strict_verify_pending"));
    }

    @Test
    void nonFiniteDistinctSourceDebugDoesNotBypassStrictEvidence() {
        TraceStore.clear();
        QueryRequest request = new QueryRequest();
        request.jamminiMode = "STRICT";
        QueryResponse response = responseWithSources("same-source", "same-source");
        response.debug.put("rag.eval.distinctSourceCount", Double.POSITIVE_INFINITY);

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());
        decision = RagGraphControlPolicy.afterQuality(decision, request, response, "", "", response.results.size());

        assertEquals(RagGraphControlPolicy.FailureAction.STRICT_VERIFY, decision.failureAction());
        assertEquals("invalid_number", TraceStore.get("langgraph.control.suppressed.intValue.errorType"));
        TraceStore.clear();
    }

    @Test
    void zeroResultsRouteToOneRepairPass() {
        QueryRequest request = new QueryRequest();
        QueryResponse response = new QueryResponse();

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());
        decision = RagGraphControlPolicy.afterQuality(decision, request, response, "", "empty_results", 0);

        assertEquals(RagGraphControlPolicy.FailureAction.REPAIR, decision.failureAction());
        assertEquals("repair", RagGraphControlPolicy.route(decision));
        assertTrue(decision.promotionBlockers().contains("repair_pending"));
    }

    @Test
    void providerDisabledRoutesFailSoftWithoutRepair() {
        QueryRequest request = new QueryRequest();
        QueryResponse response = new QueryResponse();
        response.debug.put("rag.eval.providerDisabledSignals", List.of("web:provider-disabled"));

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());
        decision = RagGraphControlPolicy.afterQuality(decision, request, response, "", "empty_results", 0);

        assertEquals(RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE, decision.failureAction());
        assertEquals("fail_soft_finalize", RagGraphControlPolicy.route(decision));
        assertFalse(decision.promotionEligible());
        assertTrue(decision.promotionBlockers().contains("fail_soft_finalize"));
    }

    @Test
    void policyTableRowsCarryStableScoresAndRuleIds() {
        QueryRequest request = new QueryRequest();
        request.planId = "brave.v1";

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());

        assertEquals("relaxed.signal", decision.policyRuleId());
        assertEquals(RagGraphControlPolicy.SafetyMode.RELAXED, decision.safetyMode());
        assertTrue(decision.controlScore() >= 0.70d);
        assertTrue(decision.promotionEligible());
        Map<String, Object> firstRow = decision.transitionTrace().get(0);
        assertEquals("decide_control", firstRow.get("node"));
        assertEquals("relaxed.signal", firstRow.get("ruleId"));
        assertEquals(decision.transitionScore(), (Double) firstRow.get("scoreAfter"), 0.0001d);
    }

    @Test
    void transitionTraceScoresInvokeFallbackAsPromotionBlocker() {
        QueryRequest request = new QueryRequest();
        QueryResponse response = new QueryResponse();

        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(request, new LinkedHashMap<>());
        double startScore = decision.transitionScore();
        decision = decision.withAction(decision.failureAction(), "graph_invoke_npe", "invoke_fallback");
        decision = RagGraphControlPolicy.afterQuality(decision, request, response,
                "", "empty_results", 0);

        assertEquals(RagGraphControlPolicy.FailureAction.REPAIR, decision.failureAction());
        assertTrue(decision.transitionScore() < startScore);
        assertFalse(decision.promotionEligible());
        assertTrue(decision.promotionBlockers().contains("invoke_fallback"),
                "blockers=" + decision.promotionBlockers() + " trace=" + decision.transitionTrace());
        Map<String, Object> lastRow = decision.transitionTrace().get(decision.transitionTrace().size() - 1);
        assertEquals("quality_gate", lastRow.get("node"));
        assertTrue(((Number) lastRow.get("scoreDelta")).doubleValue() < 0.0d);
        assertEquals(decision.transitionScore(), (Double) lastRow.get("scoreAfter"), 0.0001d);
    }

    @Test
    void publicDebugReasonCodesUseLabelOrHash() {
        String rawReason = "student private control reason";
        RagGraphControlPolicy.Decision decision = new RagGraphControlPolicy.Decision(
                RagGraphControlPolicy.SafetyMode.NORMAL,
                RagGraphControlPolicy.RetrievalPosture.NORMAL,
                RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                rawReason,
                List.of(Map.of(
                        "node", rawReason,
                        "action", "FAIL_SOFT_FINALIZE",
                        "reasonCode", rawReason,
                        "ruleId", rawReason)),
                rawReason,
                0.30d,
                0.20d,
                false,
                List.of(rawReason));

        Map<String, Object> debug = new LinkedHashMap<>();
        RagGraphControlPolicy.writeDebug(debug, decision);

        String rendered = String.valueOf(debug);
        assertTrue(String.valueOf(debug.get("langgraph.control.reasonCode")).startsWith("hash:"));
        assertTrue(String.valueOf(debug.get("langgraph.control.policyRuleId")).startsWith("hash:"));
        assertTrue(String.valueOf(debug.get("langgraph.control.promotionBlockers")).contains("hash:"));
        assertFalse(rendered.contains("student"));
        assertFalse(rendered.contains(rawReason));
    }

    private static QueryResponse responseWithSources(String... sources) {
        QueryResponse response = new QueryResponse();
        int rank = 1;
        for (String source : sources) {
            Doc doc = new Doc();
            doc.id = "doc-" + rank;
            doc.title = "title";
            doc.snippet = "snippet";
            doc.source = source;
            doc.rank = rank++;
            doc.score = 1.0d;
            doc.meta = Map.of();
            response.results.add(doc);
        }
        return response;
    }
}
