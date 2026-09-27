package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import com.example.lms.cfvm.CfvmFailureRecorder;
import com.example.lms.cfvm.RawSlotExtractor;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.prompt.PromptContext;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.budget.RetrievalBudgetDecision;
import com.example.lms.service.rag.budget.RetrievalBudgetGovernor;
import com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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
@Order(Ordered.LOWEST_PRECEDENCE - 300)
public class ExtremeZBurstAspect {

    private static final Logger log = LoggerFactory.getLogger(ExtremeZBurstAspect.class);

    private final ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider;
    private final AnchorNarrower anchorNarrower;
    private final NovaOrchestrationProperties props;
    private final ObjectProvider<ContradictionScorer> contradictionScorerProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final ObjectProvider<RagFailureBlackboxService> blackboxProvider;
    private final ObjectProvider<RetrievalBudgetGovernor> budgetGovernorProvider;
    private final ObjectProvider<CfvmFailureRecorder> cfvmFailureRecorderProvider;

    private final ThreadLocal<Boolean> reentryGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public ExtremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<ContradictionScorer> contradictionScorerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this(analyzeWebSearchRetrieverProvider, anchorNarrower, props, contradictionScorerProvider,
                debugEventStoreProvider, null, null);
    }

    public ExtremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<ContradictionScorer> contradictionScorerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this(analyzeWebSearchRetrieverProvider, anchorNarrower, props, contradictionScorerProvider,
                debugEventStoreProvider, blackboxProvider, null);
    }

    public ExtremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<ContradictionScorer> contradictionScorerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<RagFailureBlackboxService> blackboxProvider,
            ObjectProvider<RetrievalBudgetGovernor> budgetGovernorProvider) {
        this(analyzeWebSearchRetrieverProvider, anchorNarrower, props, contradictionScorerProvider,
                debugEventStoreProvider, blackboxProvider, budgetGovernorProvider, null);
    }

    public ExtremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<ContradictionScorer> contradictionScorerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<RagFailureBlackboxService> blackboxProvider,
            ObjectProvider<RetrievalBudgetGovernor> budgetGovernorProvider,
            ObjectProvider<CfvmFailureRecorder> cfvmFailureRecorderProvider) {
        this.analyzeWebSearchRetrieverProvider = analyzeWebSearchRetrieverProvider;
        this.anchorNarrower = anchorNarrower;
        this.props = props;
        this.contradictionScorerProvider = contradictionScorerProvider;
        this.debugEventStoreProvider = debugEventStoreProvider;
        this.blackboxProvider = blackboxProvider;
        this.budgetGovernorProvider = budgetGovernorProvider;
        this.cfvmFailureRecorderProvider = cfvmFailureRecorderProvider;
    }

    @Around("execution(java.util.List<dev.langchain4j.rag.content.Content> com.example.lms.service.rag.HybridRetriever.retrieve*(..))")
    public Object aroundHybridRetrieve(ProceedingJoinPoint pjp) throws Throwable {
        // If a pipeline already executed, avoid double-expansion.
        if (TraceStore.get("orch.pipeline.executed") != null) {
            return pjp.proceed();
        }
        // The canonical burst handler performs its own web/vector expansion. If it
        // already ran in this request, this AOP path must not launch another web burst.
        if (Boolean.TRUE.equals(TraceStore.get("extremez.execute.activated"))) {
            traceSkip("burst_handler_already_ran", 0, 0);
            return pjp.proceed();
        }

        NovaOrchestrationProperties.ExtremeZProps z = (props != null) ? props.getExtremeZ() : null;
        GuardContext gctx = GuardContextHolder.getOrDefault();

        boolean globalEnabled = z != null && z.isEnabled();
        boolean planEnabled = gctx != null && gctx.planBool("extremeZ.enabled", false);
        trace("extremez.enabled", globalEnabled || planEnabled);
        trace("extremez.enabled.global", globalEnabled);
        trace("extremez.enabled.plan", planEnabled);
        String primaryMode = planPrimaryMode(gctx);
        if ((globalEnabled || planEnabled) && isSuppressedByPrimaryMode(primaryMode, "EXTREMEZ")) {
            trace("specialMode.conflict.extremeZ.suppressed", true);
            trace("specialMode.conflict.extremeZ.primaryMode", primaryMode);
            traceSkip("special_mode_" + primaryMode.toLowerCase(Locale.ROOT), 0, 0);
            return pjp.proceed();
        }
        if (!globalEnabled && !planEnabled) {
            traceSkip("disabled", 0, 0);
            return pjp.proceed();
        }

        // Avoid accidental self-recursion in case the underlying retriever calls itself
        // through a proxy.
        if (Boolean.TRUE.equals(reentryGuard.get())) {
            traceSkip("reentry", 0, 0);
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
            traceSkip("strike_mode", 0, 0);
            return pjp.proceed();
        }
        if (gctx != null && skipWhenWebRateLimited && gctx.isWebRateLimited()) {
            traceSkip("web_rate_limited", 0, 0);
            return pjp.proceed();
        }
        if (gctx != null && skipWhenAuxDown && gctx.isAuxDown()) {
            traceSkip("aux_down", 0, 0);
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
        trace("extremez.base.count", baseSize);
        int minBaseDocs = (z != null ? z.getMinBaseDocs() : 3);
        if (gctx != null) {
            minBaseDocs = gctx.planInt("extremeZ.minBaseDocs", minBaseDocs);
        }
        trace("extremez.minBaseDocs", minBaseDocs);

        Query originalQuery = extractQueryArg(pjp.getArgs());
        String qText = originalQuery == null ? "" : safeText(originalQuery.text());
        if (!qText.isBlank()) {
            trace("extremez.query.hash", safeHash(qText));
            trace("extremez.query.len", qText.length());
        } else {
            trace("extremez.query.hash", "");
            trace("extremez.query.len", 0);
        }

        ExtremeZRiskSignal risk = computeRiskSignal(baseDocs, minBaseDocs, z, gctx);
        traceRisk(risk);
        if (!shouldActivateRisk(risk)) {
            traceSkip(risk.reason(), baseSize, 0);
            recordCfvmFailurePattern(risk, originalQuery);
            emitRiskEvent(risk, false, baseSize, 0);
            return baseObj;
        }
        trace("extremez.activation.reason", risk.reason());
        recordCfvmFailurePattern(risk, originalQuery);
        emitRiskEvent(risk, true, baseSize, 0);

        AnalyzeWebSearchRetriever analyzeRetriever = analyzeWebSearchRetrieverProvider == null
                ? null
                : analyzeWebSearchRetrieverProvider.getIfAvailable();
        if (analyzeRetriever == null) {
            traceSkip("web_retriever_missing", baseSize, 0);
            return baseObj;
        }

        if (originalQuery == null) {
            traceSkip("query_missing", baseSize, 0);
            return baseObj;
        }

        if (qText.isBlank()) {
            traceSkip("query_blank", baseSize, 0);
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
            traceSkip("max_subqueries_lt_1", baseSize, 0);
            return baseObj;
        }
        trace("extremez.maxSubQueries", maxSubQueries);

        List<String> variants = buildVariants(qText, maxSubQueries, gctx);
        if (variants.isEmpty()) {
            traceSkip("variants_empty", baseSize, 0);
            return baseObj;
        }
        variants = governVariants(qText, variants, maxSubQueries);
        if (variants.isEmpty()) {
            traceSkip("variants_empty_after_budget", baseSize, 0);
            return baseObj;
        }
        trace("extremez.variants.count", variants.size());
        trace("extremez.subQueryCount", variants.size());

        long budgetMs = (z != null ? z.getBudgetMs() : 1500L);
        if (gctx != null) {
            budgetMs = gctx.planLong("extremeZ.budgetMs", budgetMs);
        }
        trace("extremez.budgetMs", budgetMs);
        long deadline = System.currentTimeMillis() + Math.max(50L, budgetMs);
        List<Content> extra = new ArrayList<>();
        int failures = 0;
        int branches = 0;

        for (String v : variants) {
            if (System.currentTimeMillis() > deadline) {
                trace("extremez.deadline.hit", true);
                break;
            }
            try {
                branches++;
                Query q2 = QueryUtils.rebuild(originalQuery, v);
                List<Content> r = analyzeRetriever.retrieve(q2);
                if (r != null && !r.isEmpty()) {
                    extra.addAll(r);
                }
            } catch (Exception e) {
                // fail-soft: ignore and keep going
                failures++;
                log.debug("[ExtremeZ] variant retrieval failed (variantHash={} variantLen={})",
                        safeHash(v), v == null ? 0 : v.length(), e);
            }
        }
        trace("extremez.extra.count", extra.size());
        trace("extremez.variant.failures", failures);
        trace("extremez.parallelBranchCount", branches);

        if (extra.isEmpty()) {
            traceSkip("extra_empty", baseSize, 0);
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
        trace("extremez.merged.count", merged.size());
        trace("extremez.mergedDocCount", merged.size());
        trace("extremez.rrfApplied", false);
        trace("extremez.maxMergedDocs", maxMergedDocs);
        trace("extremez.activated", merged.size() > baseSize);

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
                return QueryUtils.buildQuery(s, Collections.emptyMap());
            } catch (Exception e) {
                traceFailure("extractQuery.string", e);
                return null;
            }
        }
        if (a0 instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s0) {
            try {
                return QueryUtils.buildQuery(s0, Collections.emptyMap());
            } catch (Exception e) {
                traceFailure("extractQuery.list", e);
                return null;
            }
        }
        if (a0 instanceof PromptContext pc) {
            String q = (pc.userQuery() == null) ? "" : pc.userQuery();
            if (!q.isBlank()) {
                try {
                    return QueryUtils.buildQuery(q, Collections.emptyMap());
                } catch (Exception e) {
                    traceFailure("extractQuery.promptContext", e);
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
        return buildVariants(queryText, max, null);
    }

    private List<String> buildVariants(String queryText, int max, GuardContext gctx) {
        String q = safeText(queryText);
        if (q.isBlank() || max <= 0) {
            return Collections.emptyList();
        }

        String anchor = pickAnchor(q);

        Set<String> uniq = new LinkedHashSet<>();
        addPlanVariants(uniq, q, max, gctx);

        // 1) anchor-aware cheap variants
        if (anchorNarrower != null) {
            try {
                AnchorNarrower.Anchor anchorObj = anchorNarrower.pick(q, Collections.emptyList(),
                        Collections.emptyList());
                uniq.addAll(anchorNarrower.cheapVariants(q, anchorObj));
            } catch (Exception e) {
                traceFailure("variant.anchorNarrower", e);
            }
        }

        // Always include the anchor term itself once (helps in Korean short queries)
        if (anchor != null && !anchor.isBlank()) {
            uniq.add(anchor);
        }

        boolean qHasKorean = q.matches(".*[\\uAC00-\\uD7A3].*");
        boolean qHasEnglishOnly = !qHasKorean && q.matches(".*[A-Za-z].*");
        trace("extremez.variants.langDetect.hasKorean", qHasKorean);
        trace("extremez.variants.langDetect.hasEnglishOnly", qHasEnglishOnly);

        // 2) small set of deterministic recall boosters
        if (qHasKorean) {
            uniq.add(q + " 정리");
            uniq.add(q + " 공식");
            uniq.add(q + " 출처");
            uniq.add(q + " 최신");
        } else if (qHasEnglishOnly) {
            uniq.add(q + " guide");
            uniq.add(q + " official");
            uniq.add(q + " latest");
            uniq.add(q + " 2024");
        } else {
            uniq.add(q + " 정리");
            uniq.add(q + " guide");
        }

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

    private void addPlanVariants(Set<String> target, String originalQuery, int max, GuardContext gctx) {
        if (target == null || gctx == null || max <= 0) {
            trace("extremez.gachaVariants.plan.count", 0);
            return;
        }
        Object raw = firstPlanOverride(gctx,
                "extremeZ.gachaVariants",
                "queryBurst.gachaVariants",
                "dualGear.gachaVariants");
        if (!(raw instanceof Iterable<?> iterable)) {
            trace("extremez.gachaVariants.plan.count", 0);
            return;
        }

        List<String> hashes = new ArrayList<>();
        int added = 0;
        String original = safeQueryVariant(originalQuery);
        for (Object item : iterable) {
            if (added >= max) {
                break;
            }
            String variant = safeQueryVariant(String.valueOf(item));
            if (variant.isBlank() || variant.equals(original) || target.contains(variant)) {
                continue;
            }
            target.add(variant);
            hashes.add(safeHash(variant));
            added++;
        }
        trace("extremez.gachaVariants.plan.count", added);
        TraceStore.put("extremez.gachaVariants.plan.hashes", hashes);
    }

    private Object firstPlanOverride(GuardContext gctx, String... keys) {
        if (gctx == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = gctx.getPlanOverride(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<String> governVariants(String queryText, List<String> variants, int requestedMaxSubQueries) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        AnchorNarrowingResult anchorResult = null;
        double maxDriftScore = 0.65d;
        if (anchorNarrower != null) {
            try {
                anchorResult = anchorNarrower.narrow(queryText, variants, 3, maxDriftScore);
            } catch (Exception e) {
                traceFailure("budget.anchorNarrow", e);
                // Anchor diagnostics are fail-soft.
            }
        }
        RetrievalBudgetGovernor governor = budgetGovernorProvider == null ? null : budgetGovernorProvider.getIfAvailable();
        int governedLimit = variants.size();
        if (governor != null) {
            double textureHit = traceDouble("rag.offlineTexture.hitRate", 0.0d);
            if (TraceStore.get("rag.offlineTexture.hitRate") == null) {
                trace("extremez.budget.textureReason", "texture_unavailable");
            }
            RetrievalBudgetDecision decision = governor.decide(
                    anchorResult,
                    textureHit,
                    OfflineTextureSnapshotLoader.looksFreshnessQuery(queryText),
                    requestedMaxSubQueries,
                    requestedMaxSubQueries,
                    requestedMaxSubQueries,
                    variants.size());
            governedLimit = Math.max(1, Math.min(governedLimit, decision.queryBurstCount()));
            trace("extremez.budget.reason", decision.reason());
            trace("extremez.budget.queryBurstCount", decision.queryBurstCount());
            trace("extremez.budget.enableHypernova", decision.enableHypernova());
            trace("extremez.budget.enableMassiveExpansion", decision.enableMassiveExpansion());
        }
        List<String> filtered = variants;
        if (anchorNarrower != null && anchorResult != null) {
            try {
                List<String> candidateFiltered = anchorNarrower.filterCandidates(queryText, variants, anchorResult, maxDriftScore);
                if (!candidateFiltered.isEmpty()) {
                    filtered = candidateFiltered;
                }
            } catch (Exception e) {
                traceFailure("budget.anchorFilter", e);
                filtered = variants;
            }
        }
        if (filtered.size() <= governedLimit) {
            return filtered;
        }
        return new ArrayList<>(filtered.subList(0, governedLimit));
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
        } catch (Exception e) {
            traceFailure("anchor.pick", e);
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
            traceFailure("fingerprint.text", e);
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

    private ExtremeZRiskSignal computeRiskSignal(List<Content> baseDocs,
                                                 int minBaseDocs,
                                                 NovaOrchestrationProperties.ExtremeZProps z,
                                                 GuardContext gctx) {
        int baseCount = baseDocs == null ? 0 : baseDocs.size();
        int minDocs = Math.max(1, minBaseDocs);
        boolean sparse = baseCount < minDocs;
        double scarcityScore = sparse ? (minDocs - baseCount) / (double) minDocs : 0.0d;

        boolean riskGateEnabled = z == null || z.isRiskGateEnabled();
        if (gctx != null) {
            riskGateEnabled = gctx.planBool("extremeZ.riskGateEnabled", riskGateEnabled);
        }
        double riskScoreThreshold = planDouble(gctx, "extremeZ.riskScoreThreshold",
                z == null ? 0.55d : z.getRiskScoreThreshold());
        double contradictionThreshold = clamp01(planDouble(gctx, "extremeZ.contradictionTriggerThreshold",
                z == null ? 0.60d : z.getContradictionTriggerThreshold()));
        double errorRateThreshold = clamp01(planDouble(gctx, "extremeZ.errorRateTriggerThreshold",
                z == null ? 0.35d : z.getErrorRateTriggerThreshold()));
        double scarcityWeight = nonNegative(planDouble(gctx, "extremeZ.scarcityWeight",
                z == null ? 0.50d : z.getScarcityWeight()));
        double contradictionWeight = nonNegative(planDouble(gctx, "extremeZ.contradictionWeight",
                z == null ? 0.30d : z.getContradictionWeight()));
        double errorRateWeight = nonNegative(planDouble(gctx, "extremeZ.errorRateWeight",
                z == null ? 0.20d : z.getErrorRateWeight()));
        double starvationWeight = nonNegative(planDouble(gctx, "extremeZ.starvationWeight",
                z == null ? 0.20d : z.getStarvationWeight()));
        double retrievalFailureWeight = nonNegative(planDouble(gctx, "extremeZ.retrievalFailureWeight",
                z == null ? 0.20d : z.getRetrievalFailureWeight()));
        double starvationThreshold = clamp01(planDouble(gctx, "extremeZ.starvationTriggerThreshold",
                z == null ? 0.50d : z.getStarvationTriggerThreshold()));
        int maxPairs = Math.max(0, gctx == null
                ? (z == null ? 3 : z.getMaxContradictionPairs())
                : gctx.planInt("extremeZ.maxContradictionPairs", z == null ? 3 : z.getMaxContradictionPairs()));
        boolean debugEventsEnabled = z == null || z.isDebugEventsEnabled();
        if (gctx != null) {
            debugEventsEnabled = gctx.planBool("extremeZ.debugEventsEnabled", debugEventsEnabled);
        }
        boolean patternIdEnabled = z == null || z.isPatternIdEnabled();
        if (gctx != null) {
            patternIdEnabled = gctx.planBool("extremeZ.patternIdEnabled", patternIdEnabled);
        }

        ContradictionResult contradiction = contradictionMean(baseDocs, maxPairs);
        double errorRate = webErrorRateFromTrace();
        TraceFailureSignals failureSignals = traceFailureSignals(errorRate, patternIdEnabled);

        boolean contradictionTrigger = contradiction.mean() >= contradictionThreshold && contradiction.mean() > 0.0d;
        boolean errorRateTrigger = errorRate >= errorRateThreshold && errorRate > 0.0d;
        boolean starvationTrigger = failureSignals.starvationScore() >= starvationThreshold
                && failureSignals.starvationScore() > 0.0d;
        boolean retrievalFailureSignal = failureSignals.providerDisabled()
                || failureSignals.vectorDegraded()
                || failureSignals.ablationPresent()
                || failureSignals.silentFailure();
        boolean retrievalFailureTrigger = retrievalFailureSignal
                && failureSignals.retrievalFailureRate() >= errorRateThreshold
                && failureSignals.retrievalFailureRate() > 0.0d;
        double contradictionComponent = contradictionTrigger
                ? normalizedAboveThreshold(contradiction.mean(), contradictionThreshold)
                : 0.0d;
        double errorComponent = errorRateTrigger
                ? normalizedAboveThreshold(errorRate, errorRateThreshold)
                : 0.0d;
        double starvationComponent = starvationTrigger
                ? normalizedAboveThreshold(failureSignals.starvationScore(), starvationThreshold)
                : 0.0d;
        double retrievalFailureComponent = retrievalFailureTrigger
                ? normalizedAboveThreshold(failureSignals.retrievalFailureRate(), errorRateThreshold)
                : 0.0d;
        double weightedScore = clamp01(scarcityWeight * scarcityScore
                + contradictionWeight * contradictionComponent
                + errorRateWeight * errorComponent
                + starvationWeight * starvationComponent
                + retrievalFailureWeight * retrievalFailureComponent);

        double riskScore = weightedScore;
        if (sparse) {
            riskScore = Math.max(riskScore, scarcityScore);
        }
        if (contradictionTrigger) {
            riskScore = Math.max(riskScore, contradiction.mean());
        }
        if (errorRateTrigger) {
            riskScore = Math.max(riskScore, errorRate);
        }
        if (starvationTrigger) {
            riskScore = Math.max(riskScore, failureSignals.starvationScore());
        }
        if (retrievalFailureTrigger) {
            riskScore = Math.max(riskScore, failureSignals.retrievalFailureRate());
        }
        riskScore = clamp01(riskScore);

        boolean trigger = riskGateEnabled
                ? (sparse || ((contradictionTrigger || errorRateTrigger || starvationTrigger || retrievalFailureTrigger)
                        && riskScore >= riskScoreThreshold))
                : sparse;
        String reason;
        if (!riskGateEnabled) {
            reason = sparse ? "sparse" : "enough_base_docs";
        } else if (sparse) {
            reason = "sparse";
        } else if (!contradictionTrigger && !errorRateTrigger && !starvationTrigger && !retrievalFailureTrigger) {
            reason = "enough_base_docs";
        } else if (riskScore < riskScoreThreshold) {
            reason = "risk_below_threshold";
        } else {
            List<String> reasons = new ArrayList<>(4);
            if (contradictionTrigger) {
                reasons.add("contradiction");
            }
            if (errorRateTrigger) {
                reasons.add("error_rate");
            }
            if (starvationTrigger) {
                reasons.add("starvation");
            }
            if (retrievalFailureTrigger) {
                reasons.add("retrieval_failure");
            }
            reason = String.join("_", reasons);
        }

        return new ExtremeZRiskSignal(
                baseCount,
                minDocs,
                riskGateEnabled,
                scarcityScore,
                contradiction.mean(),
                contradiction.unavailable(),
                errorRate,
                failureSignals.starvationScore(),
                failureSignals.retrievalFailureRate(),
                failureSignals.primaryCause(),
                failureSignals.patternId(),
                weightedScore,
                riskScore,
                sparse,
                contradictionTrigger,
                errorRateTrigger,
                starvationTrigger,
                retrievalFailureTrigger,
                riskScoreThreshold,
                trigger,
                reason,
                debugEventsEnabled);
    }

    private boolean shouldActivateRisk(ExtremeZRiskSignal risk) {
        return risk != null && risk.trigger();
    }

    private ContradictionResult contradictionMean(List<Content> docs, int maxPairs) {
        if (docs == null || docs.size() < 2 || maxPairs <= 0) {
            return new ContradictionResult(0.0d, false);
        }
        ContradictionScorer scorer = contradictionScorerProvider == null
                ? null
                : contradictionScorerProvider.getIfAvailable();
        if (scorer == null) {
            return new ContradictionResult(0.0d, true);
        }
        List<String> texts = new ArrayList<>();
        for (Content c : docs) {
            String text = contentText(c);
            if (!text.isBlank()) {
                texts.add(text);
            }
            if (texts.size() >= Math.max(2, maxPairs + 1)) {
                break;
            }
        }
        int used = 0;
        double sum = 0.0d;
        try {
            for (int i = 0; i < texts.size() && used < maxPairs; i++) {
                for (int j = i + 1; j < texts.size() && used < maxPairs; j++) {
                    sum += clamp01(scorer.score(texts.get(i), texts.get(j)));
                    used++;
                }
            }
        } catch (Exception e) {
            traceFailure("contradiction.score", e);
            trace("extremez.risk.contradictionUnavailable", true);
            return new ContradictionResult(0.0d, true);
        }
        return new ContradictionResult(used == 0 ? 0.0d : sum / used, false);
    }

    private String contentText(Content c) {
        try {
            return c == null || c.textSegment() == null ? "" : safeText(c.textSegment().text());
        } catch (Exception e) {
            traceFailure("contentText", e);
            return "";
        }
    }

    private double webErrorRateFromTrace() {
        long events = TraceStore.getLong("web.await.events.count");
        long failures = TraceStore.getLong("web.await.events.timeout.count")
                + TraceStore.getLong("web.await.events.nonOk.count")
                + TraceStore.getLong("web.await.skipped.count")
                + TraceStore.getLong("web.failsoft.rateLimitBackoff.skipped.cooldown.count")
                + TraceStore.getLong("web.await.budgetExhausted")
                + TraceStore.getLong("web.await.cancelSuppressed");
        long denominator = Math.max(events, failures);
        if (denominator <= 0L || failures <= 0L) {
            return 0.0d;
        }
        return clamp01(failures / (double) denominator);
    }

    private TraceFailureSignals traceFailureSignals(double webErrorRate, boolean patternIdEnabled) {
        refreshBlackbox("ExtremeZBurstAspect.traceFailureSignals");
        Map<String, Object> trace;
        try {
            trace = TraceStore.getAll();
        } catch (Throwable ignore) {
            traceFailure("traceFailureSignals.trace", ignore);
            trace = Collections.emptyMap();
        }

        boolean providerDisabled = anyTruthy(trace,
                "web.naver.providerDisabled",
                "web.brave.providerDisabled",
                "web.serpapi.providerDisabled",
                "web.tavily.providerDisabled")
                || listLikePresent(trace.get("providerDisabledSignals"))
                || listLikePresent(trace.get("rag.eval.providerDisabledSignals"));
        boolean explicitStarved = anyTruthy(trace,
                "web.starved",
                "web.domainFilter.starved",
                "web.failsoft.starved",
                "web.hybrid.starved",
                "web.naver.filter.starvedByStrictDomain",
                "webSearch.genericCut.starved",
                "webSearch.eduCut.starved");
        boolean afterFilterStarved = afterFilterStarved(trace);
        boolean zeroResults = anyTruthy(trace,
                "web.naver.zeroResults",
                "web.brave.zeroResults",
                "web.serpapi.zeroResults",
                "web.tavily.zeroResults");
        boolean vectorDegraded = anyTruthy(trace,
                "vector.scopeFilter.relaxed",
                "vector.docTypeFilter.relaxed",
                "vector.poisoning.bypass",
                "vector.fp.bypassed");
        boolean silentFailure = anyTruthy(trace,
                "bypass.silentFailure",
                "orch.noiseEscape.bypassSilentFailure");
        double blackboxRisk = blackboxEffectiveRisk(trace);
        String blackboxAction = String.valueOf(trace.getOrDefault("blackbox.risk.restoreAction", ""));
        String blackboxFailure = String.valueOf(trace.getOrDefault("blackbox.risk.dominantFailure", ""));

        String ablationCause = firstAblationCause(trace.get("orch.debug.ablation.strike"));
        if (ablationCause.isBlank()) {
            ablationCause = firstAblationCause(trace.get("orch.debug.ablation.bypass"));
        }
        boolean ablationPresent = !ablationCause.isBlank();

        double starvationScore = 0.0d;
        if (explicitStarved || afterFilterStarved) {
            starvationScore = 1.0d;
        } else if (zeroResults) {
            starvationScore = 0.65d;
        }
        if (blackboxRisk > 0.0d) {
            providerDisabled = providerDisabled || "disable_provider_failsoft".equals(blackboxAction);
            afterFilterStarved = afterFilterStarved || "anchor_compression_topup".equals(blackboxAction);
            vectorDegraded = vectorDegraded || "vector_quarantine".equals(blackboxAction);
            silentFailure = silentFailure
                    || "web_await_bypass".equals(blackboxAction)
                    || "llm_route_degrade".equals(blackboxAction)
                    || "safe_path_bypass".equals(blackboxAction)
                    || "missing_future".equals(blackboxFailure)
                    || "model_required".equals(blackboxFailure);
            if ("anchor_compression_topup".equals(blackboxAction)) {
                starvationScore = Math.max(starvationScore, blackboxRisk);
            }
        }

        double retrievalFailureRate = clamp01(Math.max(webErrorRate,
                Math.max(providerDisabled ? 1.0d : 0.0d,
                        Math.max(starvationScore,
                                Math.max(vectorDegraded ? 0.55d : 0.0d,
                                        Math.max(silentFailure ? 0.65d : 0.0d,
                                                Math.max(ablationPresent ? 0.60d : 0.0d, blackboxRisk)))))));

        String primaryCause = "";
        if (ablationPresent) {
            primaryCause = ablationCause;
        } else if (blackboxRisk > 0.0d && !"none".equalsIgnoreCase(blackboxFailure)) {
            primaryCause = blackboxFailure;
        } else if (providerDisabled) {
            primaryCause = "provider_disabled";
        } else if (afterFilterStarved) {
            primaryCause = "after_filter_starvation";
        } else if (explicitStarved || zeroResults) {
            primaryCause = "web_starvation";
        } else if (vectorDegraded) {
            primaryCause = "vector_degraded";
        } else if (silentFailure) {
            primaryCause = "silent_failure";
        } else if (webErrorRate > 0.0d) {
            primaryCause = "web_error_rate";
        }

        String patternId = "";
        if (patternIdEnabled && !trace.isEmpty()) {
            try {
                patternId = Long.toUnsignedString(RawSlotExtractor.patternIdFromTrace(trace));
            } catch (Throwable ignore) {
                traceFailure("traceFailureSignals.patternId", ignore);
                patternId = "";
            }
        }

        return new TraceFailureSignals(
                starvationScore,
                retrievalFailureRate,
                safeCause(primaryCause),
                patternId,
                providerDisabled,
                afterFilterStarved,
                vectorDegraded,
                silentFailure,
                ablationPresent);
    }

    private void refreshBlackbox(String where) {
        try {
            RagFailureBlackboxService service = blackboxProvider == null ? null : blackboxProvider.getIfAvailable();
            if (service != null) {
                service.currentOrRefresh(where);
            }
        } catch (Throwable ignore) {
            traceFailure("blackbox.refresh", ignore);
        }
    }

    private static double blackboxEffectiveRisk(Map<String, Object> trace) {
        return clamp01(Math.max(
                toDouble(trace == null ? null : trace.get("blackbox.risk.riskScore")),
                toDouble(trace == null ? null : trace.get("blackbox.risk.priorityScore"))));
    }

    private static boolean afterFilterStarved(Map<String, Object> trace) {
        return positive(trace, "web.naver.filter.rawCount") && zeroPresent(trace, "web.naver.afterFilterCount")
                || positive(trace, "web.naver.returnedCount") && zeroPresent(trace, "web.naver.afterFilterCount")
                || positive(trace, "web.brave.returnedCount") && zeroPresent(trace, "web.brave.afterFilterCount")
                || positive(trace, "web.serpapi.returnedCount") && zeroPresent(trace, "web.serpapi.afterFilterCount")
                || positive(trace, "web.tavily.returnedCount") && zeroPresent(trace, "web.tavily.afterFilterCount")
                || positive(trace, "rag.returnedCount") && zeroPresent(trace, "rag.afterFilterCount");
    }

    private static boolean anyTruthy(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (truthy(trace.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s)
                || "1".equals(s)
                || "yes".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s);
    }

    private static boolean listLikePresent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Iterable<?> it) {
            return it.iterator().hasNext();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        String s = String.valueOf(value).trim();
        return !s.isBlank() && !"[]".equals(s) && !"{}".equals(s) && !"null".equalsIgnoreCase(s);
    }

    private static boolean positive(Map<String, Object> trace, String key) {
        return trace != null && toLong(trace.get(key)) > 0L;
    }

    private static boolean zeroPresent(Map<String, Object> trace, String key) {
        return trace != null && trace.containsKey(key) && toLong(trace.get(key)) <= 0L;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceFailure("parse.long", new NumberFormatException("non-finite"));
                return 0L;
            }
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            traceFailure("parse.long", e);
            return 0L;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (!Double.isFinite(numeric)) {
                traceFailure("parse.double", new NumberFormatException("non-finite"));
                return 0.0d;
            }
            return numeric;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            traceFailure("parse.double", e);
            return 0.0d;
        }
    }

    private static String firstAblationCause(Object value) {
        if (value instanceof Iterable<?> rows) {
            for (Object row : rows) {
                String cause = ablationCauseFromRow(row);
                if (!cause.isBlank()) {
                    return cause;
                }
            }
            return "";
        }
        return ablationCauseFromRow(value);
    }

    private static String ablationCauseFromRow(Object row) {
        if (row instanceof Map<?, ?> map) {
            Object factor = map.get("factor");
            if (factor == null) {
                factor = map.get("stage");
            }
            if (factor == null) {
                factor = map.get("name");
            }
            return safeCause(factor == null ? "" : String.valueOf(factor));
        }
        if (row == null) {
            return "";
        }
        return "ablation_signal";
    }

    private static String safeCause(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim();
        if (s.isBlank()) {
            return "";
        }
        s = s.replaceAll("[^A-Za-z0-9_.:-]+", "_");
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s;
    }

    private void traceRisk(ExtremeZRiskSignal risk) {
        if (risk == null) {
            return;
        }
        trace("extremez.risk.gateEnabled", risk.riskGateEnabled());
        trace("extremez.risk.scarcityScore", risk.scarcityScore());
        trace("extremez.risk.contradictionMean", risk.contradictionMean());
        trace("extremez.risk.contradictionUnavailable", risk.contradictionUnavailable());
        trace("extremez.risk.errorRate", risk.errorRate());
        trace("extremez.risk.starvationScore", risk.starvationScore());
        trace("extremez.risk.retrievalFailureRate", risk.retrievalFailureRate());
        trace("extremez.risk.primaryCause", risk.primaryCause());
        trace("extremez.risk.patternId", risk.patternId());
        trace("extremez.risk.weightedScore", risk.weightedScore());
        trace("extremez.risk.score", risk.score());
        trace("extremez.risk.threshold", risk.riskScoreThreshold());
        trace("extremez.risk.trigger", risk.trigger());
        trace("extremez.risk.reason", risk.reason());
    }

    private void emitRiskEvent(ExtremeZRiskSignal risk, boolean activated, int baseCount, int extraCount) {
        if (risk == null || !risk.debugEventsEnabled()) {
            return;
        }
        if (!activated && risk.score() <= 0.0d) {
            return;
        }
        DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            String safeReason = SafeRedactor.traceLabelOrFallback(risk.reason(), "unknown");
            String eventFingerprint = "extremez.risk." + safeReason;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", "extremez");
            data.put("enabled", true);
            data.put("activated", activated);
            data.put("queryHash", TraceStore.get("extremez.query.hash"));
            data.put("baseCount", baseCount);
            data.put("extraCount", extraCount);
            data.put("minBaseDocs", risk.minBaseDocs());
            data.put("scarcityScore", risk.scarcityScore());
            data.put("contradictionMean", risk.contradictionMean());
            data.put("contradictionUnavailable", risk.contradictionUnavailable());
            data.put("errorRate", risk.errorRate());
            data.put("starvationScore", risk.starvationScore());
            data.put("retrievalFailureRate", risk.retrievalFailureRate());
            data.put("primaryCause", risk.primaryCause());
            data.put("patternId", risk.patternId());
            data.put("riskScore", risk.score());
            data.put("disabledReason", activated ? "" : safeReason);
            data.put("failureClass", activated ? "extremez-activated" : "extremez-skip");
            store.emit(
                    DebugProbeType.ORCHESTRATION,
                    activated ? DebugEventLevel.INFO : DebugEventLevel.DEBUG,
                    eventFingerprint,
                    activated ? "ExtremeZ risk gate activated" : "ExtremeZ risk gate skipped",
                    "ExtremeZBurstAspect",
                    data,
                    null);
        } catch (Throwable ignore) {
            traceFailure("riskEvent.emit", ignore);
        }
    }

    private void recordCfvmFailurePattern(ExtremeZRiskSignal risk, Query originalQuery) {
        if (risk == null || risk.primaryCause() == null || risk.primaryCause().isBlank()) {
            return;
        }
        CfvmFailureRecorder recorder;
        try {
            recorder = cfvmFailureRecorderProvider == null ? null : cfvmFailureRecorderProvider.getIfAvailable();
        } catch (org.springframework.beans.BeansException | IllegalStateException ex) {
            traceFailure("cfvm.recorder.provider", ex);
            trace("cfvm.recorder.available", false);
            return;
        }
        if (recorder == null) {
            trace("cfvm.recorder.available", false);
            return;
        }
        trace("cfvm.recorder.available", true);
        Map<String, Object> cfvmTrace = new LinkedHashMap<>(TraceStore.getAll());
        cfvmTrace.put(CfvmFailureRecorder.RAG_ORIGIN_MARKER, true);
        recorder.record("extremez", risk.primaryCause(), "ExtremeZBurstAspect",
                sessionId(originalQuery), cfvmTrace);
    }

    private void traceSkip(String reason, int baseCount, int extraCount) {
        trace("extremez.skipReason", reason);
        trace("extremez.bypassReason", reason);
        trace("extremez.activation.reason", reason);
        trace("extremez.base.count", baseCount);
        trace("extremez.extra.count", extraCount);
        trace("extremez.activated", false);
    }

    private static void traceFailure(String stage, Throwable e) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String err = errorType(stage, e);
        log.debug("[ExtremeZ] stage={} err={}", safeStage, err);
        try {
            TraceStore.inc("extremez." + safeStage + ".failed");
            TraceStore.put("extremez." + safeStage + ".error", err);
        } catch (Throwable ignored) {
            log.debug("[ExtremeZ] failure telemetry skipped stage={} err={}", safeStage, errorType(ignored));
        }
    }

    private static String errorType(String stage, Throwable e) {
        if (e instanceof NumberFormatException && isNumericParseStage(stage)) {
            return "invalid_number";
        }
        return errorType(e);
    }

    private static boolean isNumericParseStage(String stage) {
        return "parse.long".equals(stage)
                || "parse.double".equals(stage)
                || "plan.double".equals(stage);
    }

    private static String errorType(Throwable e) {
        return e == null ? "Throwable" : SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "Throwable");
    }

    private static String planPrimaryMode(GuardContext gctx) {
        Object value = gctx == null ? null : gctx.getPlanOverride("executionPlan.primaryMode");
        if (value == null) {
            value = gctx == null ? null : gctx.getPlanOverride("routing.executionPlan.primaryMode");
        }
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isSuppressedByPrimaryMode(String primaryMode, String currentMode) {
        if (primaryMode == null || primaryMode.isBlank() || "NORMAL".equals(primaryMode)) {
            return false;
        }
        return !currentMode.equals(primaryMode);
    }

    private void trace(String key, Object value) {
        try {
            TraceStore.put(key, safeTraceValue(key, value));
        } catch (Throwable ignore) {
            log.debug("[ExtremeZ] trace skipped key={} err={}",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"), errorType(ignore));
        }
    }

    private Object safeTraceValue(String key, Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String s) {
            if (key != null && key.toLowerCase(Locale.ROOT).contains("reason")) {
                return SafeRedactor.traceLabelOrFallback(s, "");
            }
            return SafeRedactor.safeMessage(s, 240);
        }
        return SafeRedactor.diagnosticValue(key, value, 240);
    }

    private double traceDouble(String key, double fallback) {
        try {
            Object value = TraceStore.get(key);
            return value == null ? fallback : toDouble(value);
        } catch (Throwable ignore) {
            traceFailure("trace.double", ignore);
            return fallback;
        }
    }

    private String safeHash(String raw) {
        String hash = SafeRedactor.hash12(raw);
        return hash == null ? "" : hash;
    }

    private static String sessionId(Query query) {
        Object value = QueryUtils.sessionId(query);
        return value == null ? "" : String.valueOf(value);
    }

    private double planDouble(GuardContext gctx, String key, double defaultValue) {
        if (gctx == null || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object v = gctx.getPlanOverride(key);
        if (v == null && key.startsWith("extremeZ.")) {
            v = gctx.getPlanOverride("extremez." + key.substring("extremeZ.".length()));
        }
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1.0d : 0.0d;
        }
        String s = String.valueOf(v).trim();
        if (s.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            traceFailure("plan.double", e);
            return defaultValue;
        }
    }

    private static double normalizedAboveThreshold(double value, double threshold) {
        double th = clamp01(threshold);
        if (value <= th) {
            return 0.0d;
        }
        double denom = Math.max(0.000001d, 1.0d - th);
        return clamp01((value - th) / denom);
    }

    private static double nonNegative(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, value);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private record ContradictionResult(double mean, boolean unavailable) {
    }

    private record ExtremeZRiskSignal(
            int baseCount,
            int minBaseDocs,
            boolean riskGateEnabled,
            double scarcityScore,
            double contradictionMean,
            boolean contradictionUnavailable,
            double errorRate,
            double starvationScore,
            double retrievalFailureRate,
            String primaryCause,
            String patternId,
            double weightedScore,
            double score,
            boolean sparseTrigger,
            boolean contradictionTrigger,
            boolean errorRateTrigger,
            boolean starvationTrigger,
            boolean retrievalFailureTrigger,
            double riskScoreThreshold,
            boolean trigger,
            String reason,
            boolean debugEventsEnabled) {
    }

    private record TraceFailureSignals(
            double starvationScore,
            double retrievalFailureRate,
            String primaryCause,
            String patternId,
            boolean providerDisabled,
            boolean afterFilterStarved,
            boolean vectorDegraded,
            boolean silentFailure,
            boolean ablationPresent) {
    }
}
