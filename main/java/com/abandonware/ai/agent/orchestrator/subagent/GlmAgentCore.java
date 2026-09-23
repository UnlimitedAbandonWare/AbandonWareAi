package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Shared provider-selection core used by the in-process flow and MCP adapters. */
@Component
public final class GlmAgentCore {

    private static final Logger log = LoggerFactory.getLogger(GlmAgentCore.class);

    private final SubagentProviderChain providerChain;
    private final SubagentAgentMetrics metrics;
    private final ModelRuntimeHealthTracker healthTracker;
    private final GlmActivationStateMachine activation;
    private final boolean activationPolicyEnforced;
    private final AtomicReference<RecentRequestEvidence> recentRequestEvidence =
            new AtomicReference<>(RecentRequestEvidence.empty());

    public GlmAgentCore(SubagentProviderChain providerChain) {
        this(providerChain, new SubagentAgentMetrics(), null, null, false);
    }

    public GlmAgentCore(SubagentProviderChain providerChain,
                        SubagentAgentMetrics metrics) {
        this(providerChain, metrics, null, null, false);
    }

    public GlmAgentCore(SubagentProviderChain providerChain,
                        SubagentAgentMetrics metrics,
                        ModelRuntimeHealthTracker healthTracker) {
        this(providerChain, metrics, healthTracker, null, false);
    }

    @Autowired
    public GlmAgentCore(SubagentProviderChain providerChain,
                        SubagentAgentMetrics metrics,
                        ModelRuntimeHealthTracker healthTracker,
                        GlmActivationStateMachine activation) {
        this(providerChain, metrics, healthTracker, activation, true);
    }

    private GlmAgentCore(SubagentProviderChain providerChain,
                         SubagentAgentMetrics metrics,
                         ModelRuntimeHealthTracker healthTracker,
                         GlmActivationStateMachine activation,
                         boolean activationPolicyEnforced) {
        this.providerChain = Objects.requireNonNull(providerChain, "providerChain");
        this.metrics = metrics == null ? new SubagentAgentMetrics() : metrics;
        this.healthTracker = healthTracker;
        this.activation = activation == null
                ? GlmActivationStateMachine.disabled(this.metrics)
                : activation;
        this.activationPolicyEnforced = activationPolicyEnforced;
    }

    public SubagentResult execute(SubagentTask task,
                                  SubagentProviderChain.AttemptBudget attemptBudget,
                                  long deadlineNanos,
                                  Consumer<SubagentResult.Attempt> observer) {
        activation.refresh(providerChain.circuitState(GlmActivationStateMachine.GLM_PROVIDER_ID));
        return executeInternal(task, attemptBudget, deadlineNanos, observer).result();
    }

    /**
     * MCP delegate entry point. The first fully ready call owns the sole process-local live probe.
     * Other tools and ordinary flow executions cannot consume that permit.
     */
    public SubagentResult executeMcpDelegate(SubagentTask task,
                                             SubagentProviderChain.AttemptBudget attemptBudget,
                                             long deadlineNanos,
                                             Consumer<SubagentResult.Attempt> observer) {
        String circuitState = providerChain.circuitState(GlmActivationStateMachine.GLM_PROVIDER_ID);
        activation.refresh(circuitState);
        if (Thread.currentThread().isInterrupted()
                || !SubagentProviderConfiguration.glmDeadlineCanInvoke(
                deadlineNanos, System.nanoTime())) {
            return execute(task, attemptBudget, deadlineNanos, observer);
        }
        Optional<GlmActivationStateMachine.ProbePermit> permit =
                activation.tryBeginProbe(circuitState);
        if (permit.isEmpty()) {
            return execute(task, attemptBudget, deadlineNanos, observer);
        }
        GlmActivationStateMachine.ProbePermit claimed = permit.orElseThrow();
        try (GlmActivationStateMachine.ProbeScope ignored = activation.enterProbeScope(claimed)) {
            try {
                ExecutionResult execution = executeInternal(
                        task, attemptBudget, deadlineNanos, observer);
                activation.completeProbe(
                        claimed,
                        execution.result(),
                        probeEvidence(execution.result(), execution.requestEvidence()));
                return execution.result();
            } catch (RuntimeException | Error failure) {
                activation.completeProbe(
                        claimed, null, GlmActivationStateMachine.ProbeEvidence.none(false));
                throw failure;
            }
        }
    }

    private ExecutionResult executeInternal(SubagentTask task,
                                            SubagentProviderChain.AttemptBudget attemptBudget,
                                            long deadlineNanos,
                                            Consumer<SubagentResult.Attempt> observer) {
        Objects.requireNonNull(task, "task");
        Consumer<SubagentResult.Attempt> safeObserver = observer == null ? ignored -> { } : observer;
        metrics.recordCall();
        RequestTimelineScope timeline = beginTimeline(task);
        String terminalClass = "error";
        SubagentResult result;
        RecentRequestEvidence requestEvidence;
        try {
            result = providerChain.execute(
                    task,
                    attemptBudget,
                    deadlineNanos,
                    attempt -> {
                        metrics.recordAttempt(attempt);
                        emitAttempt(task, attempt);
                        try {
                            safeObserver.accept(attempt);
                        } catch (RuntimeException ignored) {
                            // Observability cannot alter provider execution.
                        }
                    },
                    providerId -> !activationPolicyEnforced
                            || !GlmActivationStateMachine.GLM_PROVIDER_ID.equals(providerId)
                            || activation.glmProviderAttemptAllowed());
            terminalClass = terminalClass(result);
            metrics.recordResult(result);
            emit(task, "subagent.result.received", result.selectedProvider(),
                    result.status().name().toLowerCase(), result.failureClass(),
                    result.fallbackUsed(), result.elapsedMs(), providerChain.circuitState(result.selectedProvider()));
        } finally {
            requestEvidence = finishTimeline(timeline, terminalClass);
        }
        return new ExecutionResult(result, requestEvidence);
    }

    /** Provider readiness and circuit status without any generation request. */
    public Map<String, Object> status() {
        GlmActivationStateMachine.Snapshot activationSnapshot = activation.refresh(
                providerChain.circuitState(GlmActivationStateMachine.GLM_PROVIDER_ID));
        List<SubagentProviderChain.ProviderStatus> statuses = providerChain.status();
        SubagentAgentMetrics.Snapshot metricsSnapshot = metrics.snapshot();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        boolean blockedExternal = false;
        for (int index = 0; index < statuses.size(); index++) {
            SubagentProviderChain.ProviderStatus status = statuses.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", status.provider());
            row.put("order", status.order());
            row.put("available", status.available());
            row.put("reasonCode", status.reasonCode());
            row.put("circuitState", status.circuitState());
            row.put("singleAttemptPerFlow", status.singleAttemptPerFlow());
            rows.add(Map.copyOf(row));
            if (index > 0 && status.available() && "closed".equals(status.circuitState())) {
                fallbacks.add(status.provider());
            }
            if ("vercel-glm".equals(status.provider())
                    && ("blocked_external".equals(status.reasonCode())
                    || "missing_ai_gateway_api_key".equals(status.reasonCode())
                    || "auth_blocked".equals(status.circuitState()))) {
                blockedExternal = true;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        boolean activationBlocked = activationSnapshot.state()
                == GlmActivationStateMachine.State.BLOCKED_EXTERNAL
                || activationSnapshot.state() == GlmActivationStateMachine.State.WAITING_FOR_KEY
                || activationSnapshot.state()
                == GlmActivationStateMachine.State.WAITING_FOR_CREDIT_CONFIRMATION
                || activationSnapshot.state() == GlmActivationStateMachine.State.GLM_AUTH_BLOCKED;
        result.put("generationAttempted", activationSnapshot.liveProbeAttempted());
        result.put("activationState", activationSnapshot.state().name());
        result.put("activationReasonCode", activationSnapshot.reasonCode());
        result.put("activationEpochOrdinal", activationSnapshot.epochOrdinal());
        result.put("glmAutoActivateEnabled", activationSnapshot.autoActivateEnabled());
        result.put("glmExternalReady", activationSnapshot.externalReady());
        result.put("glmKeyPresenceChecked", activationSnapshot.keyPresenceChecked());
        result.put("glmKeyPresent", activationSnapshot.keyPresent());
        result.put("activationProbeClaimed", activationSnapshot.probeClaimed());
        result.put("liveProbeAttempted", activationSnapshot.liveProbeAttempted());
        result.put("liveProbeSucceeded", activationSnapshot.liveProbeSucceeded());
        result.put("liveProbeFailureClass", activationSnapshot.liveProbeFailureClass());
        result.put("liveProbeRetryable", activationSnapshot.liveProbeRetryable());
        result.put("liveProbeProvider", activationSnapshot.liveProbeProvider());
        result.put("aggregateProvider", activationSnapshot.aggregateProvider());
        result.put("aggregateStatus", activationSnapshot.aggregateStatus());
        result.put("liveProbeFallbackUsed", activationSnapshot.liveProbeFallbackUsed());
        result.put("probeRetrySuppressed", activationSnapshot.probeClaimed()
                && activationSnapshot.state() != GlmActivationStateMachine.State.PROBING);
        GlmActivationStateMachine.ProbeEvidence probeEvidence = activationSnapshot.probeEvidence();
        result.put("modelAdapterAttemptObserved", probeEvidence.modelAdapterAttemptObserved());
        result.put("clientHttpExchangeObserved", probeEvidence.clientHttpExchangeObserved());
        result.put("clientHttpResponseObserved", probeEvidence.clientHttpResponseObserved());
        result.put("providerAttemptObserved", probeEvidence.providerAttemptObserved());
        result.put("wireAttemptObserved", probeEvidence.wireAttemptObserved());
        result.put("responseObserved", probeEvidence.responseObserved());
        result.put("redactedResponseEvidenceObserved",
                probeEvidence.redactedResponseEvidenceObserved());
        result.put("activationFollowUpQueue",
                activationSnapshot.state() == GlmActivationStateMachine.State.GLM_ACTIVE
                        ? List.of("glm_review_change", "glm_consensus_check", "glm_delegate_task")
                        : List.of());
        result.put("blockedExternal", blockedExternal || activationBlocked);
        result.put("providers", List.copyOf(rows));
        result.put("fallbackProviders", List.copyOf(fallbacks));
        result.put("recentErrorClasses", metricsSnapshot.recentErrorClasses());
        RecentRequestEvidence requestEvidence = recentRequestEvidence.get();
        result.put("requestTimelineEnabled", healthTracker != null);
        result.put("recentRequestTimeline", requestEvidence.timeline());
        result.put("recentClientHttpLedger", requestEvidence.clientHttpLedger());
        result.put("metrics", metricsSnapshot.asMap());
        return Map.copyOf(result);
    }

    public SubagentAgentMetrics metrics() {
        return metrics;
    }

    private RequestTimelineScope beginTimeline(SubagentTask task) {
        if (healthTracker == null) {
            return RequestTimelineScope.disabled();
        }
        Object previousTimeline = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        try {
            String timelineId = healthTracker.beginRequestTimeline(task.requestId(), task.taskId());
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            healthTracker.recordRequestPhase(timelineId, "dispatch", "subagent", "subagent", "none");
            healthTracker.recordRequestPhase(timelineId, "pending", "subagent", "subagent", "none");
            return new RequestTimelineScope(timelineId, previousTimeline, true);
        } catch (RuntimeException evidenceFailure) {
            restoreTimeline(previousTimeline);
            log.debug("[AWX][glm-agent-core] event=timeline_begin_failed errorType={}",
                    SafeRedactor.traceLabelOrFallback(
                            evidenceFailure.getClass().getSimpleName(), "unknown"));
            return RequestTimelineScope.disabled();
        }
    }

    private RecentRequestEvidence finishTimeline(RequestTimelineScope scope, String terminalClass) {
        if (!scope.enabled()) {
            return RecentRequestEvidence.empty();
        }
        RecentRequestEvidence captured = RecentRequestEvidence.empty();
        try {
            healthTracker.recordRequestPhase(
                    scope.timelineId(), "terminal", "subagent", "subagent", terminalClass);
            captured = new RecentRequestEvidence(
                    projectRows(
                            healthTracker.redactedRequestTimeline(scope.timelineId()),
                            List.of("requestHash", "phase", "terminalClass")),
                    projectRows(
                            healthTracker.redactedRequestAttemptLedger(scope.timelineId()),
                            List.of(
                                    "requestHash", "promptHash", "responseHash",
                                    "httpRequestBodyHash", "httpResponseBodyHash",
                                    "outcome", "failureClass", "terminalClass",
                                    "modelAdapterAttemptObserved", "clientHttpExchangeObserved",
                                    "clientHttpResponseObserved", "providerAttemptObserved",
                                    "wireAttemptObserved", "responseObserved")));
            recentRequestEvidence.set(captured);
        } catch (RuntimeException evidenceFailure) {
            log.debug("[AWX][glm-agent-core] event=timeline_finish_failed errorType={}",
                    SafeRedactor.traceLabelOrFallback(
                            evidenceFailure.getClass().getSimpleName(), "unknown"));
        } finally {
            restoreTimeline(scope.previousTimeline());
        }
        return captured;
    }

    private static GlmActivationStateMachine.ProbeEvidence probeEvidence(
            SubagentResult result,
            RecentRequestEvidence requestEvidence) {
        boolean generationAttempted = requestEvidence.clientHttpLedger().stream().anyMatch(row ->
                Boolean.TRUE.equals(row.get("modelAdapterAttemptObserved")))
                || result != null && result.attempts().stream().anyMatch(
                GlmAgentCore::observedGlmDispatch);
        Map<String, Object> successRow = requestEvidence.clientHttpLedger().stream()
                .filter(row -> "success".equals(row.get("outcome")))
                .filter(row -> Boolean.TRUE.equals(row.get("responseObserved")))
                .findFirst()
                .orElse(Map.of());
        String responseHash = String.valueOf(successRow.getOrDefault("responseHash", "hash:unknown"));
        String httpResponseHash = String.valueOf(
                successRow.getOrDefault("httpResponseBodyHash", "hash:unknown"));
        boolean redactedResponseEvidence = isObservedHash(responseHash)
                && isObservedHash(httpResponseHash);
        return new GlmActivationStateMachine.ProbeEvidence(
                generationAttempted,
                Boolean.TRUE.equals(successRow.get("modelAdapterAttemptObserved")),
                Boolean.TRUE.equals(successRow.get("clientHttpExchangeObserved")),
                Boolean.TRUE.equals(successRow.get("clientHttpResponseObserved")),
                Boolean.TRUE.equals(successRow.get("providerAttemptObserved")),
                Boolean.TRUE.equals(successRow.get("wireAttemptObserved")),
                Boolean.TRUE.equals(successRow.get("responseObserved")),
                redactedResponseEvidence);
    }

    private static boolean observedGlmDispatch(SubagentResult.Attempt attempt) {
        if (!GlmActivationStateMachine.GLM_PROVIDER_ID.equals(attempt.provider())
                || attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED
                || attempt.outcome() == SubagentResult.AttemptOutcome.SKIPPED) {
            return false;
        }
        return !List.of(
                "availability_cancelled",
                "readiness_interrupted",
                "cancelled_before_provider_call",
                "activation_not_allowed",
                "missing_ai_gateway_api_key",
                "glm_deadline_too_short",
                "deadline_exhausted",
                "flow_attempt_limit").contains(attempt.reasonCode());
    }

    private static boolean isObservedHash(String value) {
        return value != null
                && (value.startsWith("hash:") || value.startsWith("sha256:"))
                && !"hash:unknown".equals(value)
                && !"sha256:unknown".equals(value);
    }

    private static void restoreTimeline(Object previousTimeline) {
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, previousTimeline);
    }

    private static List<Map<String, Object>> projectRows(List<Map<String, Object>> rows,
                                                          List<String> allowlistedKeys) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> projected = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> safeRow = new LinkedHashMap<>();
            for (String key : allowlistedKeys) {
                Object value = row == null ? null : row.get(key);
                if (value instanceof String || value instanceof Boolean || value instanceof Number) {
                    safeRow.put(key, value);
                }
            }
            projected.add(Map.copyOf(safeRow));
        }
        return List.copyOf(projected);
    }

    private static String terminalClass(SubagentResult result) {
        if (result == null || result.status() == null) {
            return "error";
        }
        return switch (result.status()) {
            case SUCCESS -> "success";
            case TIMEOUT -> "timeout";
            case CANCELLED -> "cancelled";
            case FAILED -> "error";
        };
    }

    private void emitAttempt(SubagentTask task, SubagentResult.Attempt attempt) {
        String circuitState = providerChain.circuitState(attempt.provider());
        if (attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED) {
            emit(task, "provider.selected", attempt.provider(), "selected",
                    LlmFailureClass.NONE, attempt.fallback(), attempt.elapsedMs(), circuitState);
            if (attempt.fallback() && !"retry_selected".equals(attempt.reasonCode())) {
                emit(task, "provider.fallback", attempt.provider(), "selected",
                        LlmFailureClass.NONE, true, attempt.elapsedMs(), circuitState);
            }
            return;
        }
        if (attempt.outcome() == SubagentResult.AttemptOutcome.SKIPPED) {
            return;
        }
        emit(task, "provider.attempt", attempt.provider(), attempt.outcome().name().toLowerCase(),
                attempt.failureClass(), attempt.fallback(), attempt.elapsedMs(), circuitState);
        if (attempt.outcome() == SubagentResult.AttemptOutcome.TIMEOUT) {
            emit(task, "provider.timeout", attempt.provider(), "timeout",
                    attempt.failureClass(), attempt.fallback(), attempt.elapsedMs(), circuitState);
        }
        if ((attempt.outcome() == SubagentResult.AttemptOutcome.FAILED
                || attempt.outcome() == SubagentResult.AttemptOutcome.TIMEOUT)
                && !"closed".equals(circuitState)
                && !"retry_scheduled".equals(attempt.reasonCode())) {
            metrics.recordCircuitOpen();
            emit(task, "provider.circuit.open", attempt.provider(), "open",
                    attempt.failureClass(), attempt.fallback(), attempt.elapsedMs(), circuitState);
        }
    }

    private static void emit(SubagentTask task,
                             String event,
                             String provider,
                             String status,
                             LlmFailureClass errorClass,
                             boolean fallbackUsed,
                             long elapsedMs,
                             String circuitState) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", Instant.now().toString());
        row.put("correlationId", SafeRedactor.hashValue(task.requestId()));
        row.put("taskIdHash", SafeRedactor.hashValue(task.taskId()));
        row.put("role", SafeRedactor.traceLabelOrFallback(task.role(), "unknown"));
        row.put("event", event);
        row.put("provider", safeLabel(provider));
        row.put("status", safeLabel(status));
        row.put("elapsedMs", Math.max(0L, elapsedMs));
        row.put("fallbackUsed", fallbackUsed);
        row.put("errorClass", errorClass == null ? "UNKNOWN" : errorClass.name());
        row.put("circuitState", safeLabel(circuitState));
        try {
            TraceStore.append("agent.subagent.events", Map.copyOf(row));
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][glm-agent-core] event=trace_failed errorType={}",
                    SafeRedactor.traceLabelOrFallback(traceFailure.getClass().getSimpleName(), "unknown"));
        }
        log.info("[AWX][glm-agent-core] event={} correlationId={} taskIdHash={} role={} provider={} "
                        + "status={} elapsedMs={} fallbackUsed={} errorClass={} circuitState={}",
                event,
                row.get("correlationId"),
                row.get("taskIdHash"),
                row.get("role"),
                row.get("provider"),
                row.get("status"),
                row.get("elapsedMs"),
                row.get("fallbackUsed"),
                row.get("errorClass"),
                row.get("circuitState"));
    }

    private static String safeLabel(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return "unknown";
        }
        return raw;
    }

    private record RequestTimelineScope(String timelineId,
                                        Object previousTimeline,
                                        boolean enabled) {
        private static RequestTimelineScope disabled() {
            return new RequestTimelineScope("", null, false);
        }
    }

    private record RecentRequestEvidence(List<Map<String, Object>> timeline,
                                         List<Map<String, Object>> clientHttpLedger) {
        private RecentRequestEvidence {
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
            clientHttpLedger = clientHttpLedger == null ? List.of() : List.copyOf(clientHttpLedger);
        }

        private static RecentRequestEvidence empty() {
            return new RecentRequestEvidence(List.of(), List.of());
        }
    }

    private record ExecutionResult(SubagentResult result,
                                   RecentRequestEvidence requestEvidence) {
    }
}
