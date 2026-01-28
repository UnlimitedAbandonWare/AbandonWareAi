package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Locale;
import java.util.Map;

/**
 * Caps irregularity increases triggered by optional/aux stages so that
 * optional failures (blank/blocked/starved) don't cascade into COMPRESSION/STRIKE.
 *
 * <p>
 * Implemented as AOP to avoid modifying core classes.
 * </p>
 */
@Aspect
public class OptionalIrregularityCapAspect {

    private static final String SUM_KEY = "irregularity.optional.sum";
    private static final String EVT_KEY = "irregularity.optional.events";

    private final boolean enabled;
    private final double deltaCap;
    private final double ceiling;
    private final int maxEvents;

    public OptionalIrregularityCapAspect(boolean enabled, double deltaCap, double ceiling, int maxEvents) {
        this.enabled = enabled;
        this.deltaCap = Math.max(0.0, deltaCap);
        this.ceiling = Math.max(0.0, ceiling);
        this.maxEvents = Math.max(1, maxEvents);
    }

    @Around("execution(void com.example.lms.infra.resilience.IrregularityProfiler.bump(..))")
    public Object aroundBump(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) return pjp.proceed();

        final Object[] args0 = pjp.getArgs();
        if (args0 == null || args0.length < 3 || !(args0[0] instanceof GuardContext)) {
            return pjp.proceed();
        }
        final GuardContext ctx = (GuardContext) args0[0];

        final double delta;
        if (args0[1] instanceof Number n) {
            delta = n.doubleValue();
        } else {
            return pjp.proceed();
        }

        final String reason = (args0[2] == null) ? null : String.valueOf(args0[2]);

        if (ctx == null) return pjp.proceed();

        String r = reason == null ? "" : reason;
        if (!isOptionalReason(r)) return pjp.proceed();

        // High-risk queries: do not dampen protective transitions.
        try {
            if (ctx.isHighRiskQuery()) {
                TraceStore.put("irregularity.optional.cap.skippedHighRisk", true);
                TraceStore.put("irregularity.optional.cap.skippedHighRisk.reason", r);
                return pjp.proceed();
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        if (Double.isNaN(delta) || Double.isInfinite(delta) || delta <= 0) {
            return pjp.proceed();
        }

        // Per-call delta cap
        double capped = Math.min(delta, deltaCap);
        if (capped <= 0) {
            return null;
        }

        // Optional ceiling per request (tracked via TraceStore context).
        double sum = getNumber(TraceStore.get(SUM_KEY));
        int events = (int) getNumber(TraceStore.get(EVT_KEY));

        if (events >= maxEvents) {
            TraceStore.put("irregularity.optional.cap.maxEvents", maxEvents);
            TraceStore.put("irregularity.optional.cap.skippedReason", r);
            return null;
        }

        double remaining = ceiling - sum;
        if (remaining <= 0) {
            TraceStore.put("irregularity.optional.cap.hit", true);
            TraceStore.put("irregularity.optional.cap.skippedReason", r);
            return null;
        }

        double effective = Math.min(capped, remaining);

        // Proceed with adjusted delta.
        // SoT snapshot: clone args once, then proceed(args) exactly once.
        final Object[] args = args0.clone();
        args[1] = effective;
        Object out = pjp.proceed(args);

        // Update counters (atomic-ish via ConcurrentHashMap compute).
        try {
            TraceStore.context().compute(SUM_KEY, (k, v) -> getNumber(v) + effective);
            TraceStore.context().compute(EVT_KEY, (k, v) -> (int) getNumber(v) + 1);
        } catch (Exception ignore) {
            // fail-soft
        }

        TraceStore.put("irregularity.optional.cap.last", Map.of(
                "reason", r,
                "delta", delta,
                "effective", effective,
                "sumBefore", sum,
                "sumAfter", sum + effective,
                "ceiling", ceiling
        ));

        return out;
    }

    private static boolean isOptionalReason(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String r = reason.toLowerCase(Locale.ROOT);
        // Explicit optional/aux reasons used across the pipeline.
        return r.startsWith("keyword_")
                || r.startsWith("disambiguation_")
                || r.startsWith("query_transformer_")
                || r.startsWith("query-transformer_");
    }

    private static double getNumber(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); }
            catch (Exception ignore) { return 0.0; }
        }
        return 0.0;
    }
}
