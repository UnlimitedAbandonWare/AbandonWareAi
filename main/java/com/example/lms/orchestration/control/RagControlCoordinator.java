package com.example.lms.orchestration.control;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Coordinates composition, rollout observation, bounded action budgets, and safe diagnostics. */
@Component
public final class RagControlCoordinator {

    private final RagGuardProbeComposer composer;
    private final RagControlRolloutState rolloutState;
    private final DebugEventStore debugEventStore;

    @Autowired
    public RagControlCoordinator(
            RagGuardProbeComposer composer,
            RagControlRolloutState rolloutState,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this(composer, rolloutState,
                debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable());
    }

    public RagControlCoordinator(
            RagGuardProbeComposer composer,
            RagControlRolloutState rolloutState) {
        this(composer, rolloutState, (DebugEventStore) null);
    }

    private RagControlCoordinator(
            RagGuardProbeComposer composer,
            RagControlRolloutState rolloutState,
            DebugEventStore debugEventStore) {
        this.composer = Objects.requireNonNull(composer, "composer");
        this.rolloutState = Objects.requireNonNull(rolloutState, "rolloutState");
        this.debugEventStore = debugEventStore;
    }

    public RagActionPlan compose(Collection<RagControlFinding> findings) {
        return applyCurrentPlan(findings, false, Function.identity());
    }

    /** Compose and publish an observation without inheriting Enforce rollout state. */
    public RagActionPlan composeShadow(Collection<RagControlFinding> findings) {
        return applyCurrentPlan(findings, true, Function.identity());
    }

    <T> T applyCurrentPlan(
            Collection<RagControlFinding> findings,
            boolean shadowOnly,
            Function<RagActionPlan, T> action) {
        return applyCurrentPlan(findings, shadowOnly, ignored -> false, action);
    }

    <T> T applyCurrentPlan(
            Collection<RagControlFinding> findings,
            boolean shadowOnly,
            Predicate<RagActionPlan> forceShadow,
            Function<RagActionPlan, T> action) {
        RagActionPlan composed = composer.compose(findings);
        boolean effectiveShadowOnly = shadowOnly
                || Objects.requireNonNull(forceShadow, "forceShadow").test(composed);
        return rolloutState.withCurrentLease(lease -> {
            RagControlRolloutState.RolloutLease appliedLease = effectiveShadowOnly
                    ? new RagControlRolloutState.RolloutLease(
                            RagControlRolloutState.Mode.SHADOW, lease.generation())
                    : lease;
            RagActionPlan plan = composed.withRollout(
                    appliedLease,
                    !effectiveShadowOnly && appliedLease.mode() == RagControlRolloutState.Mode.ENFORCE);
            publish(plan, effectiveShadowOnly ? "rag-control-shadow-compose" : "rag-control-compose");
            return Objects.requireNonNull(action, "action").apply(plan);
        });
    }

    /**
     * Records the full Composer + projection cost after the user projection is
     * built. The resulting mode applies to the next RAG turn.
     */
    public RagControlRolloutState.Mode observe(
            RagActionPlan plan,
            long composerAndProjectionNanos,
            boolean hardGuardInversion,
            boolean disclosureLeak) {
        long overheadMillis = Math.max(0L, composerAndProjectionNanos / 1_000_000L);
        boolean gap = plan == null || plan.findings().isEmpty()
                || !plan.lineageComplete()
                || plan.findings().stream().map(RagControlFinding::stage).distinct().count()
                        < RagControlFinding.Stage.values().length
                || plan.findings().stream().anyMatch(this::isGapOrUnclassified);
        return rolloutState.record(new RagControlRolloutState.Observation(
                true,
                hardGuardInversion,
                disclosureLeak,
                gap,
                overheadMillis));
    }

    public RagControlRolloutState.Snapshot rolloutSnapshot() {
        return rolloutState.snapshot();
    }

    /** Builds an enforced, bounded HOLD without invoking a possibly failing composer. */
    public RagActionPlan failSafePlan(boolean hardGuardHeld) {
        return rolloutState.withCurrentLease(lease -> {
            List<RagControlFinding> findings = Arrays.stream(RagControlFinding.Stage.values())
                    .map(stage -> hardGuardHeld && stage == RagControlFinding.Stage.PROMPT_EVIDENCE
                            ? new RagControlFinding(
                                    "rag-control-failsafe",
                                    stage,
                                    RagControlFinding.FailureClass.POLICY_DENIED,
                                    RagControlFinding.EvidenceStatus.VERIFIED,
                                    RagControlFinding.Authority.HARD_GUARD,
                                    RagActionPlan.Action.HOLD,
                                    "existing_release_guard_hold",
                                    null,
                                    RagControlFinding.LineageStatus.MISSING,
                                    Map.of())
                            : RagControlFinding.observabilityGap(stage))
                    .toList();
            RagActionPlan plan = new RagActionPlan(
                    RagActionPlan.Action.HOLD,
                    hardGuardHeld,
                    false,
                    false,
                    false,
                    hardGuardHeld ? "existing_release_guard_hold" : "observability_gap",
                    findings,
                    lease.mode(),
                    lease.generation(),
                    true);
            publish(plan, "rag-control-failsafe");
            return plan;
        });
    }

    public RagControlRolloutState.Mode observeFailure(
            RagActionPlan plan,
            long composerAndProjectionNanos,
            boolean hardGuardInversion,
            boolean disclosureLeak) {
        long overheadMillis = Math.max(0L, composerAndProjectionNanos / 1_000_000L);
        return rolloutState.record(new RagControlRolloutState.Observation(
                false,
                hardGuardInversion,
                disclosureLeak,
                true,
                overheadMillis));
    }

    public void publish(RagActionPlan plan, String where) {
        if (debugEventStore == null || plan == null) {
            return;
        }
        try {
            for (RagControlFinding finding : plan.findings()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("schema", "failure-signal.v1");
                data.put("stage", finding.stage().name());
                data.put("status", status(finding.proposedAction(), plan));
                data.put("evidenceStatus", finding.evidenceStatus().name());
                data.put("failureClass", finding.failureClass().name());
                data.put("action", finding.proposedAction().name());
                data.put("reasonCode", finding.reasonCode());
                data.put("answerImpact", answerImpact(finding.proposedAction(), plan));
                data.put("rolloutMode", plan.rolloutMode().name());
                data.put("hardGuard", plan.hardGuardLocked());
                debugEventStore.emit(
                        DebugProbeType.ORCHESTRATION,
                        level(finding.proposedAction()),
                        "rag-control." + finding.stage().name().toLowerCase() + '.' + finding.reasonCode(),
                        "bounded rag control signal",
                        SafeRedactor.traceLabelOrFallback(where, "rag-control"),
                        data,
                        null);
            }
            publishRiskBenefitDecision(plan, where);
        } catch (RuntimeException publishFailure) {
            TraceStore.put("ragControl.publish.failureClass", "debug_event_publish_failed");
            TraceStore.put("ragControl.publish.errorType",
                    SafeRedactor.traceLabelOrFallback(publishFailure.getClass().getSimpleName(), "runtime_exception"));
        }
    }

    private void publishRiskBenefitDecision(RagActionPlan plan, String where) {
        RiskBenefitExceptionGate.Decision decision = plan.riskBenefitDecision();
        if (decision == null || !decision.evaluated()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decisionType", decision.type());
        if (decision.ruleStrength() != null) {
            data.put("ruleStrength", decision.ruleStrength());
        }
        data.put("catastrophicSeverity", decision.catastrophicSeverity());
        data.put("selectedOption", decision.selectedOption());
        data.put("selectedAction", decision.selectedAction());
        data.put("utilityGap", decision.utilityGap());
        data.put("candidateCount", decision.candidateCount());
        data.put("reasonCode", decision.reasonCode());
        debugEventStore.emit(
                DebugProbeType.ORCHESTRATION,
                level(decision.selectedAction()),
                "rag-control.risk-benefit." + decision.reasonCode(),
                "bounded request-scoped risk benefit decision",
                SafeRedactor.traceLabelOrFallback(where, "rag-control"),
                data,
                null);
    }

    private boolean isGapOrUnclassified(RagControlFinding finding) {
        return finding.failureClass() == RagControlFinding.FailureClass.OBSERVABILITY_GAP
                || finding.failureClass() == RagControlFinding.FailureClass.UNCLASSIFIED
                || finding.evidenceStatus() == RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED;
    }

    private DebugEventLevel level(RagActionPlan.Action action) {
        return switch (action) {
            case BLOCK, HOLD -> DebugEventLevel.WARN;
            case DEGRADE, RETRY_ONCE, ISOLATE_EVIDENCE -> DebugEventLevel.INFO;
            case CONTINUE -> DebugEventLevel.DEBUG;
        };
    }

    private String status(RagActionPlan.Action action, RagActionPlan plan) {
        boolean terminalApplied = plan != null && plan.shouldStop()
                && (action == RagActionPlan.Action.BLOCK || action == RagActionPlan.Action.HOLD);
        if (action != RagActionPlan.Action.CONTINUE && !terminalApplied) {
            return "OBSERVED";
        }
        return switch (action) {
            case CONTINUE -> "OBSERVED";
            case DEGRADE -> "DEGRADED";
            case RETRY_ONCE -> "RETRY_PLANNED";
            case ISOLATE_EVIDENCE -> "EVIDENCE_ISOLATED";
            case BLOCK -> "BLOCKED";
            case HOLD -> "HELD";
        };
    }

    private String answerImpact(RagActionPlan.Action action, RagActionPlan plan) {
        if (plan == null || !plan.enforced()) {
            return "shadow_only";
        }
        boolean terminalApplied = plan.shouldStop()
                && (action == RagActionPlan.Action.BLOCK || action == RagActionPlan.Action.HOLD);
        if (action != RagActionPlan.Action.CONTINUE && !terminalApplied) {
            return "observation_only";
        }
        return switch (action) {
            case CONTINUE -> "continue";
            case DEGRADE -> "evidence_reduced";
            case RETRY_ONCE -> "same_provider_retry_once";
            case ISOLATE_EVIDENCE -> "local_read_only_isolation";
            case BLOCK -> "answer_blocked";
            case HOLD -> "answer_held";
        };
    }

    public enum ProbeKind {
        IN_MEMORY_READ_ONLY,
        REMOTE_OR_MUTATING
    }

    public static final class RequestBudget {
        private final RagControlRolloutState rolloutState;
        private final RagControlRolloutState.RolloutLease lease;
        private final ModelRuntimeHealthTracker.RequestAttemptRoute originalRoute;
        private boolean probeUsed;
        private boolean retryUsed;

        public RequestBudget(
                RagControlRolloutState rolloutState,
                RagControlRolloutState.RolloutLease lease,
                ModelRuntimeHealthTracker.RequestAttemptRoute originalRoute) {
            this.rolloutState = Objects.requireNonNull(rolloutState, "rolloutState");
            this.lease = Objects.requireNonNull(lease, "lease");
            this.originalRoute = originalRoute;
        }

        public synchronized boolean tryAcquireLocalProbe(RagActionPlan plan, ProbeKind kind) {
            return rolloutState.withCurrentLease(current -> {
                if (!eligible(plan, current)
                        || plan.action() != RagActionPlan.Action.ISOLATE_EVIDENCE
                        || !plan.probeAllowed()
                        || kind != ProbeKind.IN_MEMORY_READ_ONLY
                        || probeUsed) {
                    return false;
                }
                probeUsed = true;
                return true;
            });
        }

        public synchronized boolean tryAcquireRetry(
                RagActionPlan plan,
                ModelRuntimeHealthTracker.RequestAttemptRoute candidateRoute) {
            return rolloutState.withCurrentLease(current -> {
                if (!eligible(plan, current)
                        || plan.action() != RagActionPlan.Action.RETRY_ONCE
                        || !plan.retryAllowed()
                        || retryUsed
                        || !sameProvenRoute(originalRoute, candidateRoute)) {
                    return false;
                }
                retryUsed = true;
                return true;
            });
        }

        private boolean eligible(
                RagActionPlan plan,
                RagControlRolloutState.RolloutLease current) {
            return plan != null
                    && plan.enforced()
                    && !plan.hardGuardLocked()
                    && lease.mode() == RagControlRolloutState.Mode.ENFORCE
                    && current.mode() == RagControlRolloutState.Mode.ENFORCE
                    && current.generation() == lease.generation()
                    && plan.rolloutGeneration() == lease.generation();
        }

        private boolean sameProvenRoute(
                ModelRuntimeHealthTracker.RequestAttemptRoute original,
                ModelRuntimeHealthTracker.RequestAttemptRoute candidate) {
            return provenRoute(original)
                    && provenRoute(candidate)
                    && Objects.equals(original.routeKeyHash(), candidate.routeKeyHash())
                    && Objects.equals(original.modelHash(), candidate.modelHash())
                    && Objects.equals(original.endpointLabel(), candidate.endpointLabel())
                    && Objects.equals(original.protocol(), candidate.protocol());
        }

        private boolean provenRoute(ModelRuntimeHealthTracker.RequestAttemptRoute route) {
            return route != null
                    && proofHash(route.routeKeyHash())
                    && proofHash(route.modelHash())
                    && known(route.endpointLabel())
                    && known(route.protocol());
        }

        private boolean proofHash(String value) {
            return value != null && value.matches("(?i)hash:[a-f0-9]{12,64}");
        }

        private boolean known(String value) {
            return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
        }

        public synchronized boolean probeUsed() {
            return probeUsed;
        }

        public synchronized boolean retryUsed() {
            return retryUsed;
        }
    }
}
