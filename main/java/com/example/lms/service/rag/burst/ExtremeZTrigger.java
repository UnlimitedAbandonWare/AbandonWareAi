package com.example.lms.service.rag.burst;

import com.example.lms.orchestration.ExecutionPlan;
import com.example.lms.orchestration.StrategyConflictResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtremeZTrigger {
    private static final Logger log = LoggerFactory.getLogger(ExtremeZTrigger.class);
    private final com.example.lms.service.rag.energy.ContradictionScorer scorer;
    private final com.example.lms.service.rag.burst.ExtremeZProperties props;
    private final StrategyConflictResolver resolver;

    public ExtremeZTrigger(
        com.example.lms.service.rag.energy.ContradictionScorer scorer,
        com.example.lms.service.rag.burst.ExtremeZProperties props
    ) {
        this(scorer, props, new StrategyConflictResolver());
    }

    public ExtremeZTrigger(
        com.example.lms.service.rag.energy.ContradictionScorer scorer,
        com.example.lms.service.rag.burst.ExtremeZProperties props,
        StrategyConflictResolver resolver
    ) {
        this.scorer = scorer;
        this.props = props == null ? new ExtremeZProperties() : props;
        this.resolver = resolver == null ? new StrategyConflictResolver() : resolver;
    }

    public Decision evaluate(String query,
                             int baseDocCount,
                             double authorityScore,
                             double contradictionScore,
                             boolean highRiskTail) {
        boolean enabled = props.isEnabledFlag();
        boolean lowRecall = Math.max(0, baseDocCount) < props.getMinBaseDocs();
        boolean lowAuthority = clamp01(authorityScore) < props.getMinAuthority();
        boolean contradiction = clamp01(contradictionScore) >= props.getContradictionThreshold()
                && contradictionScore > 0.0d;

        StrategyConflictResolver.Signals signals = enabled
                ? new StrategyConflictResolver.Signals(lowRecall, lowAuthority, contradiction, highRiskTail)
                : new StrategyConflictResolver.Signals(false, false, false, false);
        ExecutionPlan plan = resolver.resolve(signals);
        boolean activate = plan.primaryMode() != ExecutionPlan.PrimaryMode.NORMAL;
        String reason = reason(plan, enabled);
        Decision decision = new Decision(
                activate,
                lowRecall && enabled,
                lowAuthority && enabled,
                contradiction && enabled,
                highRiskTail && enabled,
                reason,
                plan);
        trace(query, baseDocCount, authorityScore, contradictionScore, enabled, decision);
        return decision;
    }

    public Decision evaluate(String query,
                             int baseDocCount,
                             double authorityScore,
                             boolean highRiskTail,
                             java.util.List<String> candidateTexts) {
        double contradictionScore = 0.0d;
        int pairs = 0;
        try {
            if (scorer != null && candidateTexts != null && candidateTexts.size() >= 2) {
                double sum = 0.0d;
                outer:
                for (int i = 0; i < candidateTexts.size(); i++) {
                    String left = candidateTexts.get(i);
                    if (left == null || left.isBlank()) {
                        continue;
                    }
                    for (int j = i + 1; j < candidateTexts.size(); j++) {
                        String right = candidateTexts.get(j);
                        if (right == null || right.isBlank()) {
                            continue;
                        }
                        sum += scorer.score(left, right);
                        pairs++;
                        if (pairs >= 3) {
                            break outer;
                        }
                    }
                }
                contradictionScore = pairs > 0 ? clamp01(sum / pairs) : 0.0d;
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            TraceStore.put("extremez.trigger.contradictionScorerError", "scorer_exception");
            TraceStore.put("extremez.trigger.contradictionScorerErrorHash",
                    SafeRedactor.hashValue(messageOf(e)));
            TraceStore.put("extremez.trigger.contradictionScorerErrorLength", messageLength(e));
        }
        TraceStore.put("extremez.trigger.contradictionPairs", pairs);
        return evaluate(query, baseDocCount, authorityScore, contradictionScore, highRiskTail);
    }

    public com.example.lms.service.rag.energy.ContradictionScorer scorer() {
        return scorer;
    }

    private static String reason(ExecutionPlan plan, boolean enabled) {
        if (!enabled) {
            return "disabled";
        }
        if (plan.triggers().isEmpty()) {
            return "no_trigger";
        }
        return String.join("+", plan.triggers());
    }

    private static void trace(String query,
                              int baseDocCount,
                              double authorityScore,
                              double contradictionScore,
                              boolean enabled,
                              Decision decision) {
        try {
            TraceStore.put("extremez.trigger.queryHash12", SafeRedactor.hash12(query));
            TraceStore.put("extremez.trigger.queryLength", query == null ? 0 : query.length());
            TraceStore.put("extremez.trigger.baseDocCount", Math.max(0, baseDocCount));
            TraceStore.put("extremez.trigger.enabled", enabled);
            TraceStore.put("extremez.trigger.authorityScore", clamp01(authorityScore));
            TraceStore.put("extremez.trigger.contradictionScore", clamp01(contradictionScore));
            TraceStore.put("extremez.trigger.lowRecall", decision.lowRecall());
            TraceStore.put("extremez.trigger.lowAuthority", decision.lowAuthority());
            TraceStore.put("extremez.trigger.contradiction", decision.contradiction());
            TraceStore.put("extremez.trigger.highRiskTail", decision.highRiskTail());
            TraceStore.put("extremez.trigger.activate", decision.activate());
            TraceStore.put("extremez.trigger.reason",
                    SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown"));
            TraceStore.put("extremez.trigger.plan.primaryMode", decision.plan().primaryMode().name());
            boolean extremeZActive = decision.plan() != null && decision.plan().extremeZEnabled();
            String safeReason = SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown");
            TraceStore.put("extremeZ.trigger.contradictionScore", clamp01(contradictionScore));
            TraceStore.put("extremeZ.trigger.activated", decision.activate());
            TraceStore.put("extremeZ.trigger.reason", safeReason);
            TraceStore.put("extremeZ.activated", extremeZActive);
            TraceStore.put("extremeZ.triggerReasons", safeReason);
            TraceStore.put("extremeZ.bypassReason", extremeZActive ? "" : safeReason);
        } catch (Throwable ignore) {
            // best-effort diagnostics only
            log.debug("[ExtremeZTrigger] fail-soft stage={}", "trace");
        }
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    public record Decision(
            boolean activate,
            boolean lowRecall,
            boolean lowAuthority,
            boolean contradiction,
            boolean highRiskTail,
            String reason,
            ExecutionPlan plan) {
    }
}
