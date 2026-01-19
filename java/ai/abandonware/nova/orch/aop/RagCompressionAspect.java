package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Strike/Compression 모드에서 HybridRetriever의 retrieval 결과를 "가벼운" 컨텍스트로 압축한다.
 *
 * <p>추가 오케스트레이션: OverdriveGuard(증거 부족/상충) 기반으로, STRIKE/COMPRESSION이 아니어도
 * 자동으로 압축을 트리거할 수 있다(옵션, fail-soft).</p>
 */
@Aspect
public class RagCompressionAspect {

    private static final Logger log = LoggerFactory.getLogger(RagCompressionAspect.class);

    private final DynamicContextCompressor compressor;
    private final AnchorNarrower anchorNarrower;
    private final NovaOrchestrationProperties props;
    private final OverdriveGuard overdriveGuard; // optional (may be null)

    public RagCompressionAspect(
            DynamicContextCompressor compressor,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            OverdriveGuard overdriveGuard
    ) {
        this.compressor = compressor;
        this.anchorNarrower = anchorNarrower;
        this.props = props;
        this.overdriveGuard = overdriveGuard;
    }

    @Around("execution(java.util.List<dev.langchain4j.rag.content.Content> com.example.lms.service.rag..*.retrieve(..))")
    public Object aroundRetrieve(ProceedingJoinPoint pjp) throws Throwable {
        Object out = pjp.proceed();
        if (!(out instanceof List<?> rawList)) {
            return out;
        }

        // feature toggle (double safety: bean is already conditional)
        if (props != null && props.getRagCompressor() != null && !props.getRagCompressor().isEnabled()) {
            return out;
        }

        // telemetry hint: compression is enabled/configured
        try {
            TraceStore.put("rag.compress.enabled", true);
        } catch (Exception ignore) {
            // ignore
        }

        @SuppressWarnings("unchecked")
        List<Content> docs = (List<Content>) rawList;
        if (docs == null || docs.size() <= 1) {
            return out;
        }

        GuardContext ctxRef = GuardContextHolder.get();
        GuardContext gctx = (ctxRef != null) ? ctxRef : GuardContextHolder.getOrDefault();

        boolean alreadyCompression = gctx.isCompressionMode() || gctx.isStrikeMode();
        if (!alreadyCompression) {
            // Optional: auto-activate compression based on OverdriveGuard even when not in STRIKE/COMPRESSION.
            if (!shouldAutoActivateOverdrive()) {
                return out;
            }

            String queryText = extractQueryText(pjp.getArgs());
            if ((queryText == null || queryText.isBlank()) && ctxRef != null) {
                queryText = ctxRef.getUserQuery();
            }

            try {
                if (overdriveGuard != null && overdriveGuard.shouldActivate(queryText, docs)) {
                    if (props != null
                            && props.getOverdrive() != null
                            && props.getOverdrive().isMarkCompressionMode()
                            && ctxRef != null) {
                        ctxRef.setCompressionMode(true);
                    }

                    String anchor = pickAnchor(queryText);
                    List<Content> compressed = compressor != null
                            ? compressor.compress(anchor, docs)
                            : docs;

                    if (compressed != null && !compressed.isEmpty() && compressed != docs) {
                        try {
                            TraceStore.put("rag.compress.applied", true);
                            TraceStore.put("rag.compress.beforeDocs", docs.size());
                            TraceStore.put("rag.compress.afterDocs", compressed.size());
                            TraceStore.put("rag.compress.anchorLen", (anchor == null ? 0 : anchor.length()));
                        } catch (Exception ignore) {
                            // ignore
                        }
                        log.info("[NovaRagCompressor/Overdrive] compressed {} -> {} docs (anchor={})",
                                docs.size(), compressed.size(), anchor);
                        return compressed;
                    }
                }
            } catch (Exception e) {
                // fail-soft
                log.debug("[NovaRagCompressor/Overdrive] activation failed; returning original docs", e);
            }
            return out;
        }

        String queryText = extractQueryText(pjp.getArgs());
        if ((queryText == null || queryText.isBlank()) && ctxRef != null) {
            queryText = ctxRef.getUserQuery();
        }
        String anchor = pickAnchor(queryText);

        int before = docs.size();
        List<Content> compressed = compressor != null
                ? compressor.compress(anchor, docs)
                : docs;
        int after = compressed != null ? compressed.size() : 0;

        if (compressed != null && !compressed.isEmpty() && compressed != docs) {
            try {
                TraceStore.put("rag.compress.applied", true);
                TraceStore.put("rag.compress.beforeDocs", before);
                TraceStore.put("rag.compress.afterDocs", after);
                TraceStore.put("rag.compress.anchorLen", (anchor == null ? 0 : anchor.length()));
            } catch (Exception ignore) {
                // ignore
            }
            log.info("[NovaRagCompressor] compressed {} -> {} docs (anchor={})", before, after, anchor);
            return compressed;
        }
        return out;
    }

    private boolean shouldAutoActivateOverdrive() {
        if (overdriveGuard == null) {
            return false;
        }
        if (props == null || props.getOverdrive() == null) {
            return true;
        }
        return props.getOverdrive().isEnabled() && props.getOverdrive().isAutoActivateCompression();
    }

    private String extractQueryText(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        Object a0 = args[0];
        if (a0 instanceof Query q) {
            try {
                return q.text();
            } catch (Exception ignore) {
                return "";
            }
        }
        if (a0 instanceof String s) {
            return s;
        }
        return "";
    }

    private String pickAnchor(String queryText) {
        String q = queryText == null ? "" : queryText.trim();
        if (q.isBlank()) {
            return "";
        }
        try {
            if (anchorNarrower == null) {
                return q;
            }
            AnchorNarrower.Anchor a = anchorNarrower.pick(q, Collections.emptyList(), Collections.emptyList());
            return (a == null || a.term() == null) ? q : a.term();
        } catch (Exception ignore) {
            return q;
        }
    }
}
