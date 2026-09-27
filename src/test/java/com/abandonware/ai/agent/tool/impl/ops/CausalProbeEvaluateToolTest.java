package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.CausalProbeTriggerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CausalProbeEvaluateToolTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void projectsTypedOperatorScopeWithoutReturningRawValues() {
        OperatorProbeAuthorityStore authorityStore = new OperatorProbeAuthorityStore();
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(service(), authorityStore);
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);

        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "goal", "private operator goal",
                "allowedPatchCandidates", List.of("anchor_compression_topup"),
                "forbiddenActions", List.of("delete_repository"),
                "decisionQuestion", "private decision question",
                "queries", queries(),
                "retrievalBudget", retrievalBudget(),
                "dissentSignal", dissentSignal("independent-audit"),
                "stopRequested", false,
                "maxMillis", 250), new ToolContext("operator-session", null)));

        assertEquals("source_patch_candidate", response.data().get("action"));
        assertEquals("allowed", response.data().get("policyDecision"));
        assertEquals("probe_only", response.data().get("decisionAuthority"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals(true, response.data().get("operatorAuthorityIssued"));
        assertEquals("PROBE_AND_PATCH_CANDIDATE", response.data().get("operatorAuthorityMode"));
        assertEquals(true, response.data().get("dissentSignalEligible"));
        assertEquals("observed", response.data().get("dissentSignalLifecycle"));
        assertEquals("probe_only", response.data().get("dissentSignalDecisionAuthority"));
        assertEquals(1, response.data().get("dissentSignalProbeMaxAttempts"));
        assertTrue(String.valueOf(response.data().get("dissentSignalRef"))
                .matches("hash:[0-9a-f]{12}"));
        String authorityRef = String.valueOf(response.data().get("operatorAuthorityRef"));
        assertTrue(authorityRef.matches("hash:[0-9a-f]{12}"), authorityRef);
        assertEquals(250, authorityStore.peek(authorityRef, "operator-session").orElseThrow().maxMillis());
        assertTrue(String.valueOf(response.data().get("constraintHash")).length() >= 12);
        String publicPayload = response.data() + TraceStore.getByPrefix("causalProbe.").toString();
        assertFalse(publicPayload.contains("private operator goal"), publicPayload);
        assertFalse(publicPayload.contains("delete_repository"), publicPayload);
        assertFalse(publicPayload.contains("independent observed anomaly"), publicPayload);
        assertFalse(publicPayload.contains("independent-audit"), publicPayload);
    }

    @Test
    void missingOrCorrelatedDissentCannotMintProbeAuthority() {
        OperatorProbeAuthorityStore authorityStore = new OperatorProbeAuthorityStore();
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(service(), authorityStore);
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);

        ToolResponse missing = tool.execute(new ToolRequest(Map.of(
                "goal", "goal",
                "allowedPatchCandidates", List.of("anchor_compression_topup"),
                "decisionQuestion", "question",
                "queries", queries(),
                "retrievalBudget", retrievalBudget(),
                "stopRequested", false,
                "maxMillis", 250), new ToolContext("operator-session", null)));

        assertEquals(false, missing.data().get("operatorAuthorityIssued"));
        assertEquals(false, missing.data().get("dissentSignalEligible"));
        assertEquals("dissent_signal_missing", missing.data().get("dissentSignalReason"));
        assertFalse(missing.data().containsKey("dissentSignalLifecycle"));
        assertFalse(missing.data().containsKey("dissentSignalDecisionAuthority"));
        assertFalse(missing.data().containsKey("dissentSignalProbeMaxAttempts"));

        ToolResponse correlated = tool.execute(new ToolRequest(Map.of(
                "goal", "goal",
                "allowedPatchCandidates", List.of("anchor_compression_topup"),
                "decisionQuestion", "question",
                "queries", queries(),
                "retrievalBudget", retrievalBudget(),
                "dissentSignal", dissentSignal("primary-rag"),
                "stopRequested", false,
                "maxMillis", 250), new ToolContext("operator-session", null)));

        assertEquals(false, correlated.data().get("operatorAuthorityIssued"));
        assertEquals(false, correlated.data().get("dissentSignalEligible"));
        assertEquals("correlated_provenance", correlated.data().get("dissentSignalReason"));
        assertFalse(correlated.data().containsKey("operatorAuthorityRef"));
    }

    @Test
    void dissentSignalRefUsesUnambiguousCanonicalEncoding() {
        DissentSignal observationContainsDelimiter = DissentSignal.from(
                dissentSignal("anomaly|independent-audit", "source-a"));
        DissentSignal provenanceContainsDelimiter = DissentSignal.from(
                dissentSignal("anomaly", "independent-audit|source-a"));

        assertTrue(observationContainsDelimiter.eligible());
        assertTrue(provenanceContainsDelimiter.eligible());
        assertNotEquals(
                observationContainsDelimiter.signalRef(),
                provenanceContainsDelimiter.signalRef());
    }

    @Test
    void typedOperatorStopOverridesReadyCandidateWithoutLeakingReason() {
        OperatorProbeAuthorityStore authorityStore = new OperatorProbeAuthorityStore();
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(service(), authorityStore);
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);

        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "goal", "goal",
                "allowedPatchCandidates", List.of("anchor_compression_topup"),
                "decisionQuestion", "question",
                "queries", queries(),
                "retrievalBudget", retrievalBudget(),
                "stopRequested", true,
                "stopReason", "private operator stop reason",
                "maxMillis", 250), new ToolContext("operator-session", null)));

        assertEquals("observe_only", response.data().get("action"));
        assertEquals("operator_stop", response.data().get("policyDecision"));
        assertEquals(false, response.data().get("operatorAuthorityIssued"));
        assertFalse((response.data() + TraceStore.getByPrefix("causalProbe.").toString())
                .contains("private operator stop reason"));
    }

    @Test
    void missingTypedGoalFailsClosed() {
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(
                service(), new OperatorProbeAuthorityStore());

        ToolInvocationException ex = assertThrows(
                ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "allowedPatchCandidates", List.of("anchor_compression_topup"),
                        "stopRequested", false,
                        "maxMillis", 250), new ToolContext("operator-session", null))));

        assertEquals("operator_goal_required", ex.code());
    }

    @Test
    void oversizedOperatorScopeLabelFailsClosed() {
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(
                service(), new OperatorProbeAuthorityStore());

        ToolInvocationException ex = assertThrows(
                ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "goal", "goal",
                        "allowedPatchCandidates", List.of("x".repeat(129)),
                        "stopRequested", false,
                        "maxMillis", 250), new ToolContext("operator-session", null))));

        assertEquals("operator_scope_label_too_large", ex.code());
    }

    @Test
    void missingOperatorBudgetFailsClosed() {
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(
                service(), new OperatorProbeAuthorityStore());

        ToolInvocationException ex = assertThrows(
                ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "goal", "goal",
                        "stopRequested", false), new ToolContext("operator-session", null))));

        assertEquals("operator_probe_budget_required", ex.code());
    }

    @Test
    void malformedStopFlagAndCandidateOverlapDoNotMintAuthority() {
        OperatorProbeAuthorityStore authorityStore = new OperatorProbeAuthorityStore();
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(service(), authorityStore);

        ToolInvocationException invalid = assertThrows(
                ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "goal", "goal",
                        "stopRequested", "not-a-boolean",
                        "maxMillis", 250), new ToolContext("operator-session", null))));
        assertEquals("operator_stop_flag_invalid", invalid.code());

        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "goal", "goal",
                "allowedPatchCandidates", List.of("anchor_compression_topup"),
                "forbiddenActions", List.of("anchor_compression_topup"),
                "decisionQuestion", "question",
                "queries", queries(),
                "retrievalBudget", retrievalBudget(),
                "stopRequested", false,
                "maxMillis", 250), new ToolContext("operator-session", null)));

        assertEquals(false, response.data().get("operatorAuthorityIssued"));
        assertFalse(response.data().containsKey("operatorAuthorityRef"));
    }

    @Test
    void stopRevokesExistingAuthorityBeforeLaterValidationCanFail() {
        OperatorProbeAuthorityStore authorityStore = new OperatorProbeAuthorityStore();
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(service(), authorityStore);
        String authorityRef = authorityStore.issue(
                "operator-session",
                "hash:111111111111",
                "hash:222222222222",
                "hash:333333333333",
                OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE,
                250);

        assertThrows(ToolInvocationException.class, () -> tool.execute(new ToolRequest(Map.of(
                "goal", "goal",
                "stopRequested", true), new ToolContext("operator-session", null))));

        assertTrue(authorityStore.peek(authorityRef, "operator-session").isEmpty());
    }

    @Test
    void contextlessAuthorityIssuanceFailsClosed() {
        CausalProbeEvaluateTool tool = new CausalProbeEvaluateTool(
                service(), new OperatorProbeAuthorityStore());

        ToolInvocationException error = assertThrows(ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "goal", "goal",
                        "decisionQuestion", "question",
                        "queries", queries(),
                        "retrievalBudget", retrievalBudget(),
                        "stopRequested", false,
                        "maxMillis", 250), null)));

        assertEquals("agent_tool_session_required", error.code());
    }

    private static List<Map<String, String>> queries() {
        return List.of(
                Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3"));
    }

    private static Map<String, Integer> retrievalBudget() {
        return Map.of("maxDocuments", 3, "maxMillis", 250);
    }

    private static Map<String, Object> dissentSignal(String provenanceGroup) {
        return dissentSignal("independent observed anomaly", provenanceGroup);
    }

    private static Map<String, Object> dissentSignal(String observation, String provenanceGroup) {
        return Map.of(
                "atomicObservation", observation,
                "provenanceGroup", provenanceGroup,
                "correlatedMajorityProvenanceGroups", List.of("primary-rag", "shared-retrieval"),
                "collapsedFrom", List.of("signal-1", "signal-2"),
                "specificity", "high",
                "decisionChanging", true,
                "decisionImpact", Map.of(
                        "ifCorroborated", "block patch candidate",
                        "ifDisconfirmed", "retain current candidate"),
                "family", "provenance_and_time");
    }

    private static CausalProbeTriggerService service() {
        return new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
