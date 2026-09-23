package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.List;

/**
 * Dual gear policy: stable golden-ratio baseline unless routing signals call
 * for bounded gacha burst exploration.
 */
public class DualGearPolicy {

    private static final System.Logger LOG = System.getLogger(DualGearPolicy.class.getName());

    private final GoldenRatioProfile profile;
    private final StochasticCircuitBreaker breaker;
    private final StrategyConflictResolver resolver;
    private final GachaBurstRunner burstRunner;

    public DualGearPolicy(GoldenRatioProfile profile,
                          StochasticCircuitBreaker breaker,
                          StrategyConflictResolver resolver) {
        this.profile = profile == null ? GoldenRatioProfile.defaults() : profile;
        this.breaker = breaker == null ? new StochasticCircuitBreaker(0.55d, 0L) : breaker;
        this.resolver = resolver == null ? new StrategyConflictResolver() : resolver;
        this.burstRunner = new GachaBurstRunner(this.profile);
    }

    public Decision decide(StrategyConflictResolver.Signals signals) {
        ExecutionPlan plan = resolver.resolve(signals);
        double risk = riskScore(signals);
        boolean burst = plan.extremeZEnabled()
                || plan.hypernovaEnabled()
                || breaker.shouldExplore(risk, plan.primaryMode().name() + ":" + plan.triggers());
        String mode = burst ? "GACHA_BURST" : "GOLDEN_BASELINE";
        List<String> variants = burst ? burstRunner.variants(String.join(" ", plan.triggers()), 3) : List.of();
        Decision decision = new Decision(plan, mode, profile.baseline(), variants);
        trace(decision);
        return decision;
    }

    private static double riskScore(StrategyConflictResolver.Signals signals) {
        if (signals == null) {
            return 0.0d;
        }
        double score = 0.0d;
        if (signals.lowRecall()) {
            score = Math.max(score, 0.60d);
        }
        if (signals.lowAuthority()) {
            score = Math.max(score, 0.68d);
        }
        if (signals.contradiction()) {
            score = Math.max(score, 0.74d);
        }
        if (signals.highRiskTail()) {
            score = Math.max(score, 0.92d);
        }
        return score;
    }

    private static void trace(Decision decision) {
        try {
            TraceStore.put("dualGear.mode", decision.gearMode());
            TraceStore.put("dualGear.executionPlan.primaryMode", decision.plan().primaryMode().name());
            TraceStore.put("dualGear.baseline.temperature", decision.baselineGear().temperature());
            TraceStore.put("dualGear.baseline.topP", decision.baselineGear().topP());
        } catch (Throwable ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DualGearPolicy trace skipped errorType="
                            + SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
    }

    public record Decision(
            ExecutionPlan plan,
            String gearMode,
            GoldenRatioProfile.Gear baselineGear,
            List<String> gachaVariants) {
        public Decision {
            gearMode = gearMode == null || gearMode.isBlank() ? "GOLDEN_BASELINE" : gearMode;
            gachaVariants = gachaVariants == null ? List.of() : List.copyOf(gachaVariants);
        }
    }
}
