package com.example.lms.orchestration.control;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Applies PGPC decisions at learning write boundaries without creating writes itself. */
@Component
public final class RagControlLearningGate {

    private final RagControlCoordinator coordinator;
    private final RagControlRuntimeAdapter runtimeAdapter;
    private final ModelRuntimeHealthTracker runtimeHealthTracker;

    @Autowired
    public RagControlLearningGate(
            RagControlCoordinator coordinator,
            RagControlRuntimeAdapter runtimeAdapter,
            ObjectProvider<ModelRuntimeHealthTracker> runtimeHealthTrackerProvider) {
        this(
                coordinator,
                runtimeAdapter,
                runtimeHealthTrackerProvider == null ? null : runtimeHealthTrackerProvider.getIfAvailable());
    }

    public RagControlLearningGate(
            RagControlCoordinator coordinator,
            RagControlRuntimeAdapter runtimeAdapter) {
        this(coordinator, runtimeAdapter, (ModelRuntimeHealthTracker) null);
    }

    private RagControlLearningGate(
            RagControlCoordinator coordinator,
            RagControlRuntimeAdapter runtimeAdapter,
            ModelRuntimeHealthTracker runtimeHealthTracker) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.runtimeAdapter = Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        this.runtimeHealthTracker = runtimeHealthTracker;
    }

    public Decision evaluate(Boundary boundary, RagControlRuntimeAdapter.RuntimeInput input) {
        Boundary resolvedBoundary = boundary == null
                ? Boundary.TRAIN_RAG_BACKGROUND_SHADOW
                : boundary;
        Collection<RagControlFinding> findings;
        try {
            findings = runtimeAdapter.collect(input, runtimeHealthTracker);
        } catch (RuntimeException adapterFailure) {
            TraceStore.put("ragControl.learning.failureClass", "learning_adapter_failed");
            TraceStore.put("ragControl.learning.errorType", SafeRedactor.traceLabelOrFallback(
                    adapterFailure.getClass().getSimpleName(), "runtime_exception"));
            if (resolvedBoundary.enforceable()) {
                RagActionPlan failSafe = coordinator.failSafePlan(input != null && input.hardGuardHeld());
                return decision(resolvedBoundary, failSafe, true);
            }
            findings = List.of(RagControlFinding.observabilityGap(RagControlFinding.Stage.VERIFICATION));
        }
        return evaluate(resolvedBoundary, findings);
    }

    Decision evaluate(Boundary inputBoundary, Collection<RagControlFinding> findings) {
        Boundary boundary = inputBoundary == null ? Boundary.TRAIN_RAG_BACKGROUND_SHADOW : inputBoundary;
        return coordinator.applyCurrentPlan(
                findings,
                boundary.shadowOnly(),
                plan -> shouldShadowUawMissingLineage(boundary, plan, findings),
                plan -> {
            boolean effectiveShadowOnly = boundary.shadowOnly()
                    || shouldShadowUawMissingLineage(boundary, plan, findings);
            boolean holdWrites = boundary.enforceable() && plan.shouldStop();
            return decision(boundary, plan, holdWrites, effectiveShadowOnly);
        });
    }

    private Decision decision(Boundary boundary, RagActionPlan plan, boolean holdWrites) {
        return decision(boundary, plan, holdWrites, boundary.shadowOnly());
    }

    private Decision decision(
            Boundary boundary,
            RagActionPlan plan,
            boolean holdWrites,
            boolean shadowOnly) {
        TraceStore.put("ragControl.learning.boundary", boundary.name().toLowerCase());
        TraceStore.put("ragControl.learning.action", plan.action().name().toLowerCase());
        TraceStore.put("ragControl.learning.rolloutMode", plan.rolloutMode().name().toLowerCase());
        TraceStore.put("ragControl.learning.holdWrites", holdWrites);
        TraceStore.put("ragControl.learning.shadowOnly", shadowOnly);
        return new Decision(plan, holdWrites, shadowOnly);
    }

    private boolean shouldShadowUawMissingLineage(
            Boundary boundary,
            RagActionPlan plan,
            Collection<RagControlFinding> findings) {
        if (boundary != Boundary.UAW_PRE_WRITE
                || plan == null
                || plan.action() != RagActionPlan.Action.HOLD
                || plan.hardGuardLocked()
                || plan.lineageComplete()
                || !"runtime_lineage_missing".equals(plan.reasonCode())) {
            return false;
        }
        EnumSet<RagControlFinding.Stage> stages = EnumSet.noneOf(RagControlFinding.Stage.class);
        boolean lineageHoldObserved = false;
        if (findings == null) {
            return false;
        }
        for (RagControlFinding finding : findings) {
            if (finding == null) {
                continue;
            }
            stages.add(finding.stage());
            if (finding.proposedAction() == RagActionPlan.Action.HOLD
                    || finding.proposedAction() == RagActionPlan.Action.BLOCK) {
                if (finding.proposedAction() != RagActionPlan.Action.HOLD
                        || !"runtime_lineage_missing".equals(finding.reasonCode())) {
                    return false;
                }
                lineageHoldObserved = true;
            }
        }
        return lineageHoldObserved
                && stages.size() == RagControlFinding.Stage.values().length;
    }

    public enum Boundary {
        UAW_PRE_WRITE(false, true),
        CFVM_RAG_PRE_WRITE(false, true),
        TRAIN_RAG_BACKGROUND_SHADOW(true, false);

        private final boolean shadowOnly;
        private final boolean enforceable;

        Boundary(boolean shadowOnly, boolean enforceable) {
            this.shadowOnly = shadowOnly;
            this.enforceable = enforceable;
        }

        public boolean shadowOnly() {
            return shadowOnly;
        }

        public boolean enforceable() {
            return enforceable;
        }
    }

    public record Decision(RagActionPlan plan, boolean holdWrites, boolean shadowOnly) {
    }
}
