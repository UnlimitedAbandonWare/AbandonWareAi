package com.example.lms.orchestration.control;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Makes one request-scoped exception decision without mutating the rule that
 * produced the original action.
 */
public final class RiskBenefitExceptionGate {

    public static final double MIN_UTILITY_GAP = 0.05d;
    private static final double COMPARISON_EPSILON = 0.000_000_001d;

    public Decision evaluate(RagActionPlan.Action originalAction, RuleContext context) {
        RagActionPlan.Action keepAction = Objects.requireNonNullElse(
                originalAction, RagActionPlan.Action.HOLD);
        if (context == null) {
            return Decision.notEvaluated(keepAction);
        }

        int candidateCount = context.assessments().size();
        if (context.ruleStrength() == RuleStrength.HARD) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "hard_rule_preserved");
        }
        if (context.ruleStrength() == null || context.catastrophicSeverity() == null) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "input_invalid");
        }
        if (context.catastrophicSeverity() == CatastrophicSeverity.CATASTROPHIC) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "catastrophic_veto");
        }

        Map<Option, Assessment> byOption = new EnumMap<>(Option.class);
        for (Assessment assessment : context.assessments()) {
            if (assessment != null
                    && assessment.option() != null
                    && assessment.action() != null) {
                byOption.putIfAbsent(assessment.option(), assessment);
            }
        }
        Assessment keep = byOption.get(Option.KEEP);
        if (context.assessments().size() != Option.values().length
                || byOption.size() != Option.values().length
                || keep == null) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "candidate_set_incomplete");
        }
        boolean metricOutOfRange = byOption.values().stream()
                .mapToDouble(Assessment::totalLoss)
                .anyMatch(value -> !Double.isFinite(value) || value < 0.0d || value > 1.0d);
        if (metricOutOfRange) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "input_out_of_range");
        }
        if (keep.action() != keepAction) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "keep_action_mismatch");
        }

        Assessment selected = byOption.values().stream()
                .filter(candidate -> candidate.option() != Option.KEEP)
                .filter(Assessment::recoveryAvailable)
                .min(Comparator
                        .comparingDouble(Assessment::totalLoss)
                        .thenComparingInt(candidate -> optionRank(candidate.option()))
                        .thenComparing(candidate -> String.valueOf(candidate.action())))
                .orElse(null);
        if (selected == null) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    0.0d,
                    "recovery_required");
        }

        double utilityGap = keep.totalLoss() - selected.totalLoss();
        if (utilityGap + COMPARISON_EPSILON < MIN_UTILITY_GAP) {
            return Decision.kept(
                    keepAction,
                    context,
                    candidateCount,
                    utilityGap,
                    "utility_gap_below_threshold");
        }
        return Decision.selected(selected, context, candidateCount, utilityGap);
    }

    private static int optionRank(Option option) {
        return switch (option) {
            case MITIGATE -> 0;
            case ALTERNATIVE -> 1;
            case RELAX -> 2;
            case KEEP -> 3;
        };
    }

    public enum RuleStrength {
        HARD,
        SOFT,
        PREFERENCE
    }

    public enum CatastrophicSeverity {
        NONE,
        CATASTROPHIC
    }

    public enum Option {
        KEEP,
        RELAX,
        MITIGATE,
        ALTERNATIVE
    }

    public enum DecisionType {
        NOT_EVALUATED,
        KEPT,
        TEMPORARY_OVERRIDE,
        MITIGATED,
        ALTERNATIVE_SELECTED
    }

    public record Assessment(
            Option option,
            RagActionPlan.Action action,
            double totalLoss,
            boolean recoveryAvailable) {

        String stableKey() {
            return String.valueOf(option) + '|'
                    + String.valueOf(action) + '|'
                    + Double.toString(totalLoss) + '|'
                    + recoveryAvailable;
        }
    }

    public record RuleContext(
            RuleStrength ruleStrength,
            CatastrophicSeverity catastrophicSeverity,
            List<Assessment> assessments) {

        public RuleContext {
            assessments = assessments == null ? List.of() : List.copyOf(assessments);
        }

        String stableKey() {
            String prefix = String.valueOf(ruleStrength) + '|'
                    + String.valueOf(catastrophicSeverity) + '|';
            if (assessments.size() != Option.values().length) {
                return prefix + "invalidCandidateCount=" + assessments.size();
            }
            return prefix + assessments.stream()
                    .map(Assessment::stableKey)
                    .sorted()
                    .reduce((left, right) -> left + ';' + right)
                    .orElse("empty");
        }
    }

    public record Decision(
            DecisionType type,
            RuleStrength ruleStrength,
            CatastrophicSeverity catastrophicSeverity,
            Option selectedOption,
            RagActionPlan.Action selectedAction,
            double utilityGap,
            int candidateCount,
            String reasonCode) {

        public Decision {
            type = Objects.requireNonNullElse(type, DecisionType.NOT_EVALUATED);
            catastrophicSeverity = Objects.requireNonNullElse(
                    catastrophicSeverity, CatastrophicSeverity.NONE);
            selectedOption = Objects.requireNonNullElse(selectedOption, Option.KEEP);
            selectedAction = Objects.requireNonNullElse(selectedAction, RagActionPlan.Action.HOLD);
            utilityGap = Double.isFinite(utilityGap) ? utilityGap : 0.0d;
            candidateCount = Math.max(0, candidateCount);
            reasonCode = allowlistedReasonCode(reasonCode);
        }

        public boolean evaluated() {
            return type != DecisionType.NOT_EVALUATED;
        }

        public static Decision notEvaluated(RagActionPlan.Action action) {
            return new Decision(
                    DecisionType.NOT_EVALUATED,
                    null,
                    CatastrophicSeverity.NONE,
                    Option.KEEP,
                    action,
                    0.0d,
                    0,
                    "risk_benefit_not_evaluated");
        }

        private static String allowlistedReasonCode(String value) {
            if (value == null || value.isBlank()) {
                return "risk_benefit_not_evaluated";
            }
            return switch (value) {
                case "risk_benefit_not_evaluated",
                        "hard_rule_preserved",
                        "input_invalid",
                        "catastrophic_veto",
                        "candidate_set_incomplete",
                        "input_out_of_range",
                        "keep_action_mismatch",
                        "recovery_required",
                        "utility_gap_below_threshold",
                        "temporary_override",
                        "mitigation_selected",
                        "alternative_selected",
                        "keep_selected",
                        "risk_benefit_unclassified" -> value;
                default -> "risk_benefit_unclassified";
            };
        }

        private static Decision kept(
                RagActionPlan.Action action,
                RuleContext context,
                int candidateCount,
                double utilityGap,
                String reasonCode) {
            return new Decision(
                    DecisionType.KEPT,
                    context.ruleStrength(),
                    context.catastrophicSeverity(),
                    Option.KEEP,
                    action,
                    utilityGap,
                    candidateCount,
                    reasonCode);
        }

        private static Decision selected(
                Assessment assessment,
                RuleContext context,
                int candidateCount,
                double utilityGap) {
            DecisionType type = switch (assessment.option()) {
                case RELAX -> DecisionType.TEMPORARY_OVERRIDE;
                case MITIGATE -> DecisionType.MITIGATED;
                case ALTERNATIVE -> DecisionType.ALTERNATIVE_SELECTED;
                case KEEP -> DecisionType.KEPT;
            };
            String reasonCode = switch (type) {
                case TEMPORARY_OVERRIDE -> "temporary_override";
                case MITIGATED -> "mitigation_selected";
                case ALTERNATIVE_SELECTED -> "alternative_selected";
                case KEPT -> "keep_selected";
                case NOT_EVALUATED -> "risk_benefit_not_evaluated";
            };
            return new Decision(
                    type,
                    context.ruleStrength(),
                    context.catastrophicSeverity(),
                    assessment.option(),
                    assessment.action(),
                    utilityGap,
                    candidateCount,
                    reasonCode);
        }
    }
}
