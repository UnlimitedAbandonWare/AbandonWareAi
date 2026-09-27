package com.example.lms.orchestration.control;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Deterministic, bounded output of the guard/probe composer. */
public record RagActionPlan(
        Action action,
        boolean hardGuardLocked,
        boolean lineageComplete,
        boolean probeAllowed,
        boolean retryAllowed,
        String reasonCode,
        List<RagControlFinding> findings,
        RagControlRolloutState.Mode rolloutMode,
        long rolloutGeneration,
        boolean enforced,
        RiskBenefitExceptionGate.Decision riskBenefitDecision) {

    public RagActionPlan(
            Action action,
            boolean hardGuardLocked,
            boolean lineageComplete,
            boolean probeAllowed,
            boolean retryAllowed,
            String reasonCode,
            List<RagControlFinding> findings,
            RagControlRolloutState.Mode rolloutMode,
            long rolloutGeneration,
            boolean enforced) {
        this(
                action,
                hardGuardLocked,
                lineageComplete,
                probeAllowed,
                retryAllowed,
                reasonCode,
                findings,
                rolloutMode,
                rolloutGeneration,
                enforced,
                RiskBenefitExceptionGate.Decision.notEvaluated(action));
    }

    public RagActionPlan {
        action = Objects.requireNonNullElse(action, Action.HOLD);
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "unclassified" : reasonCode;
        findings = findings == null ? List.of() : List.copyOf(findings);
        rolloutMode = Objects.requireNonNullElse(rolloutMode, RagControlRolloutState.Mode.SHADOW);
        rolloutGeneration = Math.max(0L, rolloutGeneration);
        probeAllowed = probeAllowed && action == Action.ISOLATE_EVIDENCE && !hardGuardLocked;
        retryAllowed = retryAllowed && action == Action.RETRY_ONCE && !hardGuardLocked;
        riskBenefitDecision = riskBenefitDecision == null
                ? RiskBenefitExceptionGate.Decision.notEvaluated(action)
                : riskBenefitDecision;
    }

    public RagActionPlan withRollout(RagControlRolloutState.Mode mode, boolean enforceDecision) {
        return new RagActionPlan(
                action,
                hardGuardLocked,
                lineageComplete,
                probeAllowed,
                retryAllowed,
                reasonCode,
                findings,
                mode,
                rolloutGeneration,
                enforceDecision || hardGuardLocked,
                riskBenefitDecision);
    }

    public RagActionPlan withRollout(
            RagControlRolloutState.RolloutLease lease,
            boolean enforceDecision) {
        RagControlRolloutState.RolloutLease current = lease == null
                ? new RagControlRolloutState.RolloutLease(RagControlRolloutState.Mode.SHADOW, 0L)
                : lease;
        return new RagActionPlan(
                action,
                hardGuardLocked,
                lineageComplete,
                probeAllowed,
                retryAllowed,
                reasonCode,
                findings,
                current.mode(),
                current.generation(),
                enforceDecision || hardGuardLocked,
                riskBenefitDecision);
    }

    public boolean shouldStop() {
        return (enforced || hardGuardLocked) && (action == Action.BLOCK || action == Action.HOLD);
    }

    public static RagActionPlan observabilityGap() {
        List<RagControlFinding> gaps = Arrays.stream(RagControlFinding.Stage.values())
                .map(RagControlFinding::observabilityGap)
                .toList();
        return new RagActionPlan(
                Action.HOLD,
                false,
                false,
                false,
                false,
                "observability_gap",
                gaps,
                RagControlRolloutState.Mode.SHADOW,
                0L,
                false);
    }

    public enum Action {
        CONTINUE,
        DEGRADE,
        RETRY_ONCE,
        ISOLATE_EVIDENCE,
        BLOCK,
        HOLD
    }
}
