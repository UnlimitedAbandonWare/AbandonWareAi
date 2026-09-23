
package com.example.lms.risk;

import com.example.lms.search.TraceStore;
import com.nova.protocol.alloc.RiskKAllocator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;



@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
@Component
@ConditionalOnProperty(name="retrieval.topK.shrinkOnRisk", havingValue = "true", matchIfMissing = false)
public class TopKShrinkerAspect {

    @Autowired RiskScorer scorer;
    @Autowired(required = false) RiskKAllocator riskKAllocator;
    @Value("${retrieval.topK.base:24}") int baseK;
    @Value("${retrieval.topK.min:6}") int minK;
    @Value("${retrieval.topK.fallback-risk:0.0}") double fallbackRisk;

    @Around("execution(* *..AnalyzeWebSearchRetriever.*(..)) || execution(* *..WebSearchRetriever.*(..))")
    public Object adjust(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        for (int i=0;i<args.length;i++) {
            if (args[i] instanceof Integer) {
                int k = (Integer)args[i];
                int cappedK = Math.min(k, baseK);
                RiskSignal risk = currentRisk(args);
                TopKDecision decision = adjustedTopK(cappedK, risk);
                args[i] = decision.adjustedK();
                traceTopK(k, cappedK, decision.adjustedK(), risk, decision.source());
                break;
            }
        }
        return pjp.proceed(args);
    }

    private RiskSignal currentRisk(Object[] args) {
        Double traceRisk = normalizeRisk(TraceStore.get("blackbox.risk.riskScore"));
        if (traceRisk != null) {
            return new RiskSignal(new RiskDecisionIndex(traceRisk), "trace");
        }
        String query = firstQuery(args);
        if (query != null && scorer != null) {
            return new RiskSignal(scorer.score(query, null), "query");
        }
        Double normalizedFallback = normalizeRiskValue(fallbackRisk);
        double safeFallback = normalizedFallback == null ? 0.0d : normalizedFallback;
        return new RiskSignal(new RiskDecisionIndex(safeFallback), "fallback-config");
    }

    private TopKDecision adjustedTopK(int cappedK, RiskSignal risk) {
        int scorerK = scorer.shrinkTopK(cappedK, risk.index(), minK);
        if (riskKAllocator == null) {
            return new TopKDecision(scorerK, risk.source());
        }
        try {
            int[] allocated = riskKAllocator.alloc(
                    new double[]{1.0d, 1.0d - risk.index().rdi},
                    new double[]{risk.index().rdi, 0.0d},
                    cappedK,
                    1.0d,
                    new int[]{minK, 0});
            if (allocated == null || allocated.length == 0) {
                TraceStore.put("retrieval.topK.allocator.failed", "empty-allocation");
                return new TopKDecision(scorerK, risk.source());
            }
            int allocatorK = clampTopK(allocated[0], cappedK);
            TraceStore.put("retrieval.topK.allocator.primaryK", allocatorK);
            return new TopKDecision(allocatorK, "risk-k-allocator:" + risk.source());
        } catch (RuntimeException ex) {
            TraceStore.put("retrieval.topK.allocator.failed", ex.getClass().getSimpleName());
            return new TopKDecision(scorerK, risk.source());
        }
    }

    private static String firstQuery(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof CharSequence cs && cs.length() > 0) {
                return cs.toString();
            }
        }
        return null;
    }

    private static Double normalizeRisk(Object raw) {
        if (raw instanceof Number number) {
            double parsed = number.doubleValue();
            if (!Double.isFinite(parsed)) {
                traceInvalidRiskNumber();
                return null;
            }
            return normalizeRiskValue(parsed);
        }
        if (raw instanceof CharSequence text) {
            try {
                double parsed = Double.parseDouble(text.toString().trim());
                if (!Double.isFinite(parsed)) {
                    traceInvalidRiskNumber();
                    return null;
                }
                return normalizeRiskValue(parsed);
            } catch (NumberFormatException ignored) {
                traceInvalidRiskNumber();
                return null;
            }
        }
        return null;
    }

    private static void traceInvalidRiskNumber() {
        TraceStore.put("risk.topK.suppressed.stage", "normalizeRisk");
        TraceStore.put("risk.topK.suppressed.errorType", "invalid_number");
        TraceStore.put("risk.topK.suppressed.normalizeRisk", true);
        TraceStore.put("risk.topK.suppressed.normalizeRisk.errorType", "invalid_number");
    }

    private static Double normalizeRiskValue(double value) {
        if (!Double.isFinite(value) || value < 0.0d) {
            return null;
        }
        if (value > 1.0d && value <= 100.0d) {
            value = value / 100.0d;
        }
        if (value > 1.0d) {
            return null;
        }
        return value;
    }

    private static void traceTopK(int originalK, int cappedK, int adjustedK, RiskSignal risk, String source) {
        TraceStore.put("retrieval.topK.original", originalK);
        TraceStore.put("retrieval.topK.capped", cappedK);
        TraceStore.put("retrieval.topK.adjusted", adjustedK);
        TraceStore.put("retrieval.topK.rdi", risk.index().rdi);
        TraceStore.put("retrieval.topK.rdiSource", source);
    }

    private int clampTopK(int value, int cappedK) {
        return Math.max(minK, Math.min(cappedK, value));
    }

    private record RiskSignal(RiskDecisionIndex index, String source) {
    }

    private record TopKDecision(int adjustedK, String source) {
    }
}
