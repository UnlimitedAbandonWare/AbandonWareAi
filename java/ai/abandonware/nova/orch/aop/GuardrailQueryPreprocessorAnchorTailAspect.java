package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.AnchorTailQueryCompressor;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.pre.CognitiveState;
import com.example.lms.service.rag.pre.GuardrailQueryPreprocessor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Map;
import java.util.Optional;

/**
 * Prevents "head-clamp query collapse" (e.g., query becomes "그런데...") by pre-condensing
 * large inputs into an "anchor + tail/request-line" form before the guardrail preprocessor
 * applies its MAX_LEN clamp.
 */
@Aspect
public class GuardrailQueryPreprocessorAnchorTailAspect {

    private final boolean enabled;
    private final int maxLen;
    private final int headroom;
    private final int triggerLen;
    private final AnchorNarrower anchorNarrower;

    public GuardrailQueryPreprocessorAnchorTailAspect(boolean enabled, int maxLen, int headroom, int triggerLen, AnchorNarrower anchorNarrower) {
        this.enabled = enabled;
        this.maxLen = Math.max(32, maxLen);
        this.headroom = Math.max(0, headroom);
        this.triggerLen = Math.max(0, triggerLen);
        this.anchorNarrower = anchorNarrower;
    }

    @Around("execution(String com.example.lms.service.rag.pre.GuardrailQueryPreprocessor.enrich(..))")
    public Object aroundEnrich(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) return pjp.proceed();

        final Object[] args0 = pjp.getArgs();
        if (args0 == null || args0.length < 1 || !(args0[0] instanceof String)) {
            return pjp.proceed();
        }
        final String original = (String) args0[0];
        if (original == null) return pjp.proceed();
        if (original.length() < triggerLen) return pjp.proceed();

        GuardrailQueryPreprocessor target = null;
        try {
            Object t = pjp.getTarget();
            if (t instanceof GuardrailQueryPreprocessor g) {
                target = g;
            }
        } catch (Exception ignore) {
        }

        // VECTOR_SEARCH: don't alter user's text (embedding query should be verbatim).
        if (target != null) {
            try {
                Optional<CognitiveState> st = target.extractCognitiveState(original);
                if (st.isPresent() && st.get().executionMode() == CognitiveState.ExecutionMode.VECTOR_SEARCH) {
                    return pjp.proceed();
                }
            } catch (Exception ignore) {
            }
        }

        int inputMax = Math.max(16, maxLen - headroom);
        AnchorTailQueryCompressor.Result r =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(original, inputMax, anchorNarrower);

        String condensed = r.condensed();
        if (condensed == null || condensed.isBlank()) {
            return pjp.proceed();
        }

        // Avoid extra allocations when not needed.
        if (condensed.length() >= original.trim().length()) {
            return pjp.proceed();
        }

        TraceStore.put("nova.guardrail.anchorTail.used", true);
        TraceStore.put("nova.guardrail.anchorTail", Map.of(
                "originalLen", r.originalLen(),
                "inputMaxLen", r.maxLen(),
                "condensedLen", condensed.length(),
                "strategy", r.strategy().name(),
                "anchor", r.anchor()
        ));

        // SoT snapshot: clone args once, then proceed(args) exactly once.
        final Object[] args = args0.clone();
        args[0] = condensed;
        return pjp.proceed(args);
    }
}
