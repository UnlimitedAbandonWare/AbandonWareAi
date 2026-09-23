package com.example.lms.prompt.pose;

import java.util.List;
import java.util.Map;

public record PromptPosePlan(
        boolean enabled,
        PromptPoseArm arm,
        String routeModel,
        List<String> assistantDraftLines,
        List<String> queryBurstSeeds,
        int queryBurstMin,
        int queryBurstMax,
        int selfAskCount,
        Map<String, Double> laneWeights,
        double answerTemperature,
        double selfAskTemperature,
        int minCitations,
        double confidence,
        String reasonCode) {

    public PromptPosePlan {
        arm = arm == null ? PromptPoseArm.NO_DRAFT : arm;
        routeModel = routeModel == null ? "" : routeModel.trim();
        assistantDraftLines = assistantDraftLines == null ? List.of() : List.copyOf(assistantDraftLines);
        queryBurstSeeds = queryBurstSeeds == null ? List.of() : List.copyOf(queryBurstSeeds);
        laneWeights = laneWeights == null ? Map.of() : Map.copyOf(laneWeights);
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
    }

    public static PromptPosePlan disabled(String reasonCode) {
        return new PromptPosePlan(false, PromptPoseArm.NO_DRAFT, "", List.of(), List.of(),
                0, 0, 0, Map.of(), 0.0d, 0.0d, 0, 0.0d, reasonCode);
    }

    public static PromptPosePlan noDraft(String routeModel, String reasonCode) {
        return new PromptPosePlan(true, PromptPoseArm.NO_DRAFT, routeModel, List.of(), List.of(),
                0, 0, 0, Map.of(), 0.0d, 0.0d, 0, 0.0d, reasonCode);
    }

    public boolean hasRoutingHints() {
        return enabled && (selfAskCount > 0
                || queryBurstMax > 0
                || !laneWeights.isEmpty()
                || !assistantDraftLines.isEmpty()
                || !queryBurstSeeds.isEmpty());
    }
}
