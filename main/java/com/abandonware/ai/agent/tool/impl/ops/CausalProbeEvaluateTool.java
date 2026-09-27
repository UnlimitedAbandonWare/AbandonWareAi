package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.probe.CausalProbeTriggerService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immediate causal-probe evaluation under typed operator bounds. Eligible
 * decisions with one independent dissent signal issue one short-lived,
 * session-bound authority for the local counter-evidence pass; this tool does
 * not trigger retrieval or patch calls.
 */
@RequiresScopes({ToolScope.INTERNAL_READ})
public final class CausalProbeEvaluateTool implements AgentTool {

    private static final int MAX_GOAL_CHARS = 4096;
    private static final int MAX_SCOPE_ITEMS = 16;
    private static final int MAX_SCOPE_LABEL_CHARS = 128;
    private static final int MAX_PROBE_MILLIS = 60_000;

    private final CausalProbeTriggerService service;
    private final OperatorProbeAuthorityStore authorityStore;

    public CausalProbeEvaluateTool(CausalProbeTriggerService service) {
        this(service, new OperatorProbeAuthorityStore());
    }

    public CausalProbeEvaluateTool(CausalProbeTriggerService service,
                                   OperatorProbeAuthorityStore authorityStore) {
        this.service = service;
        this.authorityStore = authorityStore;
    }

    @Override
    public String id() {
        return "causal.probe.evaluate";
    }

    @Override
    public String description() {
        return "Evaluate causal-probe signals and issue one bounded local counter-evidence authority when eligible.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null
                ? Map.of()
                : request.input();
        String sessionId = requiredSessionId(request);
        boolean stopRequested = requiredBoolean(input.get("stopRequested"));
        if (stopRequested) {
            authorityStore.revokeSession(sessionId);
        }
        String goal = boundedRequiredText(input.get("goal"), "operator_goal_required", "operator_goal_too_large");
        String originalClaim = input.get("originalClaim") == null
                ? goal
                : boundedRequiredText(input.get("originalClaim"),
                "original_claim_required", "original_claim_too_large");
        Set<String> allowed = boundedLabels(input.get("allowedPatchCandidates"));
        Set<String> forbidden = boundedLabels(input.get("forbiddenActions"));
        int maxMillis = boundedInt(input.get("maxMillis"), "operator_probe_budget_required");
        long remainingMillis = TraceContext.current().remainingMillis();
        if (remainingMillis == 0L) {
            throw new ToolInvocationException(408, "tool_budget_exhausted");
        }
        maxMillis = (int) Math.min(maxMillis, Math.min(MAX_PROBE_MILLIS, remainingMillis));
        String probeRequestHash = CounterEvidenceRequestFingerprint.canonicalRequestHash(
                originalClaim,
                input.get("decisionQuestion") == null ? null : String.valueOf(input.get("decisionQuestion")),
                input.get("queries"),
                input.get("retrievalBudget"));
        DissentSignal dissentSignal = DissentSignal.from(input.get("dissentSignal"));
        CausalProbeTriggerService.ProbeConstraints constraints = stopRequested
                ? CausalProbeTriggerService.ProbeConstraints.stop(boundedOptionalText(input.get("stopReason")))
                : new CausalProbeTriggerService.ProbeConstraints(allowed, forbidden, false, "none");

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                goal,
                "agenttool.causal_probe_evaluate",
                constraints);

        String goalHash = SafeRedactor.hashValue(goal);
        String constraintHash = dissentSignal.bindConstraintHash(decision.constraintHash());
        boolean authorityEligible = decision.evidenceReady()
                && "allowed".equals(decision.policyDecision())
                && "source_patch_candidate".equals(decision.action())
                && dissentSignal.eligible()
                && !stopRequested
                && allowed.contains(decision.patchCandidate())
                && !forbidden.contains(decision.action())
                && !forbidden.contains(decision.patchCandidate());
        ToolResponse response = ToolResponse.ok()
                .put("sampleCount", decision.sampleCount())
                .put("evidenceReady", decision.evidenceReady())
                .put("triggerReason", decision.triggerReason())
                .put("dominantFailure", decision.dominantFailure())
                .put("hotspot", decision.hotspot())
                .put("confidence", decision.confidence())
                .put("patchCandidate", decision.patchCandidate())
                .put("action", decision.action())
                .put("axisCount", decision.axisCount())
                .put("policyDecision", decision.policyDecision())
                .put("constraintHash", constraintHash)
                .put("goalHash", goalHash)
                .put("operatorGoalHash", goalHash)
                .put("decisionAuthority", "probe_only")
                .put("dissentSignalEligible", dissentSignal.eligible())
                .put("dissentSignalReason", dissentSignal.reason())
                .put("verificationGatePassed", false)
                .put("operatorAuthorityIssued", authorityEligible)
                .put("operatorMaxMillis", maxMillis);
        String dissentSignalRef = dissentSignal.signalRef();
        if (dissentSignalRef != null) {
            response.put("dissentSignalRef", dissentSignalRef)
                    .put("dissentSignalProvenanceRef", dissentSignal.provenanceRef());
            response.put("dissentSignalLifecycle", "observed")
                    .put("dissentSignalDecisionAuthority", "probe_only")
                    .put("dissentSignalProbeMaxAttempts", 1)
                    .put("dissentSignalFamily", dissentSignal.family());
        }
        if (authorityEligible) {
            long issueRemainingMillis = TraceContext.current().remainingMillis();
            if (issueRemainingMillis == 0L) {
                throw new ToolInvocationException(408, "tool_budget_exhausted");
            }
            maxMillis = (int) Math.min(maxMillis, issueRemainingMillis);
            OperatorProbeAuthorityStore.Mode mode =
                    OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE;
            String authorityRef = authorityStore.issue(
                    sessionId,
                    goalHash,
                    constraintHash,
                    probeRequestHash,
                    mode,
                    maxMillis);
            response.put("operatorAuthorityRef", authorityRef)
                    .put("operatorAuthorityMode", mode.name())
                    .put("operatorMaxMillis", maxMillis);
        }
        return response;
    }

    private static String boundedRequiredText(Object value, String missingCode, String tooLargeCode) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            throw ToolInvocationException.badRequest(missingCode);
        }
        if (text.length() > MAX_GOAL_CHARS) {
            throw ToolInvocationException.badRequest(tooLargeCode);
        }
        return text;
    }

    private static String boundedOptionalText(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.length() > MAX_GOAL_CHARS) {
            throw ToolInvocationException.badRequest("operator_stop_reason_too_large");
        }
        return text;
    }

    private static Set<String> boundedLabels(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Collection<?> values)) {
            throw ToolInvocationException.badRequest("operator_scope_invalid");
        }
        if (values.size() > MAX_SCOPE_ITEMS) {
            throw ToolInvocationException.badRequest("operator_scope_too_large");
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Object item : values) {
            String label = item == null ? "" : String.valueOf(item).trim();
            if (label.length() > MAX_SCOPE_LABEL_CHARS) {
                throw ToolInvocationException.badRequest("operator_scope_label_too_large");
            }
            if (!label.isBlank()) {
                labels.add(label);
            }
        }
        return Set.copyOf(labels);
    }

    private static boolean requiredBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return Boolean.parseBoolean(text);
            }
        }
        throw ToolInvocationException.badRequest("operator_stop_flag_invalid");
    }

    private static int boundedInt(Object value, String errorCode) {
        try {
            int parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value).trim());
            if (parsed < 1 || parsed > MAX_PROBE_MILLIS) {
                throw ToolInvocationException.badRequest(errorCode);
            }
            return parsed;
        } catch (ToolInvocationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw ToolInvocationException.badRequest(errorCode);
        }
    }

    private static String requiredSessionId(ToolRequest request) {
        if (request == null || request.context() == null
                || request.context().sessionId() == null
                || request.context().sessionId().isBlank()) {
            throw ToolInvocationException.badRequest("agent_tool_session_required");
        }
        return request.context().sessionId().trim();
    }
}
