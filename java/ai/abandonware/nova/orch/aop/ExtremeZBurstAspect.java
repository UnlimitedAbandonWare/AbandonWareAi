package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Massive Parallel Query Expansion ("ExtremeZ") - minimal, fail-soft
 * orchestration layer.
 *
 * <p>
 * 의도: 1차 retrieval 결과가 너무 빈약할 때, 몇 개의 cheap query variant를 추가로 web-retrieval에
 * 흘려 보내 recall을 보강한다.
 *
 * <p>
 * 기본은 OFF이며, webRateLimited/strikeMode/auxDown 상태에서는 기본적으로 스킵하도록 옵션을 제공한다.
 */
@Aspect
public class ExtremeZBurstAspect {

    private static final Logger log = LoggerFactory.getLogger(ExtremeZBurstAspect.class);

    private final ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider;
    private final AnchorNarrower anchorNarrower;
    private final NovaOrchestrationProperties props;

    private final ThreadLocal<Boolean> reentryGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public ExtremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props) {
        this.analyzeWebSearchRetrieverProvider = analyzeWebSearchRetrieverProvider;
        this.anchorNarrower = anchorNarrower;
        this.props = props;
    }

    @Around("execution(java.util.List<dev.langchain4j.rag.content.Content> com.example.lms.service.rag.HybridRetriever.retrieve*(..))")
    public Object aroundHybridRetrieve(ProceedingJoinPoint pjp) throws Throwable {
        // If a pipeline already executed, avoid double-expansion.
        if (TraceStore.get("orch.pipeline.executed") != null) {
            return pjp.proceed();
        }

        NovaOrchestrationProperties.ExtremeZProps z = (props != null) ? props.getExtremeZ() : null;
        GuardContext gctx = GuardContextHolder.getOrDefault();

        boolean globalEnabled = z != null && z.isEnabled();
        boolean planEnabled = gctx != null && gctx.planBool("extremeZ.enabled", false);
        if (!globalEnabled && !planEnabled) {
            return pjp.proceed();
        }

        // Avoid accidental self-recursion in case the underlying retriever calls itself
        // through a proxy.
        if (Boolean.TRUE.equals(reentryGuard.get())) {
            return pjp.proceed();
        }

        boolean skipWhenStrike = (z == null) || z.isSkipWhenStrikeMode();
        boolean skipWhenWebRateLimited = (z == null) || z.isSkipWhenWebRateLimited();
        boolean skipWhenAuxDown = (z == null) || z.isSkipWhenAuxDown();

        // Plan-level overrides (passthrough) — lets strike-mode plans enable/disable ExtremeZ
        // without forcing a global behavior change.
        if (gctx != null) {
            skipWhenStrike = gctx.planBool("extremeZ.skipWhenStrikeMode", skipWhenStrike);
            skipWhenWebRateLimited = gctx.planBool("extremeZ.skipWhenWebRateLimited", skipWhenWebRateLimited);
            skipWhenAuxDown = gctx.planBool("extremeZ.skipWhenAuxDown", skipWhenAuxDown);
        }

        if (gctx != null && skipWhenStrike && gctx.isStrikeMode()) {
            return pjp.proceed();
        }
        if (gctx != null && skipWhenWebRateLimited && gctx.isWebRateLimited()) {
            return pjp.proceed();
        }
        if (gctx != null && skipWhenAuxDown && gctx.isAuxDown()) {
            return pjp.proceed();
        }

        Object baseObj;
        reentryGuard.set(Boolean.TRUE);
        try {
            baseObj = pjp.proceed();
        } finally {
            reentryGuard.set(Boolean.FALSE);
        }

        if (!(baseObj instanceof List<?> rawList)) {
            return baseObj;
        }

        @SuppressWarnings("unchecked")
        List<Content> baseDocs = (List<Content>) rawList;
        int baseSize = (baseDocs == null) ? 0 : baseDocs.size();
        int minBaseDocs = (z != null ? z.getMinBaseDocs() : 3);
        if (gctx != null) {
            minBaseDocs = gctx.planInt("extremeZ.minBaseDocs", minBaseDocs);
        }
        if (baseSize >= minBaseDocs) {
            return baseObj;
        }

        AnalyzeWebSearchRetriever analyzeRetriever = analyzeWebSearchRetrieverProvider.getIfAvailable();
        if (analyzeRetriever == null) {
            return baseObj;
        }

        Query originalQuery = extractQueryArg(pjp.getArgs());
        if (originalQuery == null) {
            return baseObj;
        }

        String qText = safeText(originalQuery.text());
        if (qText.isBlank()) {
            return baseObj;
        }

        int maxSubQueries = (z != null ? z.getMaxSubQueries() : 6);
        if (gctx != null) {
            int direct = gctx.planInt("extremeZ.maxSubQueries", -1);
            if (direct > 0) {
                maxSubQueries = direct;
            } else {
                // fallback: share the same knob with query burst when not explicitly set
                int qb = gctx.planInt("expand.queryBurst.count", -1);
                if (qb > 0) {
                    maxSubQueries = qb;
                }
            }
        }
        if (maxSubQueries < 1) {
            return baseObj;
        }

        List<String> variants = buildVariants(qText, maxSubQueries);
        if (variants.isEmpty()) {
            return baseObj;
        }

        long budgetMs = (z != null ? z.getBudgetMs() : 1500L);
        if (gctx != null) {
            budgetMs = gctx.planLong("extremeZ.budgetMs", budgetMs);
        }
        long deadline = System.currentTimeMillis() + Math.max(50L, budgetMs);
        List<Content> extra = new ArrayList<>();

        for (String v : variants) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            try {
                Query q2 = Query.builder().text(v).metadata(originalQuery.metadata()).build();
                List<Content> r = analyzeRetriever.retrieve(q2);
                if (r != null && !r.isEmpty()) {
                    extra.addAll(r);
                }
            } catch (Exception e) {
                // fail-soft: ignore and keep going
                log.debug("[ExtremeZ] variant retrieval failed (v={})", v, e);
            }
        }

        if (extra.isEmpty()) {
            return baseObj;
        }

        int maxMergedDocs = (z != null ? z.getMaxMergedDocs() : 16);
        if (gctx != null) {
            maxMergedDocs = gctx.planInt("extremeZ.maxMergedDocs", maxMergedDocs);
        }
        // if a caller passed a limit(=retrieveAll limit), respect it
        Integer reqLimit = extractLimitArg(pjp.getArgs());
        if (reqLimit != null && reqLimit > 0) {
            maxMergedDocs = Math.min(maxMergedDocs, reqLimit);
        }

        List<Content> merged = mergeAndDedupe(baseDocs, extra, maxMergedDocs);

        if (merged.size() > baseSize) {
            log.info("[ExtremeZ] recall boost base={} extra={} merged={}", baseSize, extra.size(), merged.size());
        }
        return merged;
    }

    private Query extractQueryArg(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object a0 = args[0];
        if (a0 instanceof Query q) {
            return q;
        }
        if (a0 instanceof String s) {
            try {
                return Query.builder().text(s).metadata(Collections.emptyMap()).build();
            } catch (Exception ignore) {
                return null;
            }
        }
        if (a0 instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s0) {
            try {
                return Query.builder().text(s0).metadata(Collections.emptyMap()).build();
            } catch (Exception ignore) {
                return null;
            }
        }
        if (a0 instanceof PromptContext pc) {
            String q = (pc.userQuery() == null) ? "" : pc.userQuery();
            if (!q.isBlank()) {
                try {
                    return Query.builder().text(q).metadata(Collections.emptyMap()).build();
                } catch (Exception ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    private Integer extractLimitArg(Object[] args) {
        if (args == null || args.length < 2) return null;
        // Common signatures: retrieveAll(List<String> queries, int limit, ...)
        // or retrieve(String q, int limit, ...)
        Object a1 = args[1];
        if (a1 instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private List<String> buildVariants(String queryText, int max) {
        String q = safeText(queryText);
        if (q.isBlank() || max <= 0) {
            return Collections.emptyList();
        }

        String anchor = pickAnchor(q);

        Set<String> uniq = new LinkedHashSet<>();

        // 1) anchor-aware cheap variants
        if (anchorNarrower != null) {
            try {
                AnchorNarrower.Anchor anchorObj = anchorNarrower.pick(q, Collections.emptyList(),
                        Collections.emptyList());
                uniq.addAll(anchorNarrower.cheapVariants(q, anchorObj));
            } catch (Exception ignore) {
                // ignore
            }
        }

        // Always include the anchor term itself once (helps in Korean short queries)
        if (anchor != null && !anchor.isBlank()) {
            uniq.add(anchor);
        }

        // 2) small set of deterministic recall boosters
        uniq.add(q + " 정리");
        uniq.add(q + " 공식");
        uniq.add(q + " 출처");
        uniq.add(q + " 최신");

        // remove exact original to avoid duplicate network call
        uniq.remove(q);

        List<String> out = new ArrayList<>();
        for (String s : uniq) {
            String v = safeQueryVariant(s);
            if (!v.isBlank()) {
                out.add(v);
            }
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    private String pickAnchor(String queryText) {
        String q = safeText(queryText);
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

    private List<Content> mergeAndDedupe(List<Content> base, List<Content> extra, int max) {
        int limit = Math.max(1, max);

        Map<String, Content> seen = new LinkedHashMap<>();
        addAll(seen, base);
        addAll(seen, extra);

        List<Content> out = new ArrayList<>();
        for (Content c : seen.values()) {
            out.add(c);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private void addAll(Map<String, Content> seen, List<Content> items) {
        if (items == null) {
            return;
        }
        for (Content c : items) {
            if (c == null) {
                continue;
            }
            String key = fingerprint(c);
            if (!seen.containsKey(key)) {
                seen.put(key, c);
            }
        }
    }

    private String fingerprint(Content c) {
        String t;
        try {
            t = (c.textSegment() != null) ? safeText(c.textSegment().text()) : "";
        } catch (Exception e) {
            t = "";
        }
        String url = extractUrl(t);
        if (!url.isBlank()) {
            return "url:" + url;
        }
        if (t.isBlank()) {
            return "obj:" + Integer.toHexString(System.identityHashCode(c));
        }
        String normalized = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return "txt:" + (normalized.length() > 180 ? normalized.substring(0, 180) : normalized);
    }

    private String extractUrl(String text) {
        String s = safeText(text);
        if (s.isBlank()) {
            return "";
        }
        int idx = s.indexOf("http");
        if (idx >= 0) {
            int end = s.indexOf(' ', idx);
            if (end < 0) {
                end = s.length();
            }
            return s.substring(idx, end).trim();
        }
        int u = s.indexOf("URL:");
        if (u >= 0) {
            int start = u + 4;
            int end = s.indexOf('\n', start);
            if (end < 0) {
                end = s.length();
            }
            return s.substring(start, end).trim();
        }
        return "";
    }

    private String safeQueryVariant(String s) {
        String v = safeText(s);
        if (v.isBlank()) {
            return "";
        }
        v = v.replaceAll("\\s+", " ").trim();
        if (v.length() > 120) {
            v = v.substring(0, 120);
        }
        return v;
    }

    private String safeText(String s) {
        return s == null ? "" : s;
    }
}
