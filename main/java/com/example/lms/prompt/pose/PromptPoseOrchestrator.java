package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;

public class PromptPoseOrchestrator {

    private final PromptPoseProperties props;
    private final PromptPoseDraftGenerator draftGenerator;
    private final PromptPosePlanSanitizer planSanitizer;
    private final PromptPoseApplicationJudge applicationJudge;

    public PromptPoseOrchestrator(
            PromptPoseProperties props,
            PromptPoseDraftGenerator draftGenerator,
            PromptPosePlanSanitizer planSanitizer) {
        this(props, draftGenerator, planSanitizer, null);
    }

    public PromptPoseOrchestrator(
            PromptPoseProperties props,
            PromptPoseDraftGenerator draftGenerator,
            PromptPosePlanSanitizer planSanitizer,
            PromptPoseApplicationJudge applicationJudge) {
        this.props = props == null ? new PromptPoseProperties() : props;
        this.draftGenerator = draftGenerator;
        this.planSanitizer = planSanitizer == null ? new PromptPosePlanSanitizer(this.props) : planSanitizer;
        this.applicationJudge = applicationJudge;
    }

    public PromptPosePlan plan(String finalQuery, int requestedMaxQueries) {
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(finalQuery, props);
        if (!props.isEnabled()) {
            PromptPoseTrace.writeDisabled("disabled", input);
            return PromptPosePlan.disabled("disabled");
        }
        if (input.blocked()) {
            PromptPosePlan blocked = PromptPosePlan.noDraft(draftRoute(), input.skipReason());
            PromptPoseTrace.writePlan(blocked, input);
            return blocked;
        }
        PromptPosePlan sanitized;
        if (draftGenerator == null) {
            sanitized = PromptPosePlan.noDraft(draftRoute(), "draft_dependency_missing");
        } else {
            PromptPosePlan raw = draftGenerator.generate(input);
            sanitized = planSanitizer.sanitize(raw);
        }
        PromptPoseApplicationDecision decision = null;
        if (applicationJudge != null) {
            decision = applicationJudge.decide(input, requestedMaxQueries);
            sanitized = applicationJudge.mergeIntoPlan(sanitized, decision, draftRoute());
            sanitized = planSanitizer.sanitize(sanitized);
        }
        if (requestedMaxQueries > 0 && sanitized.queryBurstMax() > requestedMaxQueries) {
            sanitized = new PromptPosePlan(
                    sanitized.enabled(),
                    sanitized.arm(),
                    sanitized.routeModel(),
                    sanitized.assistantDraftLines(),
                    sanitized.queryBurstSeeds(),
                    Math.min(sanitized.queryBurstMin(), requestedMaxQueries),
                    requestedMaxQueries,
                    sanitized.selfAskCount(),
                    sanitized.laneWeights(),
                    sanitized.answerTemperature(),
                    sanitized.selfAskTemperature(),
                    sanitized.minCitations(),
                    sanitized.confidence(),
                    sanitized.reasonCode());
        }
        PromptPoseTrace.writePlan(sanitized, input);
        if (decision != null) {
            PromptPoseTrace.writeApplicationDecision(decision, input);
        }
        return sanitized;
    }

    private String draftRoute() {
        String route = props.getDraft() == null ? null : props.getDraft().getModel();
        return route == null || route.isBlank() ? "llmrouter.light" : route.trim();
    }
}
