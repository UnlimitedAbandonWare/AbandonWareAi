package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.AnchorTailQueryCompressor;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Map;

/**
 * Pre-compresses very large conversations before KeywordSelection runs.
 *
 * <p>
 * KeywordSelection is an optional/aux stage that may call LLMs. When users paste
 * long documents or logs, naive head-clamps can collapse the semantic "anchor"
 * and increase timeout/OPEN cascades.
 * </p>
 */
@Aspect
public class KeywordSelectionAnchorTailAspect {

    private final boolean enabled;
    private final int maxLen;
    private final int triggerLen;
    private final AnchorNarrower anchorNarrower;

    public KeywordSelectionAnchorTailAspect(boolean enabled, int maxLen, int triggerLen, AnchorNarrower anchorNarrower) {
        this.enabled = enabled;
        this.maxLen = Math.max(256, maxLen);
        this.triggerLen = Math.max(0, triggerLen);
        this.anchorNarrower = anchorNarrower;
    }

    @Around("execution(* com.example.lms.search.KeywordSelectionService.select(..))")
    public Object aroundSelect(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) return pjp.proceed();

        final Object[] args0 = pjp.getArgs();
        if (args0 == null || args0.length < 3 || !(args0[0] instanceof String)) {
            return pjp.proceed();
        }
        final String conversation = (String) args0[0];
        final String domainProfile = (args0[1] instanceof String) ? (String) args0[1] : null;
        final int maxMust;
        if (args0[2] instanceof Number n) {
            maxMust = n.intValue();
        } else {
            return pjp.proceed();
        }

        if (conversation == null) return pjp.proceed();
        if (conversation.length() < triggerLen) return pjp.proceed();

        AnchorTailQueryCompressor.Result r =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(conversation, maxLen, anchorNarrower);

        String condensed = r.condensed();
        if (condensed == null || condensed.isBlank()) {
            return pjp.proceed();
        }

        if (condensed.length() >= conversation.trim().length()) {
            return pjp.proceed();
        }

        TraceStore.put("nova.keywordSelection.anchorTail.used", true);
        TraceStore.put("nova.keywordSelection.anchorTail", Map.of(
                "originalLen", r.originalLen(),
                "maxLen", r.maxLen(),
                "condensedLen", condensed.length(),
                "strategy", r.strategy().name(),
                "anchor", r.anchor()
        ));

        // SoT snapshot: clone args once, then proceed(args) exactly once.
        final Object[] args = args0.clone();
        args[0] = condensed;
        // args[1], args[2] preserved
        return pjp.proceed(args);
    }
}
