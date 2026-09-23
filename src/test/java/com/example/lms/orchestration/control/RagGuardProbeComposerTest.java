package com.example.lms.orchestration.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagGuardProbeComposerTest {

    private final RagGuardProbeComposer composer = new RagGuardProbeComposer();

    @Test
    void softRuleSelectsTemporaryOverrideWhenTotalLossImprovesByAtLeastFiveHundredths() {
        RiskBenefitExceptionGate.RuleContext context = new RiskBenefitExceptionGate.RuleContext(
                RiskBenefitExceptionGate.RuleStrength.SOFT,
                RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                List.of(
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.KEEP,
                                RagActionPlan.Action.HOLD,
                                0.60d,
                                true),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.RELAX,
                                RagActionPlan.Action.CONTINUE,
                                0.50d,
                                true),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.MITIGATE,
                                RagActionPlan.Action.DEGRADE,
                                0.58d,
                                true),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.ALTERNATIVE,
                                RagActionPlan.Action.ISOLATE_EVIDENCE,
                                0.57d,
                                true)));

        RiskBenefitExceptionGate.Decision decision =
                new RiskBenefitExceptionGate().evaluate(RagActionPlan.Action.HOLD, context);

        assertEquals(RiskBenefitExceptionGate.DecisionType.TEMPORARY_OVERRIDE, decision.type());
        assertEquals(RiskBenefitExceptionGate.Option.RELAX, decision.selectedOption());
        assertEquals(RagActionPlan.Action.CONTINUE, decision.selectedAction());
        assertEquals(0.10d, decision.utilityGap(), 0.000_000_001d);
        assertEquals(4, decision.candidateCount());
        assertEquals("temporary_override", decision.reasonCode());
    }

    @Test
    void decisionConstructorRejectsNonAllowlistedReasonCode() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate.Decision(
                RiskBenefitExceptionGate.DecisionType.KEPT,
                RiskBenefitExceptionGate.RuleStrength.SOFT,
                RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                RiskBenefitExceptionGate.Option.KEEP,
                RagActionPlan.Action.HOLD,
                0.0d,
                4,
                "private-user@example.com");

        assertEquals("risk_benefit_unclassified", decision.reasonCode());
        assertFalse(decision.reasonCode().contains("private-user"));
    }

    @Test
    void hardRuleCannotBeRelaxedEvenWhenMetricsFavorRelaxation() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate().evaluate(
                RagActionPlan.Action.HOLD,
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.HARD,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));

        assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
        assertEquals(RiskBenefitExceptionGate.Option.KEEP, decision.selectedOption());
        assertEquals(RagActionPlan.Action.HOLD, decision.selectedAction());
        assertEquals("hard_rule_preserved", decision.reasonCode());
    }

    @Test
    void catastrophicSeverityVetoesSoftRelaxationBeforeUtilityComparison() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate().evaluate(
                RagActionPlan.Action.HOLD,
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.CATASTROPHIC,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));

        assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
        assertEquals(RagActionPlan.Action.HOLD, decision.selectedAction());
        assertEquals("catastrophic_veto", decision.reasonCode());
    }

    @Test
    void softAndPreferenceMetricsOutsideZeroToOneFailClosed() {
        List<RiskBenefitExceptionGate.RuleContext> invalidContexts = List.of(
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.50d,
                        -0.01d,
                        0.40d,
                        0.45d,
                        true,
                        true,
                        true),
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.PREFERENCE,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        1.01d,
                        0.20d,
                        0.30d,
                        0.40d,
                        true,
                        true,
                        true),
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        Double.NaN,
                        0.20d,
                        0.30d,
                        0.40d,
                        true,
                        true,
                        true));

        for (RiskBenefitExceptionGate.RuleContext context : invalidContexts) {
            RiskBenefitExceptionGate.Decision decision =
                    new RiskBenefitExceptionGate().evaluate(RagActionPlan.Action.HOLD, context);
            assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
            assertEquals(RagActionPlan.Action.HOLD, decision.selectedAction());
            assertEquals("input_out_of_range", decision.reasonCode());
        }
    }

    @Test
    void allFourOptionsAreRequiredForComparison() {
        RiskBenefitExceptionGate.RuleContext incomplete = new RiskBenefitExceptionGate.RuleContext(
                RiskBenefitExceptionGate.RuleStrength.SOFT,
                RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                List.of(
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.KEEP,
                                RagActionPlan.Action.HOLD,
                                0.80d,
                                true),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.RELAX,
                                RagActionPlan.Action.CONTINUE,
                                0.20d,
                                true)));

        RiskBenefitExceptionGate.Decision decision =
                new RiskBenefitExceptionGate().evaluate(RagActionPlan.Action.HOLD, incomplete);

        assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
        assertEquals("candidate_set_incomplete", decision.reasonCode());
    }

    @Test
    void nonKeepSelectionRequiresRecovery() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate().evaluate(
                RagActionPlan.Action.HOLD,
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        false,
                        false,
                        false));

        assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
        assertEquals(RagActionPlan.Action.HOLD, decision.selectedAction());
        assertEquals("recovery_required", decision.reasonCode());
    }

    @Test
    void utilityGapBelowFiveHundredthsKeepsOriginalAction() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate().evaluate(
                RagActionPlan.Action.HOLD,
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.55d,
                        0.51d,
                        0.53d,
                        0.54d,
                        true,
                        true,
                        true));

        assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
        assertEquals(RagActionPlan.Action.HOLD, decision.selectedAction());
        assertEquals("utility_gap_below_threshold", decision.reasonCode());
    }

    @Test
    void utilityGapAtExactlyFiveHundredthsAllowsTemporaryOverride() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate().evaluate(
                RagActionPlan.Action.HOLD,
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.55d,
                        0.50d,
                        0.54d,
                        0.53d,
                        true,
                        true,
                        true));

        assertEquals(RiskBenefitExceptionGate.DecisionType.TEMPORARY_OVERRIDE, decision.type());
        assertEquals(0.05d, decision.utilityGap(), 0.000_000_001d);
    }

    @Test
    void equalLossCandidatesUseStableSafetyOrderIndependentOfInputOrder() {
        RiskBenefitExceptionGate.RuleContext forward = riskContext(
                RiskBenefitExceptionGate.RuleStrength.PREFERENCE,
                RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                0.80d,
                0.20d,
                0.20d,
                0.20d,
                true,
                true,
                true);
        ArrayList<RiskBenefitExceptionGate.Assessment> reversed =
                new ArrayList<>(forward.assessments());
        Collections.reverse(reversed);
        RiskBenefitExceptionGate.RuleContext reverse = new RiskBenefitExceptionGate.RuleContext(
                forward.ruleStrength(),
                forward.catastrophicSeverity(),
                reversed);

        RiskBenefitExceptionGate.Decision forwardDecision =
                new RiskBenefitExceptionGate().evaluate(RagActionPlan.Action.HOLD, forward);
        RiskBenefitExceptionGate.Decision reverseDecision =
                new RiskBenefitExceptionGate().evaluate(RagActionPlan.Action.HOLD, reverse);

        assertEquals(forwardDecision, reverseDecision);
        assertEquals(RiskBenefitExceptionGate.Option.MITIGATE, forwardDecision.selectedOption());
    }

    @Test
    void preferenceRuleChoosesLowestLossRecoverableAlternative() {
        RiskBenefitExceptionGate.Decision decision = new RiskBenefitExceptionGate().evaluate(
                RagActionPlan.Action.HOLD,
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.PREFERENCE,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.80d,
                        0.60d,
                        0.40d,
                        0.30d,
                        true,
                        true,
                        true));

        assertEquals(RiskBenefitExceptionGate.DecisionType.ALTERNATIVE_SELECTED, decision.type());
        assertEquals(RiskBenefitExceptionGate.Option.ALTERNATIVE, decision.selectedOption());
        assertEquals(RagActionPlan.Action.ISOLATE_EVIDENCE, decision.selectedAction());
        assertEquals(0.50d, decision.utilityGap(), 0.000_000_001d);
    }

    @Test
    void hardGuardPrecedesRiskBenefitEvaluation() {
        RagControlFinding hard = findingWithContext(
                "policy-guard",
                RagControlFinding.Stage.REQUEST,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "hard_hold",
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));

        RagActionPlan plan = composer.compose(completeStageSet(hard));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertTrue(plan.hardGuardLocked());
        assertEquals(RiskBenefitExceptionGate.DecisionType.NOT_EVALUATED,
                plan.riskBenefitDecision().type());
    }

    @Test
    void missingLineagePrecedesRiskBenefitEvaluation() {
        RagControlFinding missingLineage = findingWithContext(
                "verification",
                RagControlFinding.Stage.ORCHESTRATION,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.MISSING,
                "soft_hold",
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));

        RagActionPlan plan = composer.compose(List.of(missingLineage));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
        assertEquals(RiskBenefitExceptionGate.DecisionType.NOT_EVALUATED,
                plan.riskBenefitDecision().type());
    }

    @Test
    void findingWithoutRuleContextPreservesLegacySelection() {
        RagControlFinding legacy = finding(
                "legacy-verification",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.FailureClass.CITATION_MISS,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.DEGRADE,
                RagControlFinding.LineageStatus.COMPLETE,
                "legacy_degrade");

        RagActionPlan plan = composer.compose(completeStageSet(legacy));

        assertEquals(RagActionPlan.Action.DEGRADE, plan.action());
        assertEquals("legacy_degrade", plan.reasonCode());
        assertEquals(RiskBenefitExceptionGate.DecisionType.NOT_EVALUATED,
                plan.riskBenefitDecision().type());
    }

    @Test
    void selectedSoftFindingAppliesTemporaryOverrideAfterAuthoritySelection() {
        RagControlFinding soft = findingWithContext(
                "verified-soft-rule",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "verified_soft_hold",
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));

        RagActionPlan plan = composer.compose(completeStageSet(soft));

        assertEquals(RagActionPlan.Action.CONTINUE, plan.action());
        assertFalse(plan.hardGuardLocked());
        assertTrue(plan.lineageComplete());
        assertEquals("verified_soft_hold", plan.reasonCode());
        assertEquals(RiskBenefitExceptionGate.DecisionType.TEMPORARY_OVERRIDE,
                plan.riskBenefitDecision().type());
    }

    @Test
    void riskBenefitDecisionSurvivesRolloutCopyButNotTheNextRequest() {
        RagControlFinding soft = findingWithContext(
                "request-one",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "request_one_hold",
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));
        RagActionPlan first = composer.compose(completeStageSet(soft));

        RagActionPlan copied = first.withRollout(RagControlRolloutState.Mode.ENFORCE, true);
        RagActionPlan nextRequest = composer.compose(completeStageSet(finding(
                "request-two",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "request_two_hold")));

        assertEquals(first.riskBenefitDecision(), copied.riskBenefitDecision());
        assertEquals(RiskBenefitExceptionGate.DecisionType.TEMPORARY_OVERRIDE,
                copied.riskBenefitDecision().type());
        assertEquals(RiskBenefitExceptionGate.DecisionType.NOT_EVALUATED,
                nextRequest.riskBenefitDecision().type());
        assertEquals(RagActionPlan.Action.HOLD, nextRequest.action());
    }

    @Test
    void distinctRuleContextsAreNotCollapsedAsCorrelatedDuplicates() {
        RagControlFinding soft = findingWithContext(
                "same-rule-source",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "same_rule",
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));
        RagControlFinding hard = findingWithContext(
                "same-rule-source",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "same_rule",
                riskContext(
                        RiskBenefitExceptionGate.RuleStrength.HARD,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        0.90d,
                        0.10d,
                        0.20d,
                        0.30d,
                        true,
                        true,
                        true));
        ArrayList<RagControlFinding> input = new ArrayList<>(completeStageSet(soft));
        input.add(hard);

        RagActionPlan plan = composer.compose(input);

        assertEquals(2, plan.findings().stream()
                .filter(finding -> "same-rule-source".equals(finding.sourceId()))
                .count());
    }

    @Test
    void oversizedInvalidContextKeepsCorrelationKeyBoundedBeforeFailClosedEvaluation() {
        ArrayList<RiskBenefitExceptionGate.Assessment> oversized = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            oversized.add(new RiskBenefitExceptionGate.Assessment(
                    RiskBenefitExceptionGate.Option.RELAX,
                    RagActionPlan.Action.CONTINUE,
                    0.10d,
                    true));
        }
        RiskBenefitExceptionGate.RuleContext context = new RiskBenefitExceptionGate.RuleContext(
                RiskBenefitExceptionGate.RuleStrength.SOFT,
                RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                oversized);
        RagControlFinding finding = findingWithContext(
                "oversized-context",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "oversized_context",
                context);

        RiskBenefitExceptionGate.Decision decision =
                new RiskBenefitExceptionGate().evaluate(RagActionPlan.Action.HOLD, context);

        assertTrue(finding.correlationKey().length() < 512);
        assertEquals(RiskBenefitExceptionGate.DecisionType.KEPT, decision.type());
        assertEquals("candidate_set_incomplete", decision.reasonCode());
    }

    @Test
    void hardGuardHoldCannotBeOverriddenByDiagnosticContinueSignals() {
        RagControlFinding hardHold = finding(
                "policy-guard",
                RagControlFinding.Stage.REQUEST,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "policy_hold");
        RagControlFinding diagnosticContinue = finding(
                "diagnostic-majority",
                RagControlFinding.Stage.FINAL,
                RagControlFinding.FailureClass.UNCLASSIFIED,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.CONTINUE,
                RagControlFinding.LineageStatus.COMPLETE,
                "looks_normal");

        RagActionPlan plan = composer.compose(List.of(
                diagnosticContinue,
                diagnosticContinue,
                diagnosticContinue,
                hardHold));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertTrue(plan.hardGuardLocked());
        assertEquals("policy_hold", plan.reasonCode());
    }

    @Test
    void semanticCollisionKeepsHardGuardInEitherInputOrder() {
        RagControlFinding diagnostic = finding(
                "same-source",
                RagControlFinding.Stage.REQUEST,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.CONTINUE,
                RagControlFinding.LineageStatus.COMPLETE,
                "diagnostic_continue");
        RagControlFinding hard = finding(
                "same-source",
                RagControlFinding.Stage.REQUEST,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.HOLD,
                RagControlFinding.LineageStatus.COMPLETE,
                "hard_hold");

        RagActionPlan diagnosticFirst = composer.compose(List.of(diagnostic, hard));
        RagActionPlan hardFirst = composer.compose(List.of(hard, diagnostic));

        assertTrue(diagnosticFirst.hardGuardLocked());
        assertTrue(hardFirst.hardGuardLocked());
        assertEquals(RagActionPlan.Action.HOLD, diagnosticFirst.action());
        assertEquals(RagActionPlan.Action.HOLD, hardFirst.action());
    }

    @Test
    void missingRuntimeLineageFailsClosedEvenWhenDiagnosticSaysContinue() {
        RagActionPlan plan = composer.compose(List.of(finding(
                "provider-diagnostic",
                RagControlFinding.Stage.LLM,
                RagControlFinding.FailureClass.MODEL_BLANK,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.CONTINUE,
                RagControlFinding.LineageStatus.MISSING,
                "blank_recovered")));

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
        assertEquals("runtime_lineage_missing", plan.reasonCode());
        assertFalse(plan.retryAllowed());
        assertFalse(plan.probeAllowed());
    }

    @Test
    void mixedRequestLineageFailsClosedIndependentlyOfInputOrder() {
        ArrayList<RagControlFinding> mixed = new ArrayList<>(completeStageSet(finding(
                "verified-final",
                RagControlFinding.Stage.FINAL,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                RagControlFinding.LineageStatus.COMPLETE,
                "final_answer_observed")));
        RagControlFinding first = mixed.get(0);
        mixed.set(0, new RagControlFinding(
                first.sourceId(),
                first.stage(),
                first.failureClass(),
                first.evidenceStatus(),
                first.authority(),
                first.proposedAction(),
                first.reasonCode(),
                "hash:999999999999",
                first.lineageStatus(),
                first.evidence()));

        RagActionPlan forward = composer.compose(mixed);
        Collections.reverse(mixed);
        RagActionPlan reverse = composer.compose(mixed);

        assertEquals(RagActionPlan.Action.HOLD, forward.action());
        assertEquals("runtime_lineage_conflict", forward.reasonCode());
        assertEquals(forward.reasonCode(), reverse.reasonCode());
        assertFalse(forward.lineageComplete());
    }

    @Test
    void verifiedSameProviderRetryIsAllowedExactlyAsAPlanCapability() {
        RagControlFinding retry = finding(
                "model-runtime",
                RagControlFinding.Stage.LLM,
                RagControlFinding.FailureClass.TIMEOUT,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.RETRY_ONCE,
                RagControlFinding.LineageStatus.COMPLETE,
                "same_provider_retry");
        RagActionPlan plan = composer.compose(completeStageSet(retry));

        assertEquals(RagActionPlan.Action.RETRY_ONCE, plan.action());
        assertTrue(plan.lineageComplete());
        assertTrue(plan.retryAllowed());
        assertFalse(plan.probeAllowed());
    }

    @Test
    void correlatedDuplicateSignalsDoNotBecomeIndependentVotes() {
        RagControlFinding duplicate = finding(
                "retrieval-stage",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.ZERO_RESULT,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.PROBE,
                RagActionPlan.Action.ISOLATE_EVIDENCE,
                RagControlFinding.LineageStatus.COMPLETE,
                "probe_local_retrieval");

        ArrayList<RagControlFinding> input = new ArrayList<>(completeStageSet(duplicate));
        input.add(duplicate);
        input.add(duplicate);
        RagActionPlan plan = composer.compose(input);

        assertEquals(1, plan.findings().stream()
                .filter(finding -> "retrieval-stage".equals(finding.sourceId()))
                .count());
        assertEquals(RagActionPlan.Action.ISOLATE_EVIDENCE, plan.action());
        assertTrue(plan.probeAllowed());
    }

    @Test
    void correlationEquivalentEvidenceUsesCanonicalTieBreakerInEitherOrder() {
        RagControlFinding canonical = new RagControlFinding(
                "retrieval-stage",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.ZERO_RESULT,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.PROBE,
                RagActionPlan.Action.ISOLATE_EVIDENCE,
                "probe_local_retrieval",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of("cacheHit", false, "returnedCount", 1));
        RagControlFinding alternative = new RagControlFinding(
                canonical.sourceId(),
                canonical.stage(),
                canonical.failureClass(),
                canonical.evidenceStatus(),
                canonical.authority(),
                canonical.proposedAction(),
                canonical.reasonCode(),
                canonical.lineageKey(),
                canonical.lineageStatus(),
                Map.of("cacheHit", true, "returnedCount", 2));
        ArrayList<RagControlFinding> forwardInput = new ArrayList<>(completeStageSet(canonical));
        forwardInput.add(alternative);
        ArrayList<RagControlFinding> reverseInput = new ArrayList<>(forwardInput);
        Collections.reverse(reverseInput);

        RagActionPlan forward = composer.compose(forwardInput);
        RagActionPlan reverse = composer.compose(reverseInput);

        assertEquals(forward, reverse);
        RagControlFinding selected = forward.findings().stream()
                .filter(finding -> canonical.sourceId().equals(finding.sourceId()))
                .findFirst()
                .orElseThrow();
        assertEquals(canonical.evidence(), selected.evidence());
    }

    @Test
    void overflowSelectionAndWholePlanAreInvariantToInputOrder() {
        ArrayList<RagControlFinding> forwardInput = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            forwardInput.add(new RagControlFinding(
                    "overflow-diagnostic",
                    RagControlFinding.Stage.REQUEST,
                    RagControlFinding.FailureClass.NONE,
                    RagControlFinding.EvidenceStatus.OBSERVED,
                    RagControlFinding.Authority.DIAGNOSTIC,
                    RagActionPlan.Action.CONTINUE,
                    "overflow_candidate",
                    String.format("hash:%012x", i),
                    RagControlFinding.LineageStatus.COMPLETE,
                    Map.of("ordinal", i)));
        }
        ArrayList<RagControlFinding> reverseInput = new ArrayList<>(forwardInput);
        Collections.reverse(reverseInput);

        RagActionPlan forward = composer.compose(forwardInput);
        RagActionPlan reverse = composer.compose(reverseInput);

        assertEquals(RagGuardProbeComposer.MAX_FINDINGS, forward.findings().size());
        assertTrue(forward.findings().stream().anyMatch(
                finding -> "finding_volume_overflow".equals(finding.reasonCode())));
        assertEquals(forward, reverse);
    }

    @Test
    void distinctDiagnosticVolumeIsBoundedAndFailsClosed() {
        ArrayList<RagControlFinding> many = new ArrayList<>();
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            for (int i = 0; i < 20; i++) {
                many.add(finding(
                        "diagnostic-" + stage.name().toLowerCase() + '-' + i,
                        stage,
                        RagControlFinding.FailureClass.NONE,
                        RagControlFinding.EvidenceStatus.OBSERVED,
                        RagControlFinding.Authority.DIAGNOSTIC,
                        RagActionPlan.Action.CONTINUE,
                        RagControlFinding.LineageStatus.COMPLETE,
                        "diagnostic_" + i));
            }
        }

        RagActionPlan plan = composer.compose(many);

        assertTrue(plan.findings().size() <= RagGuardProbeComposer.MAX_FINDINGS);
        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertTrue(plan.findings().stream().anyMatch(
                finding -> "finding_volume_overflow".equals(finding.reasonCode())));
    }

    @Test
    void findingHashesRawLineageAndRedactsRestrictedEvidence() {
        RagControlFinding finding = new RagControlFinding(
                "provider-runtime",
                RagControlFinding.Stage.LLM,
                RagControlFinding.FailureClass.RATE_LIMIT,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.DEGRADE,
                "rate_limit",
                "request-id-user@example.com",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of("authorization", "restricted-value-never-appear", "tookMs", 12));

        assertTrue(finding.lineageKey().startsWith("hash:"));
        assertNotEquals("request-id-user@example.com", finding.lineageKey());
        assertFalse(String.valueOf(finding.evidence().get("authorization")).contains("restricted-value-never-appear"));
        assertEquals(12, finding.evidence().get("tookMs"));
    }

    @Test
    void nullEvidenceValueIsSkippedWithoutBreakingTheFinding() {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("optional", null);
        evidence.put("returnedCount", 0);

        RagControlFinding finding = new RagControlFinding(
                "retrieval-stage",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.ZERO_RESULT,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.DEGRADE,
                "zero_result",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                evidence);

        assertFalse(finding.evidence().containsKey("optional"));
        assertEquals(0, finding.evidence().get("returnedCount"));
    }

    private static RiskBenefitExceptionGate.RuleContext riskContext(
            RiskBenefitExceptionGate.RuleStrength strength,
            RiskBenefitExceptionGate.CatastrophicSeverity catastrophicSeverity,
            double keepLoss,
            double relaxLoss,
            double mitigateLoss,
            double alternativeLoss,
            boolean relaxRecovery,
            boolean mitigateRecovery,
            boolean alternativeRecovery) {
        return new RiskBenefitExceptionGate.RuleContext(
                strength,
                catastrophicSeverity,
                List.of(
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.KEEP,
                                RagActionPlan.Action.HOLD,
                                keepLoss,
                                true),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.RELAX,
                                RagActionPlan.Action.CONTINUE,
                                relaxLoss,
                                relaxRecovery),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.MITIGATE,
                                RagActionPlan.Action.DEGRADE,
                                mitigateLoss,
                                mitigateRecovery),
                        new RiskBenefitExceptionGate.Assessment(
                                RiskBenefitExceptionGate.Option.ALTERNATIVE,
                                RagActionPlan.Action.ISOLATE_EVIDENCE,
                                alternativeLoss,
                        alternativeRecovery)));
    }

    private static RagControlFinding findingWithContext(
            String source,
            RagControlFinding.Stage stage,
            RagControlFinding.Authority authority,
            RagActionPlan.Action action,
            RagControlFinding.LineageStatus lineageStatus,
            String reasonCode,
            RiskBenefitExceptionGate.RuleContext context) {
        return new RagControlFinding(
                source,
                stage,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                authority,
                action,
                reasonCode,
                "hash:0123456789ab",
                lineageStatus,
                Map.of(),
                context);
    }

    private static RagControlFinding finding(
            String source,
            RagControlFinding.Stage stage,
            RagControlFinding.FailureClass failureClass,
            RagControlFinding.EvidenceStatus evidenceStatus,
            RagControlFinding.Authority authority,
            RagActionPlan.Action action,
            RagControlFinding.LineageStatus lineageStatus,
            String reasonCode) {
        return new RagControlFinding(
                source,
                stage,
                failureClass,
                evidenceStatus,
                authority,
                action,
                reasonCode,
                "hash:0123456789ab",
                lineageStatus,
                Map.of());
    }

    private static List<RagControlFinding> completeStageSet(RagControlFinding primary) {
        ArrayList<RagControlFinding> findings = new ArrayList<>();
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            if (stage == primary.stage()) {
                findings.add(primary);
                continue;
            }
            findings.add(finding(
                    "healthy-" + stage.name().toLowerCase(),
                    stage,
                    RagControlFinding.FailureClass.NONE,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.DIAGNOSTIC,
                    RagActionPlan.Action.CONTINUE,
                    RagControlFinding.LineageStatus.COMPLETE,
                    "verified_" + stage.name().toLowerCase()));
        }
        return List.copyOf(findings);
    }
}

class RagControlCoordinatorTest {

    @Test
    void evaluatedExceptionEmitsOneAllowlistedPlanLevelDebugEvent() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("debugEventStore", store);
        RagControlCoordinator coordinator = new RagControlCoordinator(
                new RagGuardProbeComposer(),
                new RagControlRolloutState(new RagControlProperties(300, 0.01d, 20)),
                beans.getBeanProvider(DebugEventStore.class));

        RagActionPlan plan = coordinator.compose(completeStageSet(riskFinding()));

        DebugEvent event = store.listByProbe(DebugProbeType.ORCHESTRATION, 100).stream()
                .filter(candidate -> SafeRedactor.hashValue("rag-control.risk-benefit.temporary_override")
                        .equals(candidate.fingerprint()))
                .findFirst()
                .orElse(null);
        assertNotNull(event);
        assertEquals(RiskBenefitExceptionGate.DecisionType.TEMPORARY_OVERRIDE,
                plan.riskBenefitDecision().type());
        assertEquals("TEMPORARY_OVERRIDE", event.data().get("decisionType"));
        assertEquals("SOFT", event.data().get("ruleStrength"));
        assertEquals("NONE", event.data().get("catastrophicSeverity"));
        assertEquals("RELAX", event.data().get("selectedOption"));
        assertEquals("CONTINUE", event.data().get("selectedAction"));
        assertEquals("temporary_override", event.data().get("reasonCode"));
        assertTrue(event.data().get("utilityGap") instanceof Number);
        assertEquals(4, ((Number) event.data().get("candidateCount")).intValue());
        assertFalse(event.data().containsKey("assessments"));
        assertFalse(event.data().containsKey("ruleText"));
        assertFalse(event.data().containsKey("evidence"));
    }

    @Test
    void shadowDoesNotEnforceNewDecisionButNeverUnlocksHardGuard() {
        RagControlCoordinator coordinator = new RagControlCoordinator(
                new RagGuardProbeComposer(),
                new RagControlRolloutState(new RagControlProperties(300, 0.01d, 20)));
        RagControlFinding diagnosticHold = finding(
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.HOLD,
                "diagnostic_hold");

        RagActionPlan shadow = coordinator.compose(List.of(diagnosticHold));
        RagActionPlan hard = coordinator.compose(List.of(finding(
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.BLOCK,
                "hard_block")));

        assertEquals(RagControlRolloutState.Mode.SHADOW, shadow.rolloutMode());
        assertFalse(shadow.enforced());
        assertEquals(RagActionPlan.Action.HOLD, shadow.action());
        assertTrue(hard.enforced());
        assertTrue(hard.hardGuardLocked());
        assertEquals(RagActionPlan.Action.BLOCK, hard.action());
    }

    @Test
    void requestBudgetAllowsAtMostOneProbeAndOneSameProviderRetry() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlRolloutState.RolloutLease lease = rollout.lease();
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.RequestAttemptRoute originalRoute = tracker.redactedRequestAttemptRoute(
                "route-a", "model-a", "localhost", "openai_chat_completions");
        ModelRuntimeHealthTracker.RequestAttemptRoute otherRoute = tracker.redactedRequestAttemptRoute(
                "route-b", "model-b", "localhost", "openai_chat_completions");
        RagControlCoordinator.RequestBudget budget = new RagControlCoordinator.RequestBudget(
                rollout, lease, originalRoute);
        RagActionPlan probe = new RagGuardProbeComposer().compose(completeStageSet(finding(
                RagControlFinding.Authority.PROBE,
                RagActionPlan.Action.ISOLATE_EVIDENCE,
                "local_probe"))).withRollout(lease, true);
        RagActionPlan retry = new RagGuardProbeComposer().compose(completeStageSet(finding(
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.RETRY_ONCE,
                "same_provider_retry"))).withRollout(lease, true);

        assertFalse(budget.tryAcquireLocalProbe(
                probe, RagControlCoordinator.ProbeKind.REMOTE_OR_MUTATING));
        assertTrue(budget.tryAcquireLocalProbe(
                probe, RagControlCoordinator.ProbeKind.IN_MEMORY_READ_ONLY));
        assertFalse(budget.tryAcquireLocalProbe(
                probe, RagControlCoordinator.ProbeKind.IN_MEMORY_READ_ONLY));
        assertFalse(budget.tryAcquireRetry(retry, otherRoute));
        assertTrue(budget.tryAcquireRetry(retry, originalRoute));
        assertFalse(budget.tryAcquireRetry(retry, originalRoute));
        assertTrue(budget.probeUsed());
        assertTrue(budget.retryUsed());
    }

    @Test
    void staleEnforceLeaseCannotAuthorizeNewWorkAfterCriticalRollback() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlRolloutState.RolloutLease lease = rollout.lease();
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "route-a", "model-a", "localhost", "openai_chat_completions");
        RagControlCoordinator.RequestBudget budget = new RagControlCoordinator.RequestBudget(
                rollout, lease, route);
        RagActionPlan retry = new RagGuardProbeComposer().compose(completeStageSet(finding(
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.RETRY_ONCE,
                "same_provider_retry"))).withRollout(lease, true);

        rollout.record(new RagControlRolloutState.Observation(false, true, false, false, 1));

        assertFalse(budget.tryAcquireRetry(retry, route));
        assertEquals(RagControlRolloutState.Mode.SHADOW, rollout.mode());
    }

    @Test
    void incompleteStageObservationsNeverPromoteEnforcement() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(300, 0.01d, 20));
        RagControlCoordinator coordinator = new RagControlCoordinator(
                new RagGuardProbeComposer(), rollout);
        RagControlFinding verified = finding(
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "verified_continue");

        for (int i = 0; i < 300; i++) {
            RagActionPlan shadow = coordinator.compose(List.of(verified));
            coordinator.observe(shadow, 10_000_000L, false, false);
        }
        RagActionPlan promoted = coordinator.compose(List.of(verified));

        assertEquals(RagControlRolloutState.Mode.SHADOW, promoted.rolloutMode());
        assertFalse(promoted.enforced());
    }

    @Test
    void threeHundredCompleteStageObservationsPromoteTheNextComposition() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(300, 0.01d, 20));
        RagControlCoordinator coordinator = new RagControlCoordinator(
                new RagGuardProbeComposer(), rollout);
        List<RagControlFinding> verified = completeStageSet(finding(
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "verified_continue"));

        for (int i = 0; i < 300; i++) {
            RagActionPlan shadow = coordinator.compose(verified);
            coordinator.observe(shadow, 10_000_000L, false, false);
        }
        RagActionPlan promoted = coordinator.compose(verified);

        assertEquals(RagControlRolloutState.Mode.ENFORCE, promoted.rolloutMode());
        assertTrue(promoted.enforced());
    }

    private static RagControlFinding finding(
            RagControlFinding.Authority authority,
            RagActionPlan.Action action,
            String reason) {
        return new RagControlFinding(
                "coordinator-test",
                RagControlFinding.Stage.ORCHESTRATION,
                RagControlFinding.FailureClass.SILENT_FAILURE,
                RagControlFinding.EvidenceStatus.VERIFIED,
                authority,
                action,
                reason,
                "hash:fedcba987654",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of());
    }

    private static RagControlFinding riskFinding() {
        return new RagControlFinding(
                "coordinator-risk-benefit",
                RagControlFinding.Stage.ORCHESTRATION,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                "soft_hold",
                "hash:fedcba987654",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of(),
                new RiskBenefitExceptionGate.RuleContext(
                        RiskBenefitExceptionGate.RuleStrength.SOFT,
                        RiskBenefitExceptionGate.CatastrophicSeverity.NONE,
                        List.of(
                                new RiskBenefitExceptionGate.Assessment(
                                        RiskBenefitExceptionGate.Option.KEEP,
                                        RagActionPlan.Action.HOLD,
                                        0.90d,
                                        true),
                                new RiskBenefitExceptionGate.Assessment(
                                        RiskBenefitExceptionGate.Option.RELAX,
                                        RagActionPlan.Action.CONTINUE,
                                        0.10d,
                                        true),
                                new RiskBenefitExceptionGate.Assessment(
                                        RiskBenefitExceptionGate.Option.MITIGATE,
                                        RagActionPlan.Action.DEGRADE,
                                        0.20d,
                                        true),
                                new RiskBenefitExceptionGate.Assessment(
                                        RiskBenefitExceptionGate.Option.ALTERNATIVE,
                                        RagActionPlan.Action.ISOLATE_EVIDENCE,
                                        0.30d,
                                        true))));
    }

    private static List<RagControlFinding> completeStageSet(RagControlFinding primary) {
        ArrayList<RagControlFinding> findings = new ArrayList<>();
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            if (stage == primary.stage()) {
                findings.add(primary);
                continue;
            }
            findings.add(new RagControlFinding(
                    "coordinator-healthy-" + stage.name().toLowerCase(),
                    stage,
                    RagControlFinding.FailureClass.NONE,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.DIAGNOSTIC,
                    RagActionPlan.Action.CONTINUE,
                    "verified_" + stage.name().toLowerCase(),
                    "hash:fedcba987654",
                    RagControlFinding.LineageStatus.COMPLETE,
                    Map.of()));
        }
        return List.copyOf(findings);
    }
}
