package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redacted execution result. Provider output is retained only for synthesis. */
public record SubagentResult(
        String requestId,
        int ordinal,
        String taskId,
        String role,
        Status status,
        String output,
        String selectedProvider,
        LlmFailureClass failureClass,
        List<Attempt> attempts,
        long elapsedMs) {

    public SubagentResult {
        requestId = Objects.requireNonNullElse(requestId, "request-unknown");
        ordinal = Math.max(0, ordinal);
        taskId = Objects.requireNonNullElse(taskId, "task-unknown");
        role = Objects.requireNonNullElse(role, "analysis");
        status = status == null ? Status.FAILED : status;
        selectedProvider = Objects.requireNonNullElse(selectedProvider, "none");
        failureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        elapsedMs = Math.max(0L, elapsedMs);
    }

    public boolean succeeded() {
        return status == Status.SUCCESS && output != null && !output.isBlank();
    }

    /** Compatibility alias used by the HTTP and MCP adapters. */
    public String correlationId() {
        return requestId;
    }

    /** Compatibility alias used by the HTTP and MCP adapters. */
    public String provider() {
        return selectedProvider;
    }

    public boolean fallbackUsed() {
        return attempts.stream().anyMatch(attempt -> attempt.fallback()
                && attempt.outcome() != AttemptOutcome.SKIPPED);
    }

    public List<String> attemptedProviders() {
        LinkedHashSet<String> providerIds = new LinkedHashSet<>();
        attempts.stream()
                .filter(attempt -> attempt.outcome() == AttemptOutcome.SELECTED)
                .map(Attempt::provider)
                .forEach(providerIds::add);
        return List.copyOf(providerIds);
    }

    public LlmFailureClass errorClass() {
        return failureClass;
    }

    public boolean retryable() {
        return failureClass == LlmFailureClass.RATE_LIMIT_COOLDOWN
                || failureClass == LlmFailureClass.HEALTH_DOWN
                || failureClass == LlmFailureClass.TIMEOUT_SOFT
                || failureClass == LlmFailureClass.SOFT_CIRCUIT_OPEN
                || failureClass == LlmFailureClass.PROVIDER_ERROR;
    }

    public List<Evidence> evidence() {
        return attempts.stream()
                .map(attempt -> new Evidence(
                        attempt.provider(),
                        attempt.outcome(),
                        attempt.failureClass(),
                        attempt.elapsedMs(),
                        attempt.fallback(),
                        attempt.reasonCode()))
                .toList();
    }

    public List<String> warnings() {
        LinkedHashSet<String> reasonCodes = new LinkedHashSet<>();
        attempts.stream()
                .filter(attempt -> attempt.outcome() != AttemptOutcome.SELECTED
                        && attempt.outcome() != AttemptOutcome.SUCCESS)
                .map(Attempt::reasonCode)
                .filter(reason -> !"unknown".equals(reason))
                .forEach(reasonCodes::add);
        return List.copyOf(reasonCodes);
    }

    public boolean blockedExternal() {
        return attempts.stream().anyMatch(attempt -> "vercel-glm".equals(attempt.provider())
                && (attempt.failureClass() == LlmFailureClass.AUTH_MISSING
                || "blocked_external".equals(attempt.reasonCode())
                || "missing_ai_gateway_api_key".equals(attempt.reasonCode())
                || "circuit_auth_blocked".equals(attempt.reasonCode())));
    }

    /**
     * Stable, allowlisted adapter view. Prompts and credentials are never part of this map.
     * Output is included because it is the intended tool result, but callers must not log it.
     */
    public Map<String, Object> structuredView() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", taskId);
        row.put("correlationId", correlationId());
        row.put("role", role);
        row.put("provider", provider());
        row.put("status", status.name());
        row.put("output", output);
        row.put("elapsedMs", elapsedMs);
        row.put("fallbackUsed", fallbackUsed());
        row.put("attemptedProviders", attemptedProviders());
        row.put("errorClass", errorClass().name());
        row.put("retryable", retryable());
        row.put("evidence", evidence());
        row.put("warnings", warnings());
        row.put("blockedExternal", blockedExternal());
        return Collections.unmodifiableMap(row);
    }

    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    public enum AttemptOutcome {
        SKIPPED,
        SELECTED,
        SUCCESS,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    /** Allowlisted provider-attempt evidence; never stores prompt, output, key, or error text. */
    public record Attempt(
            String provider,
            AttemptOutcome outcome,
            LlmFailureClass failureClass,
            long elapsedMs,
            boolean fallback,
            String reasonCode) {

        public Attempt {
            provider = Objects.requireNonNullElse(provider, "unknown");
            outcome = outcome == null ? AttemptOutcome.FAILED : outcome;
            failureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
            elapsedMs = Math.max(0L, elapsedMs);
            reasonCode = Objects.requireNonNullElse(reasonCode, "unknown");
        }
    }

    /** Redacted categorical evidence suitable for structured adapter responses. */
    public record Evidence(
            String provider,
            AttemptOutcome outcome,
            LlmFailureClass errorClass,
            long elapsedMs,
            boolean fallbackUsed,
            String reasonCode) {

        public Evidence {
            provider = Objects.requireNonNullElse(provider, "unknown");
            outcome = outcome == null ? AttemptOutcome.FAILED : outcome;
            errorClass = errorClass == null ? LlmFailureClass.UNKNOWN : errorClass;
            elapsedMs = Math.max(0L, elapsedMs);
            reasonCode = Objects.requireNonNullElse(reasonCode, "unknown");
        }
    }
}
