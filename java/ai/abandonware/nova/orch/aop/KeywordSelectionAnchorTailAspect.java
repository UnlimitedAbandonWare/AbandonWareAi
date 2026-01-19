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

    @Around("execution(* com.example.lms.search.KeywordSelectionService.select(..)) && args(conversation, domainProfile, maxMust)")
    public Object aroundSelect(ProceedingJoinPoint pjp, String conversation, String domainProfile, int maxMust) throws Throwable {
        if (!enabled) return pjp.proceed();
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

        return pjp.proceed(new Object[]{condensed, domainProfile, maxMust});
    }
}
