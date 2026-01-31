package com.example.lms.service.rag.learn;

import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Reward-shaping hook: reflect "needle" contribution (needle.keptRatio) into the CFVM bandit reward.
 *
 * <p>
 * The needle probe is a 2nd-pass web evidence probe. When it meaningfully contributes to the
 * kept topDocs, we add a small bonus to the bandit reward so that k-allocation arms that benefit
 * from needle are reinforced.
 * </p>
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class NeedleKeptRatioRewardAspect {

    private final CfvmKallocLearningProperties props;

    public NeedleKeptRatioRewardAspect(CfvmKallocLearningProperties props) {
        this.props = props;
    }

    // NOTE: signature is: feedback(String key, String arm, double reward, Map<String,Object> context)
    @Around("execution(* com.example.lms.service.rag.learn.CfvmKAllocationTuner.feedback(..))")
    public Object aroundFeedback(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        if (args == null || args.length < 3) {
            return pjp.proceed();
        }

        // Feature-flag.
        if (props == null || !props.isEnabled() || !props.isNeedleRewardEnabled()) {
            return pjp.proceed();
        }

        double keptRatio = safeDouble(TraceStore.get("needle.keptRatio"), Double.NaN);
        if (!Double.isFinite(keptRatio)) {
            return pjp.proceed();
        }

        double baseReward = safeDouble(args[2], Double.NaN);
        if (!Double.isFinite(baseReward)) {
            return pjp.proceed();
        }

        double bonus = props.getNeedleRewardWeight() * (keptRatio - props.getNeedleRewardBaseline());
        bonus = clamp(bonus, -props.getNeedleRewardCap(), props.getNeedleRewardCap());

        double adjusted = clamp(baseReward + bonus, -1.0, 1.0);

        // Trace for offline analysis.
        TraceStore.put("cfvm.reward.base", baseReward);
        TraceStore.put("cfvm.reward.needleKeptRatio", keptRatio);
        TraceStore.put("cfvm.reward.needleBonus", bonus);
        TraceStore.put("cfvm.reward.adjusted", adjusted);

        // Optional: enrich the context map for bandit store logging.
        if (args.length >= 4 && args[3] instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ctx = (Map<String, Object>) args[3];
            ctx.put("needleKeptRatio", keptRatio);
            ctx.put("needleBonus", bonus);
        }

        args[2] = adjusted;
        return pjp.proceed(args);
    }

    private static double safeDouble(Object v, double def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
