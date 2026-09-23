package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Process-local, secret-free GLM readiness and one-shot activation coordinator.
 * A process restart is the only implicit re-arm; no credential identity is retained.
 */
public final class GlmActivationStateMachine {

    static final String GLM_PROVIDER_ID = "vercel-glm";

    private final BooleanSupplier autoActivateEnabled;
    private final BooleanSupplier externalReady;
    private final BooleanSupplier keyPresent;
    private final SubagentAgentMetrics metrics;
    private final AtomicLong epochSequence = new AtomicLong();
    private final AtomicReference<InternalState> current =
            new AtomicReference<>(InternalState.initial());
    private final ThreadLocal<ProbePermit> activePermit = new ThreadLocal<>();

    public GlmActivationStateMachine(BooleanSupplier autoActivateEnabled,
                                     BooleanSupplier externalReady,
                                     BooleanSupplier keyPresent,
                                     SubagentAgentMetrics metrics) {
        this.autoActivateEnabled = Objects.requireNonNull(autoActivateEnabled, "autoActivateEnabled");
        this.externalReady = Objects.requireNonNull(externalReady, "externalReady");
        this.keyPresent = Objects.requireNonNull(keyPresent, "keyPresent");
        this.metrics = metrics == null ? new SubagentAgentMetrics() : metrics;
    }

    static GlmActivationStateMachine disabled(SubagentAgentMetrics metrics) {
        GlmActivationStateMachine disabled = new GlmActivationStateMachine(
                () -> false, () -> false, () -> false, metrics);
        disabled.refresh("closed");
        return disabled;
    }

    /** Re-evaluates only non-secret readiness facts; terminal probe state is never re-armed. */
    public Snapshot refresh(String glmCircuitState) {
        String safeCircuit = safeCircuitState(glmCircuitState);
        while (true) {
            InternalState before = current.get();
            Readiness readiness = readiness(safeCircuit);
            InternalState after = before.withReadiness(readiness, epochSequence);
            if (after == before || current.compareAndSet(before, after)) {
                return snapshot(after);
            }
        }
    }

    /** Exactly one caller in this JVM can claim the readiness epoch. */
    public Optional<ProbePermit> tryBeginProbe(String glmCircuitState) {
        refresh(glmCircuitState);
        while (true) {
            InternalState before = current.get();
            if (before.state() != State.READY_TO_PROBE || before.probeClaimed()) {
                return Optional.empty();
            }
            ProbePermit permit = new ProbePermit(before.epochOrdinal());
            InternalState after = before.withProbeStarted();
            if (current.compareAndSet(before, after)) {
                metrics.recordGlmActivationAttempt();
                return Optional.of(permit);
            }
        }
    }

    ProbeScope enterProbeScope(ProbePermit permit) {
        Objects.requireNonNull(permit, "permit");
        InternalState state = current.get();
        if (state.state() != State.PROBING || state.epochOrdinal() != permit.epochOrdinal()) {
            throw new IllegalStateException("activation_probe_permit_invalid");
        }
        if (activePermit.get() != null) {
            throw new IllegalStateException("activation_probe_scope_already_active");
        }
        activePermit.set(permit);
        return () -> activePermit.remove();
    }

    /** GLM is callable only by the claimed probe thread or after verified activation. */
    public boolean glmProviderAttemptAllowed() {
        InternalState state = current.get();
        if (state.state() == State.GLM_ACTIVE) {
            return true;
        }
        ProbePermit permit = activePermit.get();
        return state.state() == State.PROBING
                && permit != null
                && permit.epochOrdinal() == state.epochOrdinal();
    }

    public String providerAvailabilityReason() {
        InternalState state = current.get();
        return switch (state.state()) {
            case BLOCKED_EXTERNAL -> state.reasonCode();
            case WAITING_FOR_KEY -> "missing_ai_gateway_api_key";
            case WAITING_FOR_CREDIT_CONFIRMATION -> "external_ready_not_confirmed";
            case READY_TO_PROBE -> "activation_probe_required";
            case PROBING -> "activation_probe_owned_by_another_thread";
            case GLM_ACTIVE -> "available";
            case GLM_DEGRADED, GLM_AUTH_BLOCKED, GLM_RATE_LIMITED, GLM_TRANSIENT_FAILURE ->
                    state.reasonCode();
        };
    }

    public void completeProbe(ProbePermit permit,
                              SubagentResult aggregateResult,
                              ProbeEvidence requestEvidence) {
        Objects.requireNonNull(permit, "permit");
        ProbeEvidence evidence = requestEvidence == null ? ProbeEvidence.none(false) : requestEvidence;
        while (true) {
            InternalState before = current.get();
            if (!before.probeClaimed()
                    || before.terminalState() != null
                    || before.epochOrdinal() != permit.epochOrdinal()) {
                return;
            }
            ProbeCompletion completion = classify(aggregateResult, evidence);
            InternalState after = before.withCompletion(completion);
            if (current.compareAndSet(before, after)) {
                metrics.recordGlmProbeCompletion(
                        completion.generationAttempted(),
                        completion.active(),
                        completion.fallbackUsed(),
                        completion.failureClass());
                return;
            }
        }
    }

    public Snapshot snapshot() {
        return snapshot(current.get());
    }

    private Readiness readiness(String circuitState) {
        boolean enabled = safeBoolean(autoActivateEnabled);
        if (!enabled) {
            return new Readiness(
                    State.BLOCKED_EXTERNAL, "auto_activation_disabled",
                    false, false, false, false, circuitState);
        }
        boolean ready = safeBoolean(externalReady);
        if (!ready) {
            return new Readiness(
                    State.WAITING_FOR_CREDIT_CONFIRMATION, "external_ready_not_confirmed",
                    true, false, false, false, circuitState);
        }
        boolean hasKey = safeBoolean(keyPresent);
        if (!hasKey) {
            return new Readiness(
                    State.WAITING_FOR_KEY, "missing_ai_gateway_api_key",
                    true, true, true, false, circuitState);
        }
        if ("auth_blocked".equals(circuitState)) {
            return new Readiness(
                    State.GLM_AUTH_BLOCKED, "circuit_auth_blocked",
                    true, true, true, true, circuitState);
        }
        if ("open".equals(circuitState)) {
            return new Readiness(
                    State.GLM_DEGRADED, "circuit_cooldown",
                    true, true, true, true, circuitState);
        }
        return new Readiness(
                State.READY_TO_PROBE, "ready_to_probe",
                true, true, true, true, circuitState);
    }

    private static boolean safeBoolean(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ProbeCompletion classify(SubagentResult result, ProbeEvidence evidence) {
        List<SubagentResult.Attempt> attempts = result == null ? List.of() : result.attempts();
        boolean generationAttempted = evidence.generationAttempted();
        SubagentResult.Attempt terminalGlmAttempt = attempts.stream()
                .filter(attempt -> GLM_PROVIDER_ID.equals(attempt.provider()))
                .filter(attempt -> attempt.outcome() != SubagentResult.AttemptOutcome.SELECTED)
                .filter(attempt -> attempt.outcome() != SubagentResult.AttemptOutcome.SKIPPED)
                .reduce((left, right) -> right)
                .orElse(null);
        String reasonCode = terminalGlmAttempt == null ? "glm_probe_not_attempted"
                : terminalGlmAttempt.reasonCode();
        LlmFailureClass failureClass = terminalGlmAttempt == null
                ? LlmFailureClass.DISABLED
                : terminalGlmAttempt.failureClass();
        boolean glmResultSuccess = result != null
                && result.succeeded()
                && GLM_PROVIDER_ID.equals(result.selectedProvider())
                && terminalGlmAttempt != null
                && terminalGlmAttempt.outcome() == SubagentResult.AttemptOutcome.SUCCESS;
        boolean attested = evidence.completeControlledEvidence();
        boolean active = generationAttempted && glmResultSuccess && attested;
        boolean fallbackUsed = result != null && result.fallbackUsed();

        if (active) {
            return new ProbeCompletion(
                    State.GLM_ACTIVE, "verified_provider_wire_success", true,
                    true, LlmFailureClass.NONE, false,
                    GLM_PROVIDER_ID, GLM_PROVIDER_ID, "SUCCESS", evidence);
        }
        if (glmResultSuccess) {
            return new ProbeCompletion(
                    State.GLM_DEGRADED, "wire_evidence_not_observed", generationAttempted,
                    false, LlmFailureClass.NONE, false,
                    GLM_PROVIDER_ID, result.selectedProvider(), result.status().name(), evidence);
        }

        State failureState;
        String safeReason = safeReason(reasonCode);
        if ("responses_http_403".equals(safeReason)) {
            failureState = State.BLOCKED_EXTERNAL;
        } else if ("responses_http_401".equals(safeReason)
                || failureClass == LlmFailureClass.AUTH_MISSING) {
            failureState = State.GLM_AUTH_BLOCKED;
        } else if (failureClass == LlmFailureClass.RATE_LIMIT_COOLDOWN) {
            failureState = State.GLM_RATE_LIMITED;
        } else if (failureClass == LlmFailureClass.HEALTH_DOWN
                || failureClass == LlmFailureClass.PROVIDER_ERROR) {
            failureState = State.GLM_TRANSIENT_FAILURE;
        } else {
            failureState = State.GLM_DEGRADED;
        }
        String aggregateProvider = result == null ? "none" : result.selectedProvider();
        String aggregateStatus = result == null ? "FAILED" : result.status().name();
        return new ProbeCompletion(
                failureState, safeReason, generationAttempted,
                false, failureClass, retryable(failureClass),
                GLM_PROVIDER_ID, aggregateProvider, aggregateStatus, evidence,
                fallbackUsed);
    }

    private static boolean retryable(LlmFailureClass failureClass) {
        return failureClass == LlmFailureClass.RATE_LIMIT_COOLDOWN
                || failureClass == LlmFailureClass.HEALTH_DOWN
                || failureClass == LlmFailureClass.TIMEOUT_SOFT
                || failureClass == LlmFailureClass.PROVIDER_ERROR;
    }

    private static String safeCircuitState(String raw) {
        return switch (raw == null ? "" : raw) {
            case "open" -> "open";
            case "auth_blocked" -> "auth_blocked";
            default -> "closed";
        };
    }

    private static String safeReason(String raw) {
        if (raw == null || !raw.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            return "unknown";
        }
        return raw;
    }

    private static Snapshot snapshot(InternalState state) {
        return new Snapshot(
                state.state(), state.reasonCode(), state.epochOrdinal(),
                state.autoActivateEnabled(), state.externalReady(),
                state.keyPresenceChecked(), state.keyPresent(), state.circuitState(),
                state.probeClaimed(), state.liveProbeAttempted(), state.liveProbeSucceeded(),
                state.liveProbeFailureClass().name(), state.liveProbeRetryable(),
                state.liveProbeProvider(), state.aggregateProvider(), state.aggregateStatus(),
                state.liveProbeFallbackUsed(), state.probeEvidence());
    }

    public enum State {
        BLOCKED_EXTERNAL,
        WAITING_FOR_KEY,
        WAITING_FOR_CREDIT_CONFIRMATION,
        READY_TO_PROBE,
        PROBING,
        GLM_ACTIVE,
        GLM_DEGRADED,
        GLM_AUTH_BLOCKED,
        GLM_RATE_LIMITED,
        GLM_TRANSIENT_FAILURE
    }

    public record Snapshot(
            State state,
            String reasonCode,
            long epochOrdinal,
            boolean autoActivateEnabled,
            boolean externalReady,
            boolean keyPresenceChecked,
            boolean keyPresent,
            String circuitState,
            boolean probeClaimed,
            boolean liveProbeAttempted,
            boolean liveProbeSucceeded,
            String liveProbeFailureClass,
            boolean liveProbeRetryable,
            String liveProbeProvider,
            String aggregateProvider,
            String aggregateStatus,
            boolean liveProbeFallbackUsed,
            ProbeEvidence probeEvidence) {
    }

    public record ProbeEvidence(
            boolean generationAttempted,
            boolean modelAdapterAttemptObserved,
            boolean clientHttpExchangeObserved,
            boolean clientHttpResponseObserved,
            boolean providerAttemptObserved,
            boolean wireAttemptObserved,
            boolean responseObserved,
            boolean redactedResponseEvidenceObserved) {

        public static ProbeEvidence none(boolean generationAttempted) {
            return new ProbeEvidence(
                    generationAttempted, false, false, false,
                    false, false, false, false);
        }

        public static ProbeEvidence clientOnly(boolean responseObserved) {
            return new ProbeEvidence(
                    true, true, true, true,
                    false, false, responseObserved, responseObserved);
        }

        boolean completeControlledEvidence() {
            return generationAttempted
                    && modelAdapterAttemptObserved
                    && clientHttpExchangeObserved
                    && clientHttpResponseObserved
                    && providerAttemptObserved
                    && wireAttemptObserved
                    && responseObserved
                    && redactedResponseEvidenceObserved;
        }
    }

    static final class ProbePermit {
        private final long epochOrdinal;

        private ProbePermit(long epochOrdinal) {
            this.epochOrdinal = epochOrdinal;
        }

        private long epochOrdinal() {
            return epochOrdinal;
        }
    }

    @FunctionalInterface
    interface ProbeScope extends AutoCloseable {
        @Override
        void close();
    }

    private record Readiness(
            State state,
            String reasonCode,
            boolean autoActivateEnabled,
            boolean externalReady,
            boolean keyPresenceChecked,
            boolean keyPresent,
            String circuitState) {
    }

    private record ProbeCompletion(
            State state,
            String reasonCode,
            boolean generationAttempted,
            boolean active,
            LlmFailureClass failureClass,
            boolean retryable,
            String liveProbeProvider,
            String aggregateProvider,
            String aggregateStatus,
            ProbeEvidence evidence,
            boolean fallbackUsed) {

        private ProbeCompletion(State state,
                                String reasonCode,
                                boolean generationAttempted,
                                boolean active,
                                LlmFailureClass failureClass,
                                boolean retryable,
                                String liveProbeProvider,
                                String aggregateProvider,
                                String aggregateStatus,
                                ProbeEvidence evidence) {
            this(state, reasonCode, generationAttempted, active, failureClass, retryable,
                    liveProbeProvider, aggregateProvider, aggregateStatus, evidence, false);
        }
    }

    private record InternalState(
            State state,
            String reasonCode,
            long epochOrdinal,
            boolean autoActivateEnabled,
            boolean externalReady,
            boolean keyPresenceChecked,
            boolean keyPresent,
            String circuitState,
            boolean probeClaimed,
            boolean liveProbeAttempted,
            boolean liveProbeSucceeded,
            LlmFailureClass liveProbeFailureClass,
            boolean liveProbeRetryable,
            String liveProbeProvider,
            String aggregateProvider,
            String aggregateStatus,
            boolean liveProbeFallbackUsed,
            ProbeEvidence probeEvidence,
            State terminalState,
            String terminalReasonCode) {

        private static InternalState initial() {
            return new InternalState(
                    State.BLOCKED_EXTERNAL, "not_evaluated", 0L,
                    false, false, false, false, "closed",
                    false, false, false, LlmFailureClass.NONE, false,
                    "none", "none", "NOT_ATTEMPTED", false,
                    ProbeEvidence.none(false), null, "");
        }

        private InternalState withReadiness(Readiness readiness, AtomicLong epochSequence) {
            State nextState = readiness.state();
            String nextReason = readiness.reasonCode();
            long nextEpoch = epochOrdinal;
            if (probeClaimed && state == State.PROBING
                    && readiness.state() == State.READY_TO_PROBE) {
                nextState = State.PROBING;
                nextReason = "probing";
            } else if (probeClaimed && terminalState != null
                    && ((readiness.state() == State.READY_TO_PROBE
                    && readiness.autoActivateEnabled()
                    && readiness.externalReady()
                    && readiness.keyPresent())
                    || (terminalState == State.BLOCKED_EXTERNAL
                    && "responses_http_403".equals(terminalReasonCode)
                    && readiness.state() == State.GLM_AUTH_BLOCKED))) {
                nextState = terminalState;
                nextReason = terminalReasonCode;
            } else if (!probeClaimed && readiness.state() == State.READY_TO_PROBE
                    && state != State.READY_TO_PROBE) {
                nextEpoch = epochSequence.incrementAndGet();
            }
            InternalState next = new InternalState(
                    nextState, nextReason, nextEpoch,
                    readiness.autoActivateEnabled(), readiness.externalReady(),
                    readiness.keyPresenceChecked(), readiness.keyPresent(), readiness.circuitState(),
                    probeClaimed, liveProbeAttempted, liveProbeSucceeded,
                    liveProbeFailureClass, liveProbeRetryable, liveProbeProvider,
                    aggregateProvider, aggregateStatus, liveProbeFallbackUsed,
                    probeEvidence, terminalState, terminalReasonCode);
            return equals(next) ? this : next;
        }

        private InternalState withProbeStarted() {
            return new InternalState(
                    State.PROBING, "probing", epochOrdinal,
                    autoActivateEnabled, externalReady, keyPresenceChecked, keyPresent, circuitState,
                    true, false, false, LlmFailureClass.NONE, false,
                    GLM_PROVIDER_ID, "none", "PROBING", false,
                    ProbeEvidence.none(false), terminalState, terminalReasonCode);
        }

        private InternalState withCompletion(ProbeCompletion completion) {
            return new InternalState(
                    completion.state(), completion.reasonCode(), epochOrdinal,
                    autoActivateEnabled, externalReady, keyPresenceChecked, keyPresent, circuitState,
                    true, completion.generationAttempted(), completion.active(),
                    completion.failureClass(), completion.retryable(), completion.liveProbeProvider(),
                    completion.aggregateProvider(), completion.aggregateStatus(),
                    completion.fallbackUsed(), completion.evidence(),
                    completion.state(), completion.reasonCode());
        }
    }
}
