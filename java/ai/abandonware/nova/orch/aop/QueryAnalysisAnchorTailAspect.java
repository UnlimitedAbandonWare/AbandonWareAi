package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.AnchorTailQueryCompressor;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Map;

/**
 * Pre-compresses very large pasted contexts (docs/logs) before the expensive
 * query-analysis stage runs. This prevents token/GC blow-ups and reduces timeout
 * risk by feeding an "anchor + tail" condensed string.
 */
@Aspect
public class QueryAnalysisAnchorTailAspect {

    private final boolean enabled;
    private final int maxLen;
    private final int triggerLen;
    private final AnchorNarrower anchorNarrower;

    public QueryAnalysisAnchorTailAspect(boolean enabled, int maxLen, int triggerLen, AnchorNarrower anchorNarrower) {
        this.enabled = enabled;
        this.maxLen = Math.max(256, maxLen);
        this.triggerLen = Math.max(0, triggerLen);
        this.anchorNarrower = anchorNarrower;
    }

    @Around("execution(* com.example.lms.service.rag.query.QueryAnalysisService.analyze(..)) && args(userQuery)")
    public Object aroundAnalyze(ProceedingJoinPoint pjp, String userQuery) throws Throwable {
        if (!enabled) return pjp.proceed();
        if (userQuery == null) return pjp.proceed();
        if (userQuery.length() < triggerLen) return pjp.proceed();

        AnchorTailQueryCompressor.Result r =
                AnchorTailQueryCompressor.condenseForQueryAnalysis(userQuery, maxLen, anchorNarrower);

        String condensed = r.condensed();
        if (condensed == null || condensed.isBlank()) {
            return pjp.proceed();
        }

        if (condensed.length() >= userQuery.trim().length()) {
            return pjp.proceed();
        }

        TraceStore.put("nova.queryAnalysis.anchorTail.used", true);
        TraceStore.put("nova.queryAnalysis.anchorTail", Map.of(
                "originalLen", r.originalLen(),
                "maxLen", r.maxLen(),
                "condensedLen", condensed.length(),
                "strategy", r.strategy().name(),
                "anchor", r.anchor()
        ));

        return pjp.proceed(new Object[]{condensed});
    }
}
