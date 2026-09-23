package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.AnchorTailQueryCompressor;
import com.example.lms.search.TraceStore;
import com.example.lms.search.SearchQueryConstraints;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

/**
 * Pre-compresses very large user prompts before QueryTransformer runs.
 *
 * <p>
 * This is a fail-soft "ABCC" style pre-processing step (Anchor + Tail) to avoid
 * aux-LLM prompt blow-ups and reduce timeout/breaker-open cascades.
 * </p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 45)
public class QueryTransformerAnchorTailAspect {

    private final boolean enabled;
    private final int maxLen;
    private final int triggerLen;
    private final AnchorNarrower anchorNarrower;

    public QueryTransformerAnchorTailAspect(boolean enabled, int maxLen, int triggerLen, AnchorNarrower anchorNarrower) {
        this.enabled = enabled;
        this.maxLen = Math.max(256, maxLen);
        this.triggerLen = Math.max(0, triggerLen);
        this.anchorNarrower = anchorNarrower;
    }

    @Around("execution(* com.example.lms.transform.QueryTransformer.transformEnhanced(..))")
    public Object aroundTransformEnhanced(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) {
            traceSkipped("disabled");
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 1 || !(args[0] instanceof String)) {
            traceSkipped("args_invalid");
            return pjp.proceed();
        }

        String userPrompt = (String) args[0];
        if (userPrompt == null || userPrompt.length() < triggerLen) {
            traceSkipped("prompt_too_short");
            return pjp.proceed();
        }

        AnchorTailQueryCompressor.Result r =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(userPrompt, maxLen, anchorNarrower);

        String condensed = r.condensed();
        if (condensed == null || condensed.isBlank()) {
            traceSkipped("condensed_blank");
            return pjp.proceed();
        }

        if (condensed.length() >= userPrompt.trim().length()) {
            traceSkipped("not_shorter");
            return pjp.proceed();
        }

        if (!SearchQueryConstraints.preserves(userPrompt, condensed)) {
            traceSkipped("search_constraints_lost");
            return pjp.proceed();
        }

        String anchor = r.anchor() == null ? "" : r.anchor();
        TraceStore.put("nova.queryTransformer.anchorTail.used", true);
        TraceStore.put("nova.queryTransformer.anchorTail", Map.of(
                "originalLen", r.originalLen(),
                "maxLen", r.maxLen(),
                "condensedLen", condensed.length(),
                "strategy", r.strategy().name(),
                "anchorHash", anchor.isBlank() ? "" : SafeRedactor.hashValue(anchor),
                "anchorLength", anchor.length()
        ));

        Object[] newArgs = args.clone();
        newArgs[0] = condensed;
        return pjp.proceed(newArgs);
    }

    private static void traceSkipped(String reason) {
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("queryTransformer.anchorTail.skipped", safeReason);
        TraceStore.put("nova.queryTransformer.anchorTail.skipped", safeReason);
    }
}
