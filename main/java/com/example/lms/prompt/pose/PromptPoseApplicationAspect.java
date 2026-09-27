package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.ensemble.StochasticParamSampler;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.guard.SensitiveTopicDetector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PromptPoseApplicationAspect {

    private final PromptPoseProperties props;
    private final PromptPoseApplicationJudge judge;
    private final StochasticParamSampler stochasticParamSampler;

    @Autowired(required = false)
    private SensitiveTopicDetector sensitiveTopicDetector;

    public PromptPoseApplicationAspect(PromptPoseProperties props, PromptPoseApplicationJudge judge) {
        this(props, judge, new StochasticParamSampler());
    }

    PromptPoseApplicationAspect(
            PromptPoseProperties props,
            PromptPoseApplicationJudge judge,
            StochasticParamSampler stochasticParamSampler) {
        this.props = props == null ? new PromptPoseProperties() : props;
        this.judge = judge;
        this.stochasticParamSampler = stochasticParamSampler;
    }

    @Around("execution(* com.example.lms.service.ChatService.continueChat(..)) || execution(* com.example.lms.service.ChatService.ask(..))")
    public Object aroundChatEntry(ProceedingJoinPoint pjp) throws Throwable {
        if (!props.isEnabled() || props.getApplication() == null || !props.getApplication().isEnabled() || judge == null) {
            clearCreativeEmergence(GuardContextHolder.get());
            return pjp.proceed();
        }

        GuardContext existing = GuardContextHolder.get();
        GuardContext ctx = existing == null ? GuardContext.defaultContext() : existing;
        boolean createdContext = existing == null;
        if (createdContext) {
            GuardContextHolder.set(ctx);
        }

        try {
            applyDecision(ctx, extractMessage(pjp.getArgs()));
            return pjp.proceed();
        } finally {
            if (createdContext) {
                GuardContextHolder.clear();
            }
        }
    }

    private void applyDecision(GuardContext ctx, String rawMessage) {
        clearCreativeEmergence(ctx);
        applySensitiveTopicGuard(ctx, rawMessage);
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(rawMessage, props);
        if (input.blocked()) {
            PromptPoseTrace.writeApplicationDecision(PromptPoseApplicationDecision.disabled(input.skipReason()), input);
            return;
        }
        try {
            PromptPoseApplicationDecision decision = judge.decide(input, requestedMaxQueries(ctx));
            if (decision == null || !decision.enabled()) {
                PromptPoseTrace.writeApplicationDecision(decision, input);
                return;
            }
            applyGuardOverrides(ctx, decision);
            applyCreativeEmergence(ctx, decision, SensitiveTopicDetector.isSensitiveText(rawMessage));
            PromptPoseTrace.writePlan(decision.toPlan(draftRoute()), input);
            PromptPoseTrace.writeApplicationDecision(decision, input);
        } catch (Throwable t) {
            TraceStore.put("promptPose.application.failureClass", "prompt_pose_application_failed");
            PromptPoseTrace.writeApplicationDecision(PromptPoseApplicationDecision.disabled("application_exception"), input);
        }
    }

    private static void applyGuardOverrides(GuardContext ctx, PromptPoseApplicationDecision decision) {
        if (ctx == null || decision == null || !decision.enabled()) {
            return;
        }
        ctx.putPlanOverride("search.zero100.enabled", true);
        putLaneWeights(ctx, decision.laneWeights());
        putRatios(ctx, "CallBudgetRatio", decision.callBudgetRatios());
        putRatios(ctx, "TimeboxRatio", decision.timeboxRatios());
        ctx.putPlanOverride("search.zero100.queryBurstMax", decision.queryBurstMax());
        ctx.putPlanOverride("search.zero100.riskConsensus.enabled", true);
        ctx.putPlanOverride("search.zero100.riskConsensus.minLaneCoverage", decision.minLaneCoverage());
        ctx.putPlanOverride("search.zero100.riskConsensus.riskPenaltyLambda", decision.riskPenaltyLambda());
        ctx.putPlanOverride("expand.selfAsk.count", decision.selfAskCount());
        ctx.putPlanOverride("selfask.enabled", decision.selfAskCount() > 0);
        ctx.putPlanOverride("selfask.planOverride.reason", "promptPose.application");
        ctx.putPlanOverride("llm.answer.temperature", decision.answerTemperature());
        ctx.putPlanOverride("llm.selfAsk.temperature", decision.selfAskTemperature());
        ctx.putPlanOverride("promptPose.application.intentSlot", decision.intentSlot());
        ctx.putPlanOverride("promptPose.application.feedbackTile", decision.feedbackTile());
        ctx.putPlanOverride("promptPose.application.decisionHash12", decision.decisionHash12());
        if ("evidence_strict".equals(decision.intentSlot()) || "debug_patch".equals(decision.intentSlot())) {
            putLowerCap(ctx, "llm.answer.temperature.max", decision.answerTemperature());
            putLowerCap(ctx, "llm.selfAsk.temperature.max", decision.selfAskTemperature());
        }
        if (decision.minCitations() > 0) {
            Integer existing = ctx.getMinCitations();
            ctx.setMinCitations(existing == null ? decision.minCitations() : Math.max(existing, decision.minCitations()));
        }
        if ("overdrive_hint".equals(decision.compressionMode())) {
            ctx.setCompressionMode(true);
            ctx.putPlanOverride("overdrive.enabled", true);
            ctx.putPlanOverride("overdrive.reason", "promptPose.application");
        }
    }

    private void applyCreativeEmergence(
            GuardContext ctx,
            PromptPoseApplicationDecision decision,
            boolean sensitiveInput) {
        if (ctx == null || decision == null || !decision.enabled()
                || !"explore".equals(decision.intentSlot())
                || sensitiveInput
                || ctx.isSensitiveTopic()
                || ctx.planBool("privacy.boundary.enforce", false)) {
            return;
        }
        if (stochasticParamSampler == null) {
            recordCreativeSuppression(ctx, "sampler-unavailable");
            return;
        }
        if (!stochasticParamSampler.applyCreativeProfile(ctx)) {
            recordCreativeSuppression(ctx, "invalid-profile-draw");
            return;
        }
        String label = String.valueOf(ctx.getPlanOverride("creative.emergence.profile"));
        String requestedHash = String.valueOf(
                ctx.getPlanOverride("creative.emergence.requestedOptionsHash"));
        ctx.putPlanOverride("llm.selfAsk.temperature",
                ctx.planDouble("creative.emergence.selfAsk.temperature", 0.55d));
        ctx.putPlanOverride("creative.emergence.active", true);
        TraceStore.put("creative.emergence.active", true);
        TraceStore.put("creative.emergence.profile", label);
        TraceStore.put("creative.emergence.requestedOptionsHash", requestedHash);
    }

    private static void recordCreativeSuppression(GuardContext ctx, String reason) {
        ctx.putPlanOverride("creative.emergence.active", false);
        ctx.putPlanOverride("creative.emergence.suppressedReason", reason);
        TraceStore.put("creative.emergence.active", false);
        TraceStore.put("creative.emergence.suppressedReason", reason);
    }

    private static void clearCreativeEmergence(GuardContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.getPlanOverrides().keySet().removeIf(key -> key != null && key.startsWith("creative.emergence."));
        ctx.putPlanOverride("creative.emergence.active", false);
        TraceStore.context().keySet().removeIf(key -> key != null && key.startsWith("creative.emergence."));
        TraceStore.put("creative.emergence.active", false);
    }

    private void applySensitiveTopicGuard(GuardContext ctx, String rawMessage) {
        if (ctx == null || sensitiveTopicDetector == null) {
            return;
        }
        try {
            sensitiveTopicDetector.applyTo(ctx, ChatRequestDto.builder().message(rawMessage).build());
        } catch (RuntimeException failure) {
            TraceStore.put("promptPose.application.sensitiveGuardFailure", "sensitive-guard-unavailable");
        }
    }

    private static void putLowerCap(GuardContext ctx, String key, double candidate) {
        Double current = ctx.planDouble(key);
        double effective = current == null ? candidate : Math.min(current, candidate);
        ctx.putPlanOverride(key, effective);
    }

    private static void putLaneWeights(GuardContext ctx, Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) {
            return;
        }
        put(ctx, "search.zero100.strictWeight", weights.get("BQ"));
        put(ctx, "search.zero100.relaxedWeight", weights.get("ER"));
        put(ctx, "search.zero100.exploreWeight", weights.get("RC"));
    }

    private static void putRatios(GuardContext ctx, String suffix, Map<String, Double> ratios) {
        if (ratios == null || ratios.isEmpty()) {
            return;
        }
        put(ctx, "search.zero100.strict" + suffix, ratios.get("BQ"));
        put(ctx, "search.zero100.relaxed" + suffix, ratios.get("ER"));
        put(ctx, "search.zero100.explore" + suffix, ratios.get("RC"));
    }

    private static void put(GuardContext ctx, String key, Object value) {
        if (ctx != null && key != null && value != null) {
            ctx.putPlanOverride(key, value);
        }
    }

    private int requestedMaxQueries(GuardContext ctx) {
        int policyMax = props.getPolicy() == null ? 18 : props.getPolicy().getMaxQueryburstCount();
        if (ctx == null) {
            return policyMax;
        }
        return ctx.planInt("search.zero100.queryBurstMax", policyMax);
    }

    private String draftRoute() {
        String route = props.getDraft() == null ? null : props.getDraft().getModel();
        return route == null || route.isBlank() ? "llmrouter.light" : route.trim();
    }

    private static String extractMessage(Object[] args) {
        if (args == null) {
            return "";
        }
        for (Object arg : args) {
            if (arg instanceof ChatRequestDto dto) {
                String msg = dto.getMessage();
                return msg == null ? "" : msg;
            }
            if (arg instanceof String s) {
                return s;
            }
        }
        return "";
    }
}
