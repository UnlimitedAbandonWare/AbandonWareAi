package com.example.lms.orchestration.control;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagControlLearningGateTest {

    @Test
    void enforceableLearningBoundaryDoesNotApplyNewHoldDuringShadow() {
        RagControlLearningGate gate = gate(new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20)));

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                runtimeLineageMissingFindings());

        assertEquals(RagControlRolloutState.Mode.SHADOW, decision.plan().rolloutMode());
        assertEquals(RagActionPlan.Action.HOLD, decision.plan().action());
        assertFalse(decision.plan().enforced());
        assertFalse(decision.holdWrites());
    }

    @Test
    void existingHardGuardStillHoldsLearningWritesDuringShadow() {
        RagControlLearningGate gate = gate(new RagControlRolloutState(
                new RagControlProperties(300, 0.01d, 20)));
        RagControlFinding hardGuard = new RagControlFinding(
                "existing-release-guard",
                RagControlFinding.Stage.PROMPT_EVIDENCE,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.HOLD,
                "existing_release_guard_hold",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of());

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                List.of(hardGuard));

        assertEquals(RagControlRolloutState.Mode.SHADOW, decision.plan().rolloutMode());
        assertTrue(decision.plan().hardGuardLocked());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void uawMissingLineagePlanIsForcedToShadowAfterPromotion() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlLearningGate gate = gate(rollout);

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                runtimeLineageMissingFindings());

        assertEquals(RagControlRolloutState.Mode.SHADOW, decision.plan().rolloutMode());
        assertFalse(decision.plan().enforced());
        assertFalse(decision.plan().shouldStop());
        assertFalse(decision.holdWrites());
        assertTrue(decision.shadowOnly());
        assertEquals("runtime_lineage_missing", decision.plan().reasonCode());
    }

    @Test
    void cfvmMissingLineagePlanStillHoldsAfterPromotion() {
        RagControlLearningGate gate = gate(promotedRollout());

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.CFVM_RAG_PRE_WRITE,
                runtimeLineageMissingFindings());

        assertEquals(RagControlRolloutState.Mode.ENFORCE, decision.plan().rolloutMode());
        assertTrue(decision.plan().enforced());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void uawRuntimeLineageConflictStillHoldsAfterPromotion() {
        RagControlLearningGate gate = gate(promotedRollout());

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                completeFindings("hash:111111111111", "hash:222222222222", false));

        assertEquals("runtime_lineage_conflict", decision.plan().reasonCode());
        assertEquals(RagControlRolloutState.Mode.ENFORCE, decision.plan().rolloutMode());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void uawCompleteLineageVerificationHoldStillEnforces() {
        RagControlLearningGate gate = gate(promotedRollout());

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                completeFindings("hash:111111111111", "hash:111111111111", true));

        assertEquals("verification_rejected", decision.plan().reasonCode());
        assertTrue(decision.plan().lineageComplete());
        assertEquals(RagControlRolloutState.Mode.ENFORCE, decision.plan().rolloutMode());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void uawMissingLineageWithIndependentVerificationHoldStillEnforces() {
        List<RagControlFinding> findings = new java.util.ArrayList<>(runtimeLineageMissingFindings());
        findings.add(independentStop(
                RagControlFinding.Stage.VERIFICATION,
                RagActionPlan.Action.HOLD,
                "verification_rejected"));

        RagControlLearningGate.Decision decision = gate(promotedRollout()).evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                findings);

        assertEquals(RagControlRolloutState.Mode.ENFORCE, decision.plan().rolloutMode());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void uawMissingLineageWithIndependentBlockStillEnforces() {
        List<RagControlFinding> findings = new java.util.ArrayList<>(runtimeLineageMissingFindings());
        findings.add(independentStop(
                RagControlFinding.Stage.FINAL,
                RagActionPlan.Action.BLOCK,
                "independent_policy_block"));

        RagControlLearningGate.Decision decision = gate(promotedRollout()).evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                findings);

        assertEquals(RagControlRolloutState.Mode.ENFORCE, decision.plan().rolloutMode());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void uawSixStageMissingLineageCannotUseTeamKillException() {
        List<RagControlFinding> findings = runtimeLineageMissingFindings().stream()
                .filter(finding -> finding.stage() != RagControlFinding.Stage.FINAL)
                .toList();

        RagControlLearningGate.Decision decision = gate(promotedRollout()).evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                findings);

        assertEquals(RagControlRolloutState.Mode.ENFORCE, decision.plan().rolloutMode());
        assertTrue(decision.plan().shouldStop());
        assertTrue(decision.holdWrites());
        assertFalse(decision.shadowOnly());
    }

    @Test
    void backgroundTrainRagStaysShadowOnlyEvenWhenRuntimeModeIsEnforce() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlLearningGate gate = gate(rollout);

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.TRAIN_RAG_BACKGROUND_SHADOW,
                List.of(missingLineage()));

        assertEquals(RagControlRolloutState.Mode.SHADOW, decision.plan().rolloutMode());
        assertFalse(decision.plan().enforced());
        assertFalse(decision.holdWrites());
        assertTrue(decision.shadowOnly());
    }

    @Test
    void adapterFailureHoldsEnforceableWritesEvenDuringShadow() {
        RagControlRuntimeAdapter adapter = mock(RagControlRuntimeAdapter.class);
        when(adapter.collect(
                any(RagControlRuntimeAdapter.RuntimeInput.class),
                nullable(com.example.lms.llm.ModelRuntimeHealthTracker.class)))
                .thenThrow(new IllegalStateException("private-learning-detail"));
        RagControlLearningGate gate = new RagControlLearningGate(
                new RagControlCoordinator(
                        new RagGuardProbeComposer(),
                        new RagControlRolloutState(new RagControlProperties(20, 0.01d, 20))),
                adapter);

        RagControlLearningGate.Decision decision = gate.evaluate(
                RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded(true));

        assertTrue(decision.holdWrites());
        assertTrue(decision.plan().shouldStop());
        assertFalse(decision.shadowOnly());
    }

    private static RagControlRolloutState promotedRollout() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        return rollout;
    }

    private static List<RagControlFinding> completeFindings(
            String firstLineage,
            String secondLineage,
            boolean verificationHold) {
        return Arrays.stream(RagControlFinding.Stage.values())
                .map(stage -> new RagControlFinding(
                        "learning-" + stage.name().toLowerCase(),
                        stage,
                        verificationHold && stage == RagControlFinding.Stage.VERIFICATION
                                ? RagControlFinding.FailureClass.CITATION_MISS
                                : RagControlFinding.FailureClass.NONE,
                        RagControlFinding.EvidenceStatus.VERIFIED,
                        RagControlFinding.Authority.VERIFICATION,
                        verificationHold && stage == RagControlFinding.Stage.VERIFICATION
                                ? RagActionPlan.Action.HOLD
                                : RagActionPlan.Action.CONTINUE,
                        verificationHold && stage == RagControlFinding.Stage.VERIFICATION
                                ? "verification_rejected"
                                : "observed",
                        stage.ordinal() == 0 ? firstLineage : secondLineage,
                        RagControlFinding.LineageStatus.COMPLETE,
                        Map.of()))
                .toList();
    }

    private static List<RagControlFinding> runtimeLineageMissingFindings() {
        return Arrays.stream(RagControlFinding.Stage.values())
                .map(stage -> new RagControlFinding(
                        "learning-" + stage.name().toLowerCase(java.util.Locale.ROOT),
                        stage,
                        stage == RagControlFinding.Stage.LLM
                                ? RagControlFinding.FailureClass.OBSERVABILITY_GAP
                                : RagControlFinding.FailureClass.NONE,
                        stage == RagControlFinding.Stage.LLM
                                ? RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED
                                : RagControlFinding.EvidenceStatus.OBSERVED,
                        RagControlFinding.Authority.VERIFICATION,
                        stage == RagControlFinding.Stage.LLM
                                ? RagActionPlan.Action.HOLD
                                : RagActionPlan.Action.CONTINUE,
                        stage == RagControlFinding.Stage.LLM
                                ? "runtime_lineage_missing"
                                : "observed",
                        null,
                        RagControlFinding.LineageStatus.MISSING,
                        Map.of()))
                .toList();
    }

    private static RagControlFinding independentStop(
            RagControlFinding.Stage stage,
            RagActionPlan.Action action,
            String reasonCode) {
        return new RagControlFinding(
                "independent-stop-" + stage.name().toLowerCase(java.util.Locale.ROOT),
                stage,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                action,
                reasonCode,
                null,
                RagControlFinding.LineageStatus.MISSING,
                Map.of());
    }

    private static RagControlLearningGate gate(RagControlRolloutState rollout) {
        return new RagControlLearningGate(
                new RagControlCoordinator(new RagGuardProbeComposer(), rollout),
                new RagControlRuntimeAdapter());
    }

    private static RagControlFinding missingLineage() {
        return new RagControlFinding(
                "learning-boundary",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                "runtime_lineage_missing",
                null,
                RagControlFinding.LineageStatus.MISSING,
                Map.of());
    }
}
