package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.AblationContributionTracker;
import com.example.lms.trace.SafeRedactor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 240)
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
            String traceStage = safeStageLabel(stg);
            String stageKey = safeStageKey(stg);
            String failureClass = failureClassFor(stg, context, note, traceStage);
            String failureKey = safeStageKey(failureClass);

            // Per-request bucket count (do NOT use FaultMaskingLayerMonitor's global
            // counters).
            long c = TraceStore.inc("uaw.faultmask.count." + stageKey + "." + failureKey);

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

            String onceKey = "faultmask." + stageKey + "." + failureKey + "#" + c;
            String guard = failureClass;
            String msg = (note != null && !note.isBlank()) ? note : context;

            AblationContributionTracker.recordPenaltyOnce(
                    onceKey,
                    "faultmask",
                    guard,
                    delta,
                    msg);

            TraceStore.maxLong("uaw.faultmask.maxBucket", c);
            TraceStore.put("uaw.faultmask.lastStage", traceStage);
            TraceStore.put("uaw.faultmask.lastFailureClass", failureClass);
            TraceStore.inc("uaw.faultmask.failureClass." + failureKey);
            TraceStore.put("uaw.faultmask.lastDelta", delta);

        } catch (Throwable t) {
            // fail-soft
            log.debug("[FaultMaskPenalty] skipped due to errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t));
        }

        return ret;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }

    private boolean isUawActive() {
        try {
            Object v = TraceStore.get("uaw.autolearn");
            if (truthy(v))
                return true;
            Object b = TraceStore.get("uaw.ablation.bridge");
            return truthy(b);
        } catch (Throwable ignore) {
            traceSuppressed("uaw.active", ignore);
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
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (!Double.isFinite(numeric)) {
                TraceStore.put("faultMask.delta.parse.errorType", "invalid_number");
                traceSuppressed("faultMask.delta.parse", new NumberFormatException("non-finite number"));
                return fallback;
            }
            return numeric;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(v).trim());
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            return parsed;
        } catch (NumberFormatException ignore) {
            TraceStore.put("faultMask.delta.parse.errorType", "invalid_number");
            traceSuppressed("faultMask.delta.parse", ignore);
            return fallback;
        }
    }

    private static void traceSuppressed(String stage, Throwable t) {
        log.debug("[FaultMaskPenalty] suppressed stage={} errorHash={} errorLength={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                SafeRedactor.hashValue(messageOf(t)), messageLength(t));
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

    private static String safeStageLabel(String stage) {
        String raw = (stage == null || stage.isBlank()) ? "unknown" : stage.trim();
        String label = SafeRedactor.traceLabelOrFallback(raw, "unknown");
        return (label == null || label.isBlank()) ? "unknown" : label;
    }

    private static String safeStageKey(String stage) {
        String label = safeStageLabel(stage);
        String safe = label.replace("hash:", "hash_").replaceAll("[^A-Za-z0-9_.:-]+", "_");
        if (safe.isBlank()) {
            return "unknown";
        }
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    private static String failureClassFor(String stage, String context, String note, String fallback) {
        String signal = normalizedSignal(stage) + " " + normalizedSignal(context) + " " + normalizedSignal(note);
        if (containsAny(signal, "zero-result-after-filter", "zero_result_after_filter", "after-filter",
                "after filter", "after_filter")) {
            return "zero-result-after-filter";
        }
        if (containsAny(signal, "provider-disabled", "provider_disabled")
                || (signal.contains("provider") && signal.contains("disabled"))) {
            return "provider-disabled";
        }
        if (containsAny(signal, "rate-limit", "rate_limit", "rate limit", "too many requests", "http 429", " 429 ")) {
            return "rate-limit";
        }
        if (containsAny(signal, "cancelled", "canceled", "cancelled by", "canceled by", "cancel(true)",
                "interrupt")) {
            return "cancelled";
        }
        if (containsAny(signal, "timeout", "timed out", "time out", "budget_exhausted")) {
            return "timeout";
        }
        return (fallback == null || fallback.isBlank()) ? "unknown" : fallback;
    }

    private static String normalizedSignal(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String signal, String... needles) {
        if (signal == null || signal.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && signal.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
