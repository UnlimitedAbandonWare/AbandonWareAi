package com.example.lms.api.internal;

import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.example.lms.search.TraceStore;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalAgentToolProbeRoundTest {

    @AfterEach
    void clearTraceContext() {
        TraceStore.clear();
        TraceContext.cleanupCurrentThread();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startRunsPolicyBoundCausalAndCounterPhasesExactlyOnce() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        Map<String, Object> input = startInput();
        when(invoker.invoke(eq("causal.probe.evaluate"), eq(input), any(), eq(true)))
                .thenReturn(validEnvelope("causal.probe.evaluate", Map.of(
                                "operatorAuthorityIssued", true,
                                "operatorAuthorityRef", "hash:authority",
                                "operatorGoalHash", "hash:goal",
                                "constraintHash", "hash:constraint",
                                "dominantFailure", "after_filter_starvation",
                                "patchCandidate", "source_patch_candidate",
                                "confidence", 0.74d,
                                "decisionAuthority", "probe_only")));
        when(invoker.invoke(eq("counter.evidence.retrieve"), any(), any(), eq(true)))
                .thenReturn(validEnvelope("counter.evidence.retrieve", Map.of(
                                "decision", "VERIFIER_DEFERRED",
                                "decisionAuthority", "retrieval_only",
                                "verificationGatePassed", false,
                                "retrievalConfidenceScore", 0.82d,
                                "counterDocumentUsed", 3,
                                "packetRef", "hash:123456789abc")));
        InternalAgentToolController controller = controller(invoker);

        Map<String, Object> result = controller.startProbeRound(input, request());

        InOrder order = inOrder(invoker);
        order.verify(invoker).invoke(eq("causal.probe.evaluate"), eq(input), any(), eq(true));
        ArgumentCaptor<Map<String, Object>> counterInput = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<ToolContext> context = ArgumentCaptor.forClass(ToolContext.class);
        order.verify(invoker).invoke(
                eq("counter.evidence.retrieve"), counterInput.capture(), context.capture(), eq(true));
        verify(invoker, never()).invoke(eq("evidence.coherence.verify"), any(), any(), eq(true));

        assertEquals("NORMALIZATION_REQUIRED", result.get("phase"));
        assertEquals("evidence.coherence.verify", result.get("nextTool"));
        assertEquals(Boolean.FALSE, result.get("verificationGatePassed"));
        assertEquals("session-1", context.getValue().sessionId());
        assertEquals("hash:authority", counterInput.getValue().get("operatorAuthorityRef"));
        assertEquals("hash:goal", counterInput.getValue().get("operatorGoalHash"));
        assertEquals("hash:constraint", counterInput.getValue().get("operatorConstraintHash"));
        assertEquals(input.get("originalClaim"), counterInput.getValue().get("originalClaim"));
        assertFalse(counterInput.getValue().containsKey("goal"));
        assertEquals("NORMALIZATION_REQUIRED", TraceStore.get("orch.probeRound.phase"));
        assertEquals("after_filter_starvation", TraceStore.get("orch.probeRound.hypothesis"));
        assertEquals("source_patch_candidate", TraceStore.get("orch.probeRound.patchCandidate"));
        assertEquals(0.74d, TraceStore.get("orch.probeRound.causalConfidence"));
        assertEquals(3, TraceStore.get("orch.probeRound.counterEvidenceCount"));
        assertEquals(0.82d, TraceStore.get("orch.probeRound.retrievalConfidence"));
        assertEquals("hash:123456789abc", TraceStore.get("orch.probeRound.roundRef"));
        assertEquals(Boolean.FALSE, TraceStore.get("orch.probeRound.verificationGatePassed"));
    }

    @Test
    void startStopsAfterCausalPhaseWhenOperatorAuthorityIsNotIssued() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        Map<String, Object> input = startInput();
        when(invoker.invoke(eq("causal.probe.evaluate"), eq(input), any(), eq(true)))
                .thenReturn(validEnvelope("causal.probe.evaluate", Map.of(
                                "operatorAuthorityIssued", false,
                                "policyDecision", "operator_stop",
                                "decisionAuthority", "probe_only")));
        InternalAgentToolController controller = controller(invoker);

        Map<String, Object> result = controller.startProbeRound(input, request());

        assertEquals("CAUSAL_ONLY", result.get("phase"));
        assertEquals("none", result.get("nextTool"));
        assertEquals(Boolean.FALSE, result.get("verificationGatePassed"));
        verify(invoker, never()).invoke(eq("counter.evidence.retrieve"), any(), any(), eq(true));
        verify(invoker, never()).invoke(eq("evidence.coherence.verify"), any(), any(), eq(true));
    }

    @Test
    void resumeInvokesVerifierOnceOnlyAfterCallerSuppliesNormalizedEvidence() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        Map<String, Object> normalized = Map.of(
                "originalClaim", "LLM hypothesis",
                "decisionQuestion", "bounded question",
                "evidenceRows", List.of(Map.of("evidenceId", "E1")),
                "officialConstraints", List.of(),
                "allowedReleaseStatuses", List.of("APPROVE", "REJECT", "HOLD"),
                "queryTraceRefs", List.of("hash:q1", "hash:q2", "hash:q3"),
                "counterEvidencePacketRef", "hash:123456789abc");
        when(invoker.invoke(eq("evidence.coherence.verify"), eq(normalized), any(), eq(true)))
                .thenReturn(validEnvelope("evidence.coherence.verify", Map.of(
                                "coherenceStatus", "CONSISTENT",
                                "releaseStatus", "APPROVE",
                                "confidenceRange", "HIGH..HIGH",
                                "validRowCount", 3,
                                "decisionAuthority", "verification_only",
                                "verificationGatePassed", true)));
        InternalAgentToolController controller = controller(invoker);

        Map<String, Object> result = controller.resumeProbeRound(normalized, request());

        assertEquals("VERIFIED", result.get("phase"));
        assertEquals("PATCH_CANDIDATE_REVIEW", result.get("nextAction"));
        assertEquals(Boolean.TRUE, result.get("verificationGatePassed"));
        verify(invoker).invoke(eq("evidence.coherence.verify"), eq(normalized), any(), eq(true));
        verify(invoker, never()).invoke(eq("causal.probe.evaluate"), any(), any(), eq(true));
        verify(invoker, never()).invoke(eq("counter.evidence.retrieve"), any(), any(), eq(true));
        assertEquals("VERIFIED", TraceStore.get("orch.probeRound.phase"));
        assertEquals("consistent", TraceStore.get("orch.probeRound.coherenceStatus"));
        assertEquals("approve", TraceStore.get("orch.probeRound.releaseStatus"));
        assertEquals("high_to_high", TraceStore.get("orch.probeRound.confidenceRange"));
        assertEquals("hash:123456789abc", TraceStore.get("orch.probeRound.roundRef"));
        assertEquals(3, TraceStore.get("orch.probeRound.verifiedEvidenceCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("orch.probeRound.verificationGatePassed"));
    }

    @Test
    void startFailsClosedWhenCausalEnvelopeContradictsNestedAuthority() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        Map<String, Object> input = startInput();
        Map<String, Object> invalid = new java.util.LinkedHashMap<>(validEnvelope(
                "causal.probe.evaluate",
                Map.of(
                        "operatorAuthorityIssued", true,
                        "operatorAuthorityRef", "hash:authority",
                        "operatorGoalHash", "hash:goal",
                        "constraintHash", "hash:constraint",
                        "decisionAuthority", "probe_only")));
        invalid.put("ok", false);
        when(invoker.invoke(eq("causal.probe.evaluate"), eq(input), any(), eq(true)))
                .thenReturn(invalid);
        InternalAgentToolController controller = controller(invoker);

        Map<String, Object> result = controller.startProbeRound(input, request());

        assertEquals("EVIDENCE_NEEDED", result.get("phase"));
        assertEquals(Boolean.FALSE, result.get("verificationGatePassed"));
        verify(invoker, never()).invoke(eq("counter.evidence.retrieve"), any(), any(), eq(true));
    }

    @Test
    void startFailsClosedWhenIssuedAuthorityIsMissingRequiredReference() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        Map<String, Object> input = startInput();
        when(invoker.invoke(eq("causal.probe.evaluate"), eq(input), any(), eq(true)))
                .thenReturn(validEnvelope("causal.probe.evaluate", Map.of(
                        "operatorAuthorityIssued", true,
                        "operatorGoalHash", "hash:goal",
                        "constraintHash", "hash:constraint",
                        "decisionAuthority", "probe_only")));
        InternalAgentToolController controller = controller(invoker);

        Map<String, Object> result = controller.startProbeRound(input, request());

        assertEquals("EVIDENCE_NEEDED", result.get("phase"));
        verify(invoker, never()).invoke(eq("counter.evidence.retrieve"), any(), any(), eq(true));
    }

    @Test
    void resumeRejectsContradictoryVerifierEnvelope() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        Map<String, Object> invalid = new java.util.LinkedHashMap<>(validEnvelope(
                "evidence.coherence.verify",
                Map.of(
                        "coherenceStatus", "CONSISTENT",
                        "releaseStatus", "APPROVE",
                        "decisionAuthority", "verification_only",
                        "verificationGatePassed", true)));
        invalid.put("resultValidation", "FAILED");
        when(invoker.invoke(eq("evidence.coherence.verify"), any(), any(), eq(true)))
                .thenReturn(invalid);
        InternalAgentToolController controller = controller(invoker);

        Map<String, Object> result = controller.resumeProbeRound(Map.of("evidenceRows", List.of()), request());

        assertEquals(Boolean.FALSE, result.get("ok"));
        assertEquals("EVIDENCE_NEEDED", result.get("phase"));
        assertEquals(Boolean.FALSE, result.get("verificationGatePassed"));
    }

    @Test
    void resumePreservesToolInvocationHttpStatusAndCode() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.invoke(eq("evidence.coherence.verify"), any(), any(), eq(true)))
                .thenThrow(new ToolInvocationException(408, "tool_budget_exhausted"));
        InternalAgentToolController controller = controller(invoker);

        org.springframework.web.server.ResponseStatusException ex = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.resumeProbeRound(Map.of(), request()));

        assertEquals(408, ex.getStatusCode().value());
        assertEquals("tool_budget_exhausted", ex.getReason());
    }

    private static Map<String, Object> startInput() {
        return Map.of(
                "goal", "operator goal",
                "originalClaim", "LLM hypothesis",
                "allowedPatchCandidates", List.of("anchor_compression_topup"),
                "forbiddenActions", List.of("delete_repository"),
                "decisionQuestion", "bounded question",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "MODEL_IDENTITY", "query", "q2"),
                        Map.of("slot", "CONFLICTING_SPECIFICATION", "query", "q3")),
                "retrievalBudget", Map.of("maxDocuments", 9, "maxMillis", 500),
                "maxMillis", 500);
    }

    private static Map<String, Object> validEnvelope(String toolId, Map<String, Object> data) {
        return Map.of(
                "ok", true,
                "toolId", toolId,
                "policyDecision", "ALLOW",
                "resultValidation", "PASSED",
                "budgetBounded", true,
                "truncated", false,
                "data", data);
    }

    private static InternalAgentToolController controller(AgentToolInvoker invoker) {
        AdminTokenGuardInterceptor guard = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(guard, "expectedToken", "admin-secret");
        ReflectionTestUtils.setField(guard, "ownerToken", "");
        ReflectionTestUtils.setField(guard, "tokenRequired", true);
        ReflectionTestUtils.setField(guard, "activeProfiles", "local");
        InternalAgentToolController controller = new InternalAgentToolController(invoker, guard);
        ReflectionTestUtils.setField(controller, "apiEnabled", true);
        return controller;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/probe-round");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        request.addHeader("X-Agent-Tool-Budget-Ms", "1000");
        request.addHeader("X-Session-Id", " session-1 ");
        return request;
    }
}
