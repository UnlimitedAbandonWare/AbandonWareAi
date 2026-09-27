package com.example.lms.prompt.pose;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.learn.CfvmBanditStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Locale;

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 80)
public class PromptPoseRewardAspect {

    private static final Logger LOG = LoggerFactory.getLogger(PromptPoseRewardAspect.class);

    private final CfvmBanditStore store;

    public PromptPoseRewardAspect(CfvmBanditStore store) {
        this.store = store;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object recordReward(ProceedingJoinPoint pjp) throws Throwable {
        Throwable failure = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            updateReward(failure);
        }
    }

    private void updateReward(Throwable failure) {
        GuardContext context = GuardContextHolder.get();
        if (context != null && context.isInteractionMemoryWriteSuppressed()) {
            TraceStore.put("promptPose.reward.skipped", "interaction_policy");
            return;
        }
        if (store == null) {
            return;
        }
        String arm = PromptPoseTrace.arm();
        if (arm == null || arm.isBlank() || PromptPoseArm.NO_DRAFT.name().equals(arm)) {
            return;
        }
        double reward = 0.45d * value("finalSigmoid", "gate.finalSigmoid.score", "finalSigmoidGate.score")
                + 0.20d * boolValue("citation.pass", "citationPass", "citation.gate.pass")
                + 0.15d * value("evidenceCoverage", "rag.evidenceCoverage")
                + 0.10d * latencyScore()
                - 0.40d * failurePenalty(failure);
        reward = clamp01(reward);
        String tile = stringValue(PromptPoseTrace.APPLICATION_FEEDBACK_TILE);
        if (tile == null || tile.isBlank()) {
            tile = stringValue("cfvm.kalloc.key");
        }
        if (tile == null || tile.isBlank()) {
            tile = "default";
        }
        String safeTile = SafeRedactor.traceLabelOrFallback(tile, "default");
        try {
            TraceStore.put("promptPose.reward.arm", arm);
            TraceStore.put("promptPose.reward.tileKey", safeTile);
            TraceStore.put("promptPose.reward.value", round4(reward));
        } catch (Throwable t) {
            traceSkipped("reward_trace", t);
        }
        store.update("promptPose:" + safeTile, arm, reward);
    }

    private static double failurePenalty(Throwable failure) {
        if (failure != null) {
            return 1.0d;
        }
        String failureClass = stringValue(PromptPoseTrace.FAILURE_CLASS);
        if (failureClass == null || failureClass.isBlank() || "none".equalsIgnoreCase(failureClass)) {
            return 0.0d;
        }
        return 1.0d;
    }

    private static double latencyScore() {
        double tookMs = value("rag.tookMs", "chat.tookMs", "workflow.tookMs");
        if (tookMs <= 0.0d) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, 1.0d - (tookMs / 30_000.0d)));
    }

    private static double boolValue(String... keys) {
        for (String key : keys) {
            Object v = TraceStore.get(key);
            if (v instanceof Boolean b) {
                return b ? 1.0d : 0.0d;
            }
            if (v instanceof String s) {
                String n = s.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(n) || "pass".equals(n) || "passed".equals(n) || "1".equals(n)) {
                    return 1.0d;
                }
                if ("false".equals(n) || "fail".equals(n) || "failed".equals(n) || "0".equals(n)) {
                    return 0.0d;
                }
            }
        }
        return 0.0d;
    }

    private static double value(String... keys) {
        for (String key : keys) {
            Object v = TraceStore.get(key);
            if (v instanceof Number n) {
                return clamp01(n.doubleValue());
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    return clamp01(Double.parseDouble(s.trim()));
                } catch (NumberFormatException e) {
                    traceSkipped("reward_numeric_value", e);
                }
            }
        }
        return 0.0d;
    }

    private static String stringValue(String key) {
        String value = TraceStore.getString(key);
        return value == null ? "" : value.trim();
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static void traceSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        LOG.debug("[AWX][prompt][pose] reward trace skipped stage={} errorType={}",
                safeStage,
                safeErrorType);
    }
}
