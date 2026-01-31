package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.AblationContributionTracker;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * DROP: translate faultmask signals into ablation penalties.
 *
 * <p>
 * FaultMaskingLayerMonitor already bumps irregularity scores. This aspect
 * additionally
 * records attribution penalties so the final trace can explain <i>what degraded
 * the run</i>.
 *
 * <p>
 * Important: this is <b>trace/attribution only</b>; it should be fail-soft and
 * never block
 * request execution.
 * </p>
 */
@Aspect
public class FaultMaskAblationPenaltyAspect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FaultMaskAblationPenaltyAspect.class);

    private final Environment env;

    public FaultMaskAblationPenaltyAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.infra.resilience.FaultMaskingLayerMonitor.record(..))")
    public Object aroundFaultmaskRecord(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();

        String stage = (args != null && args.length > 0 && args[0] != null)
                ? String.valueOf(args[0])
                : "unknown";

        // record(...) has 3-arg and 4-arg overloads.
        String note = null;
        String context = null;
        if (args != null) {
            if (args.length == 3) {
                note = (args[2] != null) ? String.valueOf(args[2]) : null;
            } else if (args.length >= 4) {
                context = (args[2] != null) ? String.valueOf(args[2]) : null;
                note = (args[3] != null) ? String.valueOf(args[3]) : null;
            }
        }

        Object ret = pjp.proceed();

        // Only attach penalties when UAW autolearn is active.
        if (!isUawActive()) {
            return ret;
        }

        try {
            String stg = (stage == null || stage.isBlank()) ? "unknown" : stage.trim();
            String stgLower = stg.toLowerCase(Locale.ROOT);

            // Per-request bucket count (do NOT use FaultMaskingLayerMonitor's global
            // counters).
            long c = TraceStore.inc("uaw.faultmask.count." + stgLower);

            // Bucketed attribution: avoid infinite accumulation.
            if (!(c == 1 || c == 3 || c == 10)) {
                return ret;
            }

            double base = stagePenalty(stgLower);

            // If stage policy computed a delta, keep the larger one.
            double policyDelta = readDouble(TraceStore.get("faultMask.delta"), -1.0);
            if (policyDelta > 0) {
                base = Math.max(base, policyDelta);
            }

            double factor = (c == 1) ? 1.0 : (c == 3 ? 0.60 : 0.35);
            double delta = clamp01(base * factor);

            String onceKey = "faultmask." + stgLower + "#" + c;
            String guard = stg;
            String msg = (note != null && !note.isBlank()) ? note : context;

            AblationContributionTracker.recordPenaltyOnce(
                    onceKey,
                    "faultmask",
                    guard,
                    delta,
                    msg);

            TraceStore.maxLong("uaw.faultmask.maxBucket", c);
            TraceStore.put("uaw.faultmask.lastStage", stg);
            TraceStore.put("uaw.faultmask.lastDelta", delta);

        } catch (Throwable t) {
            // fail-soft
            log.debug("[FaultMaskPenalty] skipped due to {}", t.toString());
        }

        return ret;
    }

    private boolean isUawActive() {
        try {
            Object v = TraceStore.get("uaw.autolearn");
            if (truthy(v))
                return true;
            Object b = TraceStore.get("uaw.ablation.bridge");
            return truthy(b);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private double stagePenalty(String stgLower) {
        // Stage-specific mapping (defaults can be tuned via env).
        // NOTE: keep values conservative; this is attribution only.
        if (stgLower == null)
            stgLower = "unknown";

        if (stgLower.contains("websearch:starvation")) {
            return env.getProperty("uaw.ablation.penalty.websearch.starvation", Double.class, 0.28);
        }
        if (stgLower.contains("websearch:domain-misroute") || stgLower.contains("domain-misroute")) {
            return env.getProperty("uaw.ablation.penalty.websearch.domain-misroute", Double.class, 0.22);
        }
        if (stgLower.startsWith("websearch:")) {
            // YAML merge safety: prefer a nested-map friendly key.
            // uaw.ablation.penalty.websearch.base
            // and fall back to the legacy scalar key:
            // uaw.ablation.penalty.websearch
            Double v = env.getProperty("uaw.ablation.penalty.websearch.base", Double.class);
            if (v == null) {
                v = env.getProperty("uaw.ablation.penalty.websearch", Double.class);
            }
            return v != null ? v : 0.35;
        }
        if (stgLower.startsWith("query-transformer:") || stgLower.startsWith("query_transformer")) {
            return env.getProperty("uaw.ablation.penalty.query-transformer", Double.class, 0.18);
        }
        if (stgLower.startsWith("retrieval:")) {
            return env.getProperty("uaw.ablation.penalty.retrieval", Double.class, 0.20);
        }
        if (stgLower.startsWith("rerank:")) {
            return env.getProperty("uaw.ablation.penalty.rerank", Double.class, 0.15);
        }

        return env.getProperty("uaw.ablation.penalty.default", Double.class, 0.12);
    }

    private static boolean truthy(Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean b)
            return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static double readDouble(Object v, double fallback) {
        if (v == null)
            return fallback;
        if (v instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return 0.0;
        if (v < 0.0)
            return 0.0;
        if (v > 1.0)
            return 1.0;
        return v;
    }
}
