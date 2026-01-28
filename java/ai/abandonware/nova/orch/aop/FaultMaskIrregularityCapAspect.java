package ai.abandonware.nova.orch.aop;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Locale;

/**
 * Fail-soft guard against "optional stage fault-masking" causing cascading compression.
 *
 * <p>
 * We want to keep FaultMaskingLayerMonitor telemetry, but avoid repeatedly bumping
 * GuardContext irregularity for the same optional stage within a single request.
 * This aspect caps the irregularity delta (first bump) and reverts subsequent
 * bumps per stage-group.
 * </p>
 */
@Aspect
public class FaultMaskIrregularityCapAspect {

    private final boolean enabled;
    private final double maxDelta;

    public FaultMaskIrregularityCapAspect(boolean enabled, double maxDelta) {
        this.enabled = enabled;
        this.maxDelta = Math.max(0.0, maxDelta);
    }

    @Around("execution(* com.example.lms.infra.resilience.FaultMaskingLayerMonitor.record(..))")
    public Object aroundRecord(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) {
            return pjp.proceed();
        }

        String stageKey = null;
        try {
            Object[] args = pjp.getArgs();
            if (args != null && args.length > 0 && args[0] != null) {
                stageKey = String.valueOf(args[0]);
            }
        } catch (Throwable ignore) {
            stageKey = null;
        }

        String group = stageGroup(stageKey);
        if (group == null) {
            return pjp.proceed();
        }

        GuardContext ctx = null;
        double before = 0.0;
        try {
            ctx = GuardContextHolder.get();
            if (ctx != null) {
                before = ctx.getIrregularityScore();
            }
        } catch (Throwable ignore) {
            ctx = null;
        }

        Object out = pjp.proceed();

        if (ctx == null) {
            return out;
        }

        try {
            double after = ctx.getIrregularityScore();
            double delta = after - before;
            if (delta <= 0.000001) {
                return out;
            }

            String key = "faultmask.irregularityCap.once." + group;
            Object prev = TraceStore.get(key);
            if (prev != null) {
                // Already applied once in this request -> revert further bumps.
                ctx.setIrregularityScore(before);
                TraceStore.inc("faultmask.irregularityCap.reverted.count");
                TraceStore.put("faultmask.irregularityCap.reverted.last.group", group);
                TraceStore.put("faultmask.irregularityCap.reverted.last.delta", delta);
                return out;
            }

            // First bump: cap delta to maxDelta (best-effort).
            TraceStore.put(key, true);
            if (maxDelta > 0.0 && delta > maxDelta) {
                ctx.setIrregularityScore(before + maxDelta);
                TraceStore.inc("faultmask.irregularityCap.clamped.count");
                TraceStore.put("faultmask.irregularityCap.clamped.last.group", group);
                TraceStore.put("faultmask.irregularityCap.clamped.last.delta", delta);
                TraceStore.put("faultmask.irregularityCap.clamped.last.maxDelta", maxDelta);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        return out;
    }

    private static String stageGroup(String stageKey) {
        if (stageKey == null) {
            return null;
        }
        String s = stageKey.trim();
        if (s.isEmpty()) {
            return null;
        }
        String t = s.toLowerCase(Locale.ROOT);

        // Optional aux stages most likely to be noisy.
        if (t.startsWith("query-transformer")) {
            return "query_transformer";
        }
        if (t.startsWith("plan:queryplanner") || t.startsWith("plan:query_planner")) {
            return "query_planner";
        }
        if (t.startsWith("disambiguation")) {
            return "disambiguation";
        }
        return null;
    }
}
