package com.example.lms.prompt.pose;

import java.util.List;
import java.util.Map;

public record PromptPoseApplicationDecision(
        boolean enabled,
        String intentSlot,
        String evidenceSlot,
        String failureSlot,
        String feedbackSlot,
        String feedbackTile,
        String decisionHash12,
        String reasonCode,
        int queryBurstMax,
        int selfAskCount,
        double answerTemperature,
        double selfAskTemperature,
        int minCitations,
        Map<String, Double> laneWeights,
        Map<String, Double> callBudgetRatios,
        Map<String, Double> timeboxRatios,
        double riskPenaltyLambda,
        int minLaneCoverage,
        double confidence,
        double feedbackMean,
        long feedbackCount,
        String compressionMode) {

    public PromptPoseApplicationDecision {
        intentSlot = safe(intentSlot, "general");
        evidenceSlot = safe(evidenceSlot, "balanced");
        failureSlot = safe(failureSlot, "none");
        feedbackSlot = safe(feedbackSlot, "none");
        feedbackTile = safe(feedbackTile, intentSlot + ":" + failureSlot);
        decisionHash12 = safe(decisionHash12, "");
        reasonCode = safe(reasonCode, "application");
        queryBurstMax = Math.max(0, queryBurstMax);
        selfAskCount = Math.max(0, Math.min(3, selfAskCount));
        answerTemperature = clamp(answerTemperature, 0.0d, 1.0d);
        selfAskTemperature = clamp(selfAskTemperature, 0.0d, 1.0d);
        minCitations = Math.max(0, Math.min(8, minCitations));
        laneWeights = laneWeights == null ? Map.of() : Map.copyOf(laneWeights);
        callBudgetRatios = callBudgetRatios == null ? Map.of() : Map.copyOf(callBudgetRatios);
        timeboxRatios = timeboxRatios == null ? Map.of() : Map.copyOf(timeboxRatios);
        riskPenaltyLambda = clamp(riskPenaltyLambda, 0.0d, 1.0d);
        minLaneCoverage = Math.max(1, Math.min(3, minLaneCoverage));
        confidence = clamp(confidence, 0.0d, 1.0d);
        feedbackMean = Double.isFinite(feedbackMean) ? clamp(feedbackMean, 0.0d, 1.0d) : 0.0d;
        feedbackCount = Math.max(0L, feedbackCount);
        compressionMode = safe(compressionMode, "off");
    }

    public static PromptPoseApplicationDecision disabled(String reason) {
        return new PromptPoseApplicationDecision(false, "disabled", "balanced", "none", "none",
                "disabled:none", "", safe(reason, "disabled"), 0, 0, 0.0d, 0.0d, 0,
                Map.of(), Map.of(), Map.of(), 0.0d, 1, 0.0d, 0.0d, 0L, "off");
    }

    public PromptPosePlan toPlan(String routeModel) {
        if (!enabled) {
            return PromptPosePlan.disabled(reasonCode);
        }
        return new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, routeModel,
                List.of(), List.of(), queryBurstMax > 0 ? 1 : 0, queryBurstMax,
                selfAskCount, laneWeights, answerTemperature, selfAskTemperature,
                minCitations, confidence, reasonCode);
    }

    private static String safe(String value, String fallback) {
        String out = value == null ? "" : value.trim();
        return out.isEmpty() ? fallback : out;
    }

    private static double clamp(double value, double low, double high) {
        if (!Double.isFinite(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }
}
