package com.example.lms.api.internal;

import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.abandonware.ai.agent.tool.request.ToolContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal/agent")
public class InternalAgentToolController {
    private static final String TOOL_BUDGET_HEADER = "X-Agent-Tool-Budget-Ms";
    private static final long MAX_TOOL_BUDGET_MS = 60_000L;
    private static final Set<String> SESSION_BOUND_TOOL_IDS = Set.of(
            "causal.probe.evaluate",
            "counter.evidence.retrieve",
            "evidence.coherence.verify");

    private final AgentToolInvoker invoker;
    private final AdminTokenGuardInterceptor guard;

    @Value("${agent.tools.api.enabled:false}")
    private boolean apiEnabled;

    public InternalAgentToolController(AgentToolInvoker invoker, AdminTokenGuardInterceptor guard) {
        this.invoker = invoker;
        this.guard = guard;
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(HttpServletRequest request) {
        ensureEnabledAndAuthorized(request);
        return invoker.describeTools();
    }

    @PostMapping("/tools/{toolId}:invoke")
    public Map<String, Object> invokeTool(@PathVariable String toolId,
                                          @RequestBody(required = false) Map<String, Object> input,
                                          HttpServletRequest request) {
        ensureEnabledAndAuthorized(request);
        String canonicalToolId = toolId == null ? "" : toolId.trim();
        String rawSessionId = request == null ? null : request.getHeader("X-Session-Id");
        if (SESSION_BOUND_TOOL_IDS.contains(canonicalToolId)
                && (rawSessionId == null || rawSessionId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_tool_session_required");
        }
        String sessionId = normalizeSessionId(rawSessionId);
        if (SESSION_BOUND_TOOL_IDS.contains(canonicalToolId)) {
            attachOperatorBudget(request);
        } else {
            attachOperatorBudgetIfPresent(request);
        }
        ToolContext context = new ToolContext(sessionId, null);
        return invokeWithHttpStatus(canonicalToolId, input == null ? Map.of() : input, context);
    }

    /**
     * Starts one bounded probe round. The server may evaluate the causal gate
     * and consume its one-shot authority for local counter-evidence retrieval,
     * but it deliberately stops before provenance normalization and coherence
     * verification. Those remain caller/LLM responsibilities.
     */
    @PostMapping("/probe-round:start")
    public Map<String, Object> startProbeRound(
            @RequestBody(required = false) Map<String, Object> input,
            HttpServletRequest request) {
        ToolContext context = boundedProbeContext(request);
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        Map<String, Object> causal = invokeWithHttpStatus(
                "causal.probe.evaluate", safeInput, context);
        Map<String, Object> causalData = responseData(causal);

        if (!validEnvelope(causal, "causal.probe.evaluate")
                || !"probe_only".equals(causalData.get("decisionAuthority"))) {
            traceProbeRound("EVIDENCE_NEEDED", "causal_result_invalid",
                    causalData, Map.of(), Map.of(), false, null);
            return evidenceNeeded("causal_result_invalid", "causal", causal);
        }

        if (!Boolean.TRUE.equals(causalData.get("operatorAuthorityIssued"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE.equals(causal.get("ok")));
            out.put("phase", "CAUSAL_ONLY");
            out.put("nextTool", "none");
            out.put("verificationGatePassed", false);
            out.put("causal", causal);
            traceProbeRound("CAUSAL_ONLY", "operator_authority_not_issued",
                    causalData, Map.of(), Map.of(), false, null);
            return out;
        }
        if (!hasText(causalData, "operatorAuthorityRef")
                || !hasText(causalData, "operatorGoalHash")
                || !hasText(causalData, "constraintHash")) {
            traceProbeRound("EVIDENCE_NEEDED", "causal_authority_reference_missing",
                    causalData, Map.of(), Map.of(), false, null);
            return evidenceNeeded("causal_authority_reference_missing", "causal", causal);
        }

        Map<String, Object> counterInput = new LinkedHashMap<>();
        copyIfPresent(safeInput, counterInput, "originalClaim");
        copyIfPresent(safeInput, counterInput, "decisionQuestion");
        copyIfPresent(safeInput, counterInput, "queries");
        copyIfPresent(safeInput, counterInput, "retrievalBudget");
        counterInput.put("operatorAuthorityRef", causalData.get("operatorAuthorityRef"));
        counterInput.put("operatorGoalHash", causalData.get("operatorGoalHash"));
        counterInput.put("operatorConstraintHash", causalData.get("constraintHash"));

        Map<String, Object> counter = invokeWithHttpStatus(
                "counter.evidence.retrieve", counterInput, context);
        Map<String, Object> counterData = responseData(counter);
        if (!validEnvelope(counter, "counter.evidence.retrieve")
                || !"retrieval_only".equals(counterData.get("decisionAuthority"))
                || !"VERIFIER_DEFERRED".equals(counterData.get("decision"))
                || !Boolean.FALSE.equals(counterData.get("verificationGatePassed"))
                || !hasText(counterData, "packetRef")) {
            Map<String, Object> out = evidenceNeeded(
                    "counter_evidence_result_invalid", "counterEvidence", counter);
            out.put("causal", causal);
            traceProbeRound("EVIDENCE_NEEDED", "counter_evidence_result_invalid",
                    causalData, counterData, Map.of(), false, counterData.get("packetRef"));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE.equals(causal.get("ok")) && Boolean.TRUE.equals(counter.get("ok")));
        out.put("phase", "NORMALIZATION_REQUIRED");
        out.put("nextTool", "evidence.coherence.verify");
        out.put("verificationGatePassed", false);
        out.put("causal", causal);
        out.put("counterEvidence", counter);
        traceProbeRound("NORMALIZATION_REQUIRED", "verifier_deferred",
                causalData, counterData, Map.of(), false, counterData.get("packetRef"));
        return out;
    }

    /**
     * Resumes a probe round only after the caller supplies normalized evidence.
     * The deterministic verifier remains the sole owner of the release gate.
     */
    @PostMapping("/probe-round:resume")
    public Map<String, Object> resumeProbeRound(
            @RequestBody(required = false) Map<String, Object> input,
            HttpServletRequest request) {
        ToolContext context = boundedProbeContext(request);
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        Map<String, Object> verifier = invokeWithHttpStatus(
                "evidence.coherence.verify",
                safeInput,
                context);
        Map<String, Object> verifierData = responseData(verifier);
        boolean envelopeValid = validEnvelope(verifier, "evidence.coherence.verify");
        boolean verificationGatePassed = envelopeValid
                && "verification_only".equals(verifierData.get("decisionAuthority"))
                && "CONSISTENT".equals(verifierData.get("coherenceStatus"))
                && "APPROVE".equals(verifierData.get("releaseStatus"))
                && Boolean.TRUE.equals(verifierData.get("verificationGatePassed"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", envelopeValid);
        out.put("phase", verificationGatePassed ? "VERIFIED" : "EVIDENCE_NEEDED");
        out.put("nextAction", verificationGatePassed
                ? "PATCH_CANDIDATE_REVIEW"
                : "COLLECT_OR_NORMALIZE_EVIDENCE");
        out.put("verificationGatePassed", verificationGatePassed);
        out.put("verifier", verifier);
        traceProbeRound(
                verificationGatePassed ? "VERIFIED" : "EVIDENCE_NEEDED",
                verificationGatePassed ? "coherence_consistent" : "verification_gate_hold",
                Map.of(), Map.of(), verifierData, verificationGatePassed,
                safeInput.get("counterEvidencePacketRef"));
        return out;
    }

    private void ensureEnabledAndAuthorized(HttpServletRequest request) {
        if (!apiEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent_tools_api_disabled");
        }
        if (!guard.hasConfiguredToken() || !guard.isPresentedTokenAuthorized(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_token_required");
        }
    }

    private static String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return "internal-agent";
        }
        String trimmed = sessionId.trim();
        return trimmed.isEmpty() ? "internal-agent" : trimmed;
    }

    private ToolContext boundedProbeContext(HttpServletRequest request) {
        ensureEnabledAndAuthorized(request);
        String rawSessionId = request == null ? null : request.getHeader("X-Session-Id");
        if (rawSessionId == null || rawSessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_tool_session_required");
        }
        attachOperatorBudget(request);
        return new ToolContext(normalizeSessionId(rawSessionId), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseData(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private Map<String, Object> invokeWithHttpStatus(
            String toolId,
            Map<String, Object> input,
            ToolContext context) {
        try {
            return invoker.invoke(toolId, input, context, true);
        } catch (ToolInvocationException ex) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(ex.status()), ex.code(), ex);
        }
    }

    private static boolean validEnvelope(Map<String, Object> response, String expectedToolId) {
        return response != null
                && Boolean.TRUE.equals(response.get("ok"))
                && expectedToolId.equals(response.get("toolId"))
                && "ALLOW".equals(response.get("policyDecision"))
                && "PASSED".equals(response.get("resultValidation"))
                && Boolean.TRUE.equals(response.get("budgetBounded"))
                && Boolean.FALSE.equals(response.get("truncated"));
    }

    private static boolean hasText(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof String text && !text.isBlank();
    }

    private static Map<String, Object> evidenceNeeded(
            String reason,
            String responseKey,
            Map<String, Object> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("phase", "EVIDENCE_NEEDED");
        out.put("nextTool", "none");
        out.put("nextAction", "COLLECT_OR_NORMALIZE_EVIDENCE");
        out.put("verificationGatePassed", false);
        out.put("reason", reason);
        out.put(responseKey, response == null ? Map.of() : response);
        return out;
    }

    private static void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static void traceProbeRound(
            String phase,
            String reason,
            Map<String, Object> causalData,
            Map<String, Object> counterData,
            Map<String, Object> verifierData,
            boolean verificationGatePassed,
            Object roundRef) {
        TraceStore.put("orch.probeRound.observed", true);
        TraceStore.put("orch.probeRound.phase", phase);
        TraceStore.put("orch.probeRound.reason", reason);
        TraceStore.put("orch.probeRound.verificationGatePassed", verificationGatePassed);
        traceRoundRef(roundRef);

        traceLabel("orch.probeRound.hypothesis", causalData.get("dominantFailure"));
        traceLabel("orch.probeRound.patchCandidate", causalData.get("patchCandidate"));
        traceConfidence("orch.probeRound.causalConfidence", causalData.get("confidence"));
        traceCount("orch.probeRound.counterEvidenceCount", counterData.get("counterDocumentUsed"));
        traceConfidence("orch.probeRound.retrievalConfidence", counterData.get("retrievalConfidenceScore"));
        traceLabel("orch.probeRound.coherenceStatus", verifierData.get("coherenceStatus"));
        traceLabel("orch.probeRound.releaseStatus", verifierData.get("releaseStatus"));
        traceConfidenceRange(verifierData.get("confidenceRange"));
        traceCount("orch.probeRound.verifiedEvidenceCount", verifierData.get("validRowCount"));
    }

    private static void traceLabel(String key, Object value) {
        if (value == null) {
            return;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        TraceStore.put(key, normalized.matches("[a-z0-9_-]{1,64}") ? normalized : "unknown");
    }

    private static void traceRoundRef(Object value) {
        if (value == null) {
            return;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.matches("hash:[0-9a-f]{12}")) {
            TraceStore.put("orch.probeRound.roundRef", normalized);
        }
    }

    private static void traceConfidenceRange(Object value) {
        String normalized = value == null
                ? ""
                : String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT);
        String safe = switch (normalized) {
            case "HIGH..HIGH" -> "high_to_high";
            case "MEDIUM..HIGH" -> "medium_to_high";
            case "LOW..LOW" -> "low_to_low";
            case "LOW..MEDIUM" -> "low_to_medium";
            default -> "unknown";
        };
        TraceStore.put("orch.probeRound.confidenceRange", safe);
    }

    private static void traceConfidence(String key, Object value) {
        if (!(value instanceof Number number)) {
            return;
        }
        double numeric = number.doubleValue();
        if (Double.isFinite(numeric)) {
            TraceStore.put(key, Math.max(0.0d, Math.min(1.0d, numeric)));
        }
    }

    private static void traceCount(String key, Object value) {
        if (!(value instanceof Number number)) {
            return;
        }
        TraceStore.put(key, Math.max(0, number.intValue()));
    }

    private static void attachOperatorBudget(HttpServletRequest request) {
        String raw = request == null ? null : request.getHeader(TOOL_BUDGET_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_tool_budget_required");
        }
        long budgetMs;
        try {
            budgetMs = Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_tool_budget_invalid");
        }
        if (budgetMs < 1L || budgetMs > MAX_TOOL_BUDGET_MS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_tool_budget_out_of_range");
        }
        TraceContext context = TraceContext.current();
        context.tightenBudget(Duration.ofMillis(budgetMs));
    }

    private static void attachOperatorBudgetIfPresent(HttpServletRequest request) {
        String raw = request == null ? null : request.getHeader(TOOL_BUDGET_HEADER);
        if (raw != null && !raw.isBlank()) {
            attachOperatorBudget(request);
        }
    }
}
