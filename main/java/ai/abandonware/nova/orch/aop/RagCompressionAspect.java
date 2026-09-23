package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.overdrive.AngerOverdriveNarrower;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Strike/Compression 모드에서 HybridRetriever의 retrieval 결과를 "가벼운" 컨텍스트로 압축한다.
 *
 * <p>추가 오케스트레이션: OverdriveGuard(증거 부족/상충) 기반으로, STRIKE/COMPRESSION이 아니어도
 * 자동으로 압축을 트리거할 수 있다(옵션, fail-soft).</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 250)
public class RagCompressionAspect {

    private static final Logger log = LoggerFactory.getLogger(RagCompressionAspect.class);

    private final DynamicContextCompressor compressor;
    private final AnchorNarrower anchorNarrower;
    private final NovaOrchestrationProperties props;
    private final OverdriveGuard overdriveGuard; // optional (may be null)
    private final AngerOverdriveNarrower overdriveNarrower; // optional (may be null)

    public RagCompressionAspect(
            DynamicContextCompressor compressor,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            OverdriveGuard overdriveGuard
    ) {
        this(compressor, anchorNarrower, props, overdriveGuard, null);
    }

    public RagCompressionAspect(
            DynamicContextCompressor compressor,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            OverdriveGuard overdriveGuard,
            AngerOverdriveNarrower overdriveNarrower
    ) {
        this.compressor = compressor;
        this.anchorNarrower = anchorNarrower;
        this.props = props;
        this.overdriveGuard = overdriveGuard;
        this.overdriveNarrower = overdriveNarrower;
    }

    @Around("execution(java.util.List<dev.langchain4j.rag.content.Content> com.example.lms.service.rag..*.retrieve(..))"
            + " || execution(java.util.List<dev.langchain4j.rag.content.Content> com.example.lms.service.rag.HybridRetriever.retrieveAll(java.util.List, int, java.lang.Object, java.util.Map))")
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
            WebFailSoftTraceSuppressions.trace("ragCompression.enabledTrace", ignore);
        }

        @SuppressWarnings("unchecked")
        List<Content> docs = (List<Content>) rawList;
        if (docs == null || docs.size() <= 1) {
            return out;
        }

        GuardContext ctxRef = GuardContextHolder.get();
        GuardContext gctx = (ctxRef != null) ? ctxRef : GuardContextHolder.getOrDefault();

        boolean alreadyCompression = gctx.isCompressionMode() || gctx.isStrikeMode();
        if (!alreadyCompression && isSuppressedByPrimaryMode(gctx, "OVERDRIVE")) {
            String primaryMode = planPrimaryMode(gctx);
            traceCompressSkip("special_mode_" + primaryMode.toLowerCase(Locale.ROOT));
            return out;
        }
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
                    List<Content> narrowedDocs = maybeApplyOverdriveNarrow(queryText, docs, gctx);
                    List<Content> compressed = compressor != null
                            ? compressor.compress(anchor, narrowedDocs)
                            : narrowedDocs;

                    if (compressed != null && !compressed.isEmpty() && compressed != docs) {
                        traceCompressionApplied(docs.size(), compressed.size(), anchor);
                        log.info("[NovaRagCompressor/Overdrive] compressed {} -> {} docs (anchorHash={} anchorLength={})",
                                docs.size(), compressed.size(), SafeRedactor.hashValue(anchor), lengthOf(anchor));
                        return compressed;
                    }
                }
            } catch (Exception e) {
                WebFailSoftTraceSuppressions.trace("ragCompression.overdriveActivation", e);
                log.debug("[NovaRagCompressor/Overdrive] activation failed; returning original docs. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            }
            return out;
        }

        String queryText = extractQueryText(pjp.getArgs());
        if ((queryText == null || queryText.isBlank()) && ctxRef != null) {
            queryText = ctxRef.getUserQuery();
        }
        String anchor = pickAnchor(queryText);

        int before = docs.size();
        List<Content> narrowedDocs = maybeApplyOverdriveNarrow(queryText, docs, gctx);
        List<Content> compressed = compressor != null
                ? compressor.compress(anchor, narrowedDocs)
                : narrowedDocs;
        int after = compressed != null ? compressed.size() : 0;

        if (compressed != null && !compressed.isEmpty() && compressed != docs) {
            traceCompressionApplied(before, after, anchor);
            log.info("[NovaRagCompressor] compressed {} -> {} docs (anchorHash={} anchorLength={})",
                    before, after, SafeRedactor.hashValue(anchor), lengthOf(anchor));
            return compressed;
        }
        return out;
    }

    private static void traceCompressionApplied(int before, int after, String anchor) {
        try {
            TraceStore.put("rag.compress.applied", true);
            TraceStore.put("rag.compress.beforeDocs", before);
            TraceStore.put("rag.compress.afterDocs", after);
            TraceStore.put("rag.compress.anchorHash", SafeRedactor.hashValue(anchor));
            TraceStore.put("rag.compress.anchorLen", lengthOf(anchor));
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("ragCompression.appliedTrace", ignore);
        }
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
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

    private List<Content> maybeApplyOverdriveNarrow(String queryText, List<Content> docs, GuardContext gctx) {
        boolean planEnabled = gctx != null && gctx.planBool("overdrive.narrow.enabled", false);
        try {
            TraceStore.put("overdrive.narrow.plan.enabled", planEnabled);
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("overdriveNarrow.planEnabledTrace", ignore);
        }
        if (!planEnabled || overdriveNarrower == null || docs == null || docs.size() <= 1) {
            traceNarrowPlan(false, reasonForNarrowSkip(planEnabled, docs));
            return docs;
        }
        try {
            List<Content> narrowed = overdriveNarrower.narrow(queryText, docs);
            if (narrowed == null || narrowed.isEmpty()) {
                traceNarrowPlan(false, "empty_original_returned");
                return docs;
            }
            boolean applied = narrowed != docs;
            traceNarrowPlan(applied, applied ? "applied" : "original_returned");
            return narrowed;
        } catch (Exception e) {
            WebFailSoftTraceSuppressions.trace("overdriveNarrow.invoke", e);
            log.debug("[NovaRagCompressor/Overdrive] narrow failed; returning original docs. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            traceNarrowPlan(false, "exception_original_returned");
            return docs;
        }
    }

    private String reasonForNarrowSkip(boolean planEnabled, List<Content> docs) {
        if (!planEnabled) {
            return "plan_disabled";
        }
        if (overdriveNarrower == null) {
            return "narrower_missing";
        }
        if (docs == null || docs.size() <= 1) {
            return "insufficient_docs";
        }
        return "skipped";
    }

    private static void traceNarrowPlan(boolean applied, String reason) {
        try {
            TraceStore.put("overdrive.narrow.plan.applied", applied);
            TraceStore.put("overdrive.narrow.plan.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("overdriveNarrow.planTrace", ignore);
        }
    }

    private static void traceCompressSkip(String reason) {
        try {
            TraceStore.put("rag.compress.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("specialMode.conflict.overdrive.suppressed", true);
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("ragCompression.skipTrace", ignore);
        }
    }

    private static String planPrimaryMode(GuardContext gctx) {
        Object value = gctx == null ? null : gctx.getPlanOverride("executionPlan.primaryMode");
        if (value == null) {
            value = gctx == null ? null : gctx.getPlanOverride("routing.executionPlan.primaryMode");
        }
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isSuppressedByPrimaryMode(GuardContext gctx, String currentMode) {
        String primaryMode = planPrimaryMode(gctx);
        if (primaryMode.isBlank() || "NORMAL".equals(primaryMode)) {
            return false;
        }
        return !currentMode.equals(primaryMode);
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
                WebFailSoftTraceSuppressions.trace("ragCompression.extractQueryText", ignore);
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
            WebFailSoftTraceSuppressions.trace("ragCompression.pickAnchor", ignore);
            return q;
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }
}
