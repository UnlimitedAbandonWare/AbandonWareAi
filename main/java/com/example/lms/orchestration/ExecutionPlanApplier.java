package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Bridges request signals into GuardContext plan overrides consumed by the live
 * retrieval, overdrive, gate, breaker, cancel, and prompt stages.
 */
@Component
public class ExecutionPlanApplier {

    private static final System.Logger LOG = System.getLogger(ExecutionPlanApplier.class.getName());

    private static final long LOW_RECALL_OUT_COUNT = 1L;
    private static final double LOW_AUTHORITY_THRESHOLD = 0.45d;
    private static final double CONTRADICTION_THRESHOLD = 0.60d;

    private final StrategyConflictResolver resolver;
    private final DualGearPolicy dualGearPolicy;

    public ExecutionPlanApplier(StrategyConflictResolver resolver) {
        this.resolver = resolver == null ? new StrategyConflictResolver() : resolver;
        this.dualGearPolicy = new DualGearPolicy(
                GoldenRatioProfile.defaults(),
                new StochasticCircuitBreaker(0.55d, 17L),
                this.resolver);
    }

    public ExecutionPlan apply(GuardContext ctx, OrchestrationSignals orchestrationSignals) {
        StrategyConflictResolver.Signals signals = deriveSignals(ctx, orchestrationSignals);
        ExecutionPlan plan = resolver.resolve(signals);
        DualGearPolicy.Decision gearDecision = dualGearPolicy.decide(signals);
        applyOverrides(ctx, plan, gearDecision);
        traceApplied(plan, gearDecision);
        return plan;
    }

    private StrategyConflictResolver.Signals deriveSignals(
            GuardContext ctx,
            OrchestrationSignals orchestrationSignals) {
        boolean lowRecall = hasTrace("outCount") && TraceStore.getLong("outCount") <= LOW_RECALL_OUT_COUNT;
        lowRecall = lowRecall
                || hasTrace("web.failsoft.starvationFallback.trigger")
                || hasTrace("starvationFallback.trigger");

        boolean lowAuthority = traceDouble("overdrive.authority.avg", 1.0d) < LOW_AUTHORITY_THRESHOLD
                || traceDouble("authority.avg", 1.0d) < LOW_AUTHORITY_THRESHOLD;

        boolean contradiction = traceDouble("overdrive.contradiction.mean", 0.0d) >= CONTRADICTION_THRESHOLD
                || traceDouble("extremez.risk.contradictionScore", 0.0d) >= CONTRADICTION_THRESHOLD
                || traceDouble("rag.contradiction.score", 0.0d) >= CONTRADICTION_THRESHOLD;

        boolean highRiskTail = (orchestrationSignals != null && orchestrationSignals.highRisk())
                || (ctx != null && ctx.isHighRiskQuery())
                || traceBool("highRiskTail")
                || traceBool("routing.trigger.highRiskTail");

        return new StrategyConflictResolver.Signals(lowRecall, lowAuthority, contradiction, highRiskTail);
    }

    private static void applyOverrides(GuardContext ctx, ExecutionPlan plan, DualGearPolicy.Decision gearDecision) {
        if (ctx == null || plan == null) {
            return;
        }

        ctx.putPlanOverride("executionPlan.primaryMode", plan.primaryMode().name());
        ctx.putPlanOverride("executionPlan.triggers", plan.triggers());
        ctx.putPlanOverride("executionPlan.stages", plan.stages());
        ctx.putPlanOverride("routing.executionPlan.primaryMode", plan.primaryMode().name());

        plan.knobs().forEach(ctx::putPlanOverride);
        applyDualGear(ctx, gearDecision);
        if (plan.primaryMode() != ExecutionPlan.PrimaryMode.NORMAL) {
            ctx.putPlanOverride("cancelShield.breadcrumb", true);
        }

        ctx.putPlanOverride("extremeZ.enabled", plan.extremeZEnabled());
        ctx.putPlanOverride("overdrive.enabled", plan.overdriveEnabled());
        ctx.putPlanOverride("overdrive.executionPlan", plan.overdriveEnabled());
        ctx.putPlanOverride("overdrive.narrow.enabled", plan.overdriveEnabled());
        ctx.putPlanOverride("hypernova.enabled", plan.hypernovaEnabled());
        ctx.putPlanOverride("hypernova.executionPlan", plan.hypernovaEnabled());

        if (plan.extremeZEnabled()) {
            ctx.putPlanOverride("extremeZ.skipWhenStrikeMode", false);
            ctx.putPlanOverride("extremeZ.skipWhenWebRateLimited", false);
            ctx.putPlanOverride("extremeZ.skipWhenAuxDown", false);
            putMax(ctx, "extremeZ.maxSubQueries", 8);
            putMax(ctx, "expand.queryBurst.count", 6);
            putMax(ctx, "extremeZ.maxMergedDocs", 24);
            putMax(ctx, "extremeZ.budgetMs", 2200);
        }

        if (plan.overdriveEnabled()) {
            ctx.putPlanOverride("overdrive.aggressive", true);
            ctx.putPlanOverride("overdrive.reason", "executionPlan");
        }

        if (plan.hypernovaEnabled()) {
            ctx.putPlanOverride("gateChain.highRiskTail.strict", true);
        }
    }

    private static void applyDualGear(GuardContext ctx, DualGearPolicy.Decision gearDecision) {
        if (ctx == null || gearDecision == null) {
            return;
        }
        ctx.putPlanOverride("dualGear.mode", gearDecision.gearMode());
        ctx.putPlanOverride("dualGear.baseline.temperature", gearDecision.baselineGear().temperature());
        ctx.putPlanOverride("dualGear.baseline.topP", gearDecision.baselineGear().topP());
        ctx.putPlanOverride("dualGear.gachaVariants", gearDecision.gachaVariants());
        if (!gearDecision.gachaVariants().isEmpty()) {
            ctx.putPlanOverride("queryBurst.gachaVariants", gearDecision.gachaVariants());
            ctx.putPlanOverride("extremeZ.gachaVariants", gearDecision.gachaVariants());
        }
    }

    private static void putMax(GuardContext ctx, String key, int floor) {
        int existing = ctx.planInt(key, Integer.MIN_VALUE);
        if (existing == Integer.MIN_VALUE || existing < floor) {
            ctx.putPlanOverride(key, floor);
        }
    }

    private static void traceApplied(ExecutionPlan plan, DualGearPolicy.Decision gearDecision) {
        try {
            TraceStore.put("routing.executionPlan.applied", true);
            TraceStore.put("routing.executionPlan.applied.primaryMode", plan.primaryMode().name());
            TraceStore.put("routing.executionPlan.applied.triggers", plan.triggers());
            TraceStore.put("routing.executionPlan.applied.stages", plan.stages());
            TraceStore.put("routing.executionPlan.applied.extremeZ", plan.extremeZEnabled());
            TraceStore.put("routing.executionPlan.applied.overdrive", plan.overdriveEnabled());
            TraceStore.put("routing.executionPlan.applied.hypernova", plan.hypernovaEnabled());
            if (gearDecision != null) {
                TraceStore.put("routing.executionPlan.applied.dualGearMode", gearDecision.gearMode());
                TraceStore.put("routing.executionPlan.applied.gachaVariantCount", gearDecision.gachaVariants().size());
            }
        } catch (Throwable ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "ExecutionPlanApplier trace skipped errorType="
                            + SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
    }

    private static boolean hasTrace(String key) {
        return TraceStore.get(key) != null;
    }

    private static boolean traceBool(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private static double traceDouble(String key, double defaultValue) {
        Object value = TraceStore.get(key);
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) {
                return numeric;
            }
            TraceStore.put("routing.executionPlan.suppressed.traceDouble", true);
            TraceStore.put("routing.executionPlan.suppressed.traceDouble.errorType", "invalid_number");
            return defaultValue;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            TraceStore.put("routing.executionPlan.suppressed.traceDouble", true);
            TraceStore.put("routing.executionPlan.suppressed.traceDouble.errorType", "invalid_number");
            return defaultValue;
        } catch (NumberFormatException ignored) {
            TraceStore.put("routing.executionPlan.suppressed.traceDouble", true);
            TraceStore.put("routing.executionPlan.suppressed.traceDouble.errorType", errorType(ignored));
            return defaultValue;
        }
    }

    private static String errorType(Throwable ignored) {
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(ignored == null ? null : ignored.getClass().getSimpleName(), "unknown");
    }
}
