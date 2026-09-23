package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 80)
public class PromptPoseRoutingPlanAspect {

    private final PromptPoseProperties props;
    private final PromptPoseOrchestrator orchestrator;

    public PromptPoseRoutingPlanAspect(PromptPoseProperties props, PromptPoseOrchestrator orchestrator) {
        this.props = props == null ? new PromptPoseProperties() : props;
        this.orchestrator = orchestrator;
    }

    @Around("execution(* com.example.lms.service.routing.plan.RoutingPlanService.plan(String, String, int))")
    public Object aroundRoutingPlan(ProceedingJoinPoint pjp) throws Throwable {
        if (!props.isEnabled() || orchestrator == null) {
            return pjp.proceed();
        }
        Object[] args = pjp.getArgs();
        if (args == null || args.length < 3 || !(args[0] instanceof String query) || !(args[2] instanceof Number max)) {
            return pjp.proceed();
        }

        PromptPosePlan plan = orchestrator.plan(query, max.intValue());
        if (plan == null || !plan.enabled() || !plan.hasRoutingHints()) {
            return pjp.proceed();
        }

        Object[] newArgs = args.clone();
        String existingDraft = args[1] instanceof String s ? s : "";
        String mergedDraft = mergeAssistantDraft(existingDraft, plan.assistantDraftLines(), plan.queryBurstSeeds());
        int boundedMax = plan.queryBurstMax() > 0
                ? Math.max(1, Math.min(max.intValue(), capFromPlan(plan, props)))
                : Math.max(1, max.intValue());

        newArgs[1] = mergedDraft;
        newArgs[2] = boundedMax;
        return pjp.proceed(newArgs);
    }

    private static int capFromPlan(PromptPosePlan plan, PromptPoseProperties props) {
        int policyCap = props == null || props.getPolicy() == null ? 18 : props.getPolicy().getMaxQueryburstCount();
        int cap = Math.max(1, Math.min(18, policyCap));
        if (plan != null && plan.queryBurstMax() > 0) {
            cap = Math.min(cap, plan.queryBurstMax());
        }
        return cap;
    }

    private static String mergeAssistantDraft(String existing, List<String> lines, List<String> seeds) {
        boolean hasLines = lines != null && !lines.isEmpty();
        boolean hasSeeds = seeds != null && !seeds.isEmpty();
        if (!hasLines && !hasSeeds) {
            return existing == null ? "" : existing;
        }
        StringBuilder sb = new StringBuilder();
        if (existing != null && !existing.isBlank()) {
            sb.append(existing.trim()).append("\n");
        }
        if (hasLines) {
            sb.append("PromptPose routing hints:");
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    sb.append("\n- ").append(line.trim());
                }
            }
        }
        if (hasSeeds) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("PromptPose search seed hints:");
            for (String seed : seeds) {
                if (seed != null && !seed.isBlank()) {
                    sb.append("\n- \"").append(quote(seed.trim())).append("\"");
                }
            }
        }
        return sb.toString();
    }

    private static String quote(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
