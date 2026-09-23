package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import com.example.lms.nova.burst.QueryBurstExpander;
import com.example.lms.prompt.pose.PromptPoseTrace;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.budget.RetrievalBudgetDecision;
import com.example.lms.service.rag.budget.RetrievalBudgetGovernor;
import com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Nova Overlay: Brave 모드(aggressive plan)에서 검색 쿼리 후보를 QueryBurst로 확장합니다.
 *
 * 연결 포인트:
 * - ChatWorkflow는 웹검색/하이브리드 리트리벌 전 단계에서 SmartQueryPlanner.plan(...)을 호출합니다.
 * - 이 AOP는 plan(...)의 반환값(List<String>)을 가로채, Brave(aggressive)일 때
 * QueryBurstExpander로 추가 쿼리를 생성하고 QueryHygieneFilter로 정리합니다.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class BraveQueryBurstAspect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BraveQueryBurstAspect.class);
    private final Environment env;
    private final QueryBurstExpander expander = new QueryBurstExpander();
    private final RuleBasedQueryAugmenter augmenter;
    private final AnchorNarrower anchorNarrower;
    private final OfflineTextureSnapshotLoader offlineTextureLoader;
    private final RetrievalBudgetGovernor budgetGovernor;

    public BraveQueryBurstAspect(Environment env, RuleBasedQueryAugmenter augmenter) {
        this(env, augmenter, null, null, null);
    }

    public BraveQueryBurstAspect(Environment env,
                                 RuleBasedQueryAugmenter augmenter,
                                 AnchorNarrower anchorNarrower,
                                 OfflineTextureSnapshotLoader offlineTextureLoader,
                                 RetrievalBudgetGovernor budgetGovernor) {
        this.env = env;
        this.augmenter = augmenter;
        this.anchorNarrower = anchorNarrower;
        this.offlineTextureLoader = offlineTextureLoader;
        this.budgetGovernor = budgetGovernor;
    }

    @Around("execution(* com.example.lms.search.SmartQueryPlanner.plan(..))")
    public Object expandQueriesInBraveMode(ProceedingJoinPoint pjp) throws Throwable {
        // If a pipeline already executed, avoid double-expansion.
        if (TraceStore.get("orch.pipeline.executed") != null) {
            return pjp.proceed();
        }

        GuardContext ctx = GuardContextHolder.getOrDefault();
        int overrideCount = (ctx == null) ? 0 : ctx.planInt("expand.queryBurst.count", 0);
        Integer promptPoseCap = PromptPoseTrace.queryBurstCap();
        List<String> promptPoseSeedHashes = PromptPoseTrace.queryBurstSeedHashes();
        boolean enabled = (ctx != null && (ctx.isAggressivePlan() || overrideCount > 0))
                || promptPoseCap != null;

        if (!enabled) {
            return pjp.proceed();
        }

        // We still run deterministic augmentation even when aux-down/strike.
        Object pd = TraceStore.get("web.down.partial");
        boolean partialDown = Boolean.TRUE.equals(TraceStore.get("orch.webPartialDown"))
                || Boolean.TRUE.equals(pd)
                || (pd instanceof String s && ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)));
        boolean allowBurst = (ctx == null || (!ctx.isStrikeMode()
                && !ctx.isAuxDown()
                && !ctx.isWebRateLimited()
                && !ctx.isCompressionMode()))
                && !partialDown;
        if (partialDown) {
            TraceStore.put("web.brave.queryBurst.disabled.reason", "partialDown");
            // KPI key: disable expansion when web is not healthy (partialDown).
            TraceStore.putIfAbsent("expand.queryBurst.skipped", "web_not_healthy");
            TraceStore.putIfAbsent("expand.queryBurst.skipped.reason", "partial_down");
        }

        Object base;
        if (overrideCount > 0) {
            // Also override the SmartQueryPlanner maxBranches argument when present.
            Object[] args = pjp.getArgs();
            Object[] newArgs = (args == null) ? null : args.clone();
            if (newArgs != null && newArgs.length >= 3 && newArgs[2] instanceof Number) {
                newArgs[2] = overrideCount;
            }
            base = (newArgs == null) ? pjp.proceed() : pjp.proceed(newArgs);
        } else {
            base = pjp.proceed();
        }
        if (!(base instanceof List<?> raw)) {
            return base;
        }

        List<String> baseQs = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof String s && !s.isBlank())
                baseQs.add(s);
        }

        // Planner might return empty - keep original question as seed.
        String seedRaw = seedFromArgsOrFallback(pjp.getArgs(), baseQs);
        if ((baseQs == null || baseQs.isEmpty()) && seedRaw != null && !seedRaw.isBlank()) {
            baseQs = List.of(seedRaw);
        }

        // Deterministic canonicalization + small augmentations (works even when helper
        // is down).
        LinkedHashSet<String> deterministic = new LinkedHashSet<>();
        if (augmenter != null) {
            for (String q : baseQs) {
                RuleBasedQueryAugmenter.Augment aug = augmenter.augment(q);
                if (aug != null && aug.queries() != null && !aug.queries().isEmpty()) {
                    deterministic.addAll(aug.queries());
                } else if (q != null && !q.isBlank()) {
                    deterministic.add(q);
                }
            }
        } else {
            deterministic.addAll(baseQs);
        }

        // Config (plan override can drive maxBranches/cap)
        int defaultMin = env.getProperty("nova.orch.brave-query-burst.min", Integer.class, 9);
        int defaultMax = env.getProperty("nova.orch.brave-query-burst.max", Integer.class, 18);
        int defaultCap = env.getProperty("nova.orch.brave-query-burst.cap", Integer.class, defaultMax);
        int max = overrideCount > 0 ? overrideCount : defaultMax;
        int min = overrideCount > 0 ? Math.min(defaultMin, max) : defaultMin;
        int cap = overrideCount > 0 ? overrideCount : defaultCap;
        if (promptPoseCap != null) {
            int poseCap = Math.max(1, Math.min(18, promptPoseCap));
            max = Math.min(max, poseCap);
            cap = Math.min(cap, poseCap);
            try {
                TraceStore.put("web.brave.queryBurst.promptPose.capApplied", poseCap);
                TraceStore.put("web.brave.queryBurst.promptPose.seedHashCount", promptPoseSeedHashes.size());
            } catch (Throwable ignore) {
                traceSuppressed("trace.promptPose");
            }
        }

        // Safety clamps
        if (max < 1)
            max = 1;
        if (min < 1)
            min = 1;
        if (min > max)
            min = max;
        if (cap < 1)
            cap = 1;
        if (cap > 64)
            cap = 64;

        Double simCfg = env.getProperty("nova.orch.brave-query-burst.similarity-threshold", Double.class);
        if (simCfg == null) {
            // Backward-compat: older configs used "sim-threshold".
            simCfg = env.getProperty("nova.orch.brave-query-burst.sim-threshold", Double.class);
        }
        double sim = (simCfg != null) ? simCfg : 0.80d;

        String seed = seedRaw;
        if (augmenter != null && seed != null && !seed.isBlank()) {
            try {
                RuleBasedQueryAugmenter.Augment seedAug = augmenter.augment(seed);
                if (seedAug != null && seedAug.canonical() != null && !seedAug.canonical().isBlank()) {
                    seed = seedAug.canonical();
                }
            } catch (RuntimeException failure) {
                TraceStore.put("web.brave.queryBurst.seedAugment.failed", true);
                TraceStore.put("web.brave.queryBurst.seedAugment.failureClass",
                        SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
            }
        }

        AnchorNarrowingResult anchorResult = null;
        OfflineTextureSnapshotLoader.TextureLookup textureLookup =
                OfflineTextureSnapshotLoader.TextureLookup.disabled("loader_unavailable");
        boolean freshnessQuery = OfflineTextureSnapshotLoader.looksFreshnessQuery(seedRaw);
        boolean anchorEnabled = env.getProperty("rag.anchor.enabled", Boolean.class, true);
        int maxAnchors = env.getProperty("rag.anchor.max-anchors", Integer.class, 3);
        double maxDriftScore = env.getProperty("rag.anchor.max-drift-score", Double.class, 0.65d);
        if (anchorEnabled && anchorNarrower != null && seed != null && !seed.isBlank()) {
            anchorResult = anchorNarrower.narrow(seed, baseQs, maxAnchors, maxDriftScore);
        } else {
            try {
                TraceStore.put("rag.anchor.enabled", false);
                TraceStore.put("rag.anchor.reason", anchorNarrower == null ? "anchor_narrower_missing" : "disabled");
            } catch (Throwable ignore) {
                traceSuppressed("trace.anchorDisabled");
            }
        }
        if (offlineTextureLoader != null && anchorResult != null) {
            textureLookup = offlineTextureLoader.lookup(seed, anchorResult.anchors(), freshnessQuery);
        }
        if (budgetGovernor != null && env.getProperty("rag.budget.enabled", Boolean.class, true)) {
            RetrievalBudgetDecision decision = budgetGovernor.decide(
                    anchorResult,
                    textureLookup.hitRate(),
                    freshnessQuery,
                    cap,
                    cap,
                    Math.max(1, cap / 2),
                    max);
            max = Math.min(max, decision.queryBurstCount());
            min = Math.min(min, Math.max(1, max));
            cap = Math.min(cap, Math.max(1, decision.queryBurstCount() + deterministic.size()));
            try {
                TraceStore.put("web.brave.queryBurst.budget.reason", SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown"));
                TraceStore.put("web.brave.queryBurst.budget.max", max);
            } catch (Throwable ignore) {
                traceSuppressed("trace.budget");
            }
        }

        List<String> burst = (!allowBurst || seed == null || seed.isBlank())
                ? List.of()
                : expander.expand(seed, min, max);

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(deterministic);
        merged.addAll(burst);

        List<String> out = QueryHygieneFilter.sanitize(new ArrayList<>(merged), cap, sim);
        int beforeAnchorFilter = out.size();
        if (anchorEnabled && anchorNarrower != null && anchorResult != null && !out.isEmpty()) {
            List<String> filtered = anchorNarrower.filterCandidates(seed, out, anchorResult, maxDriftScore);
            if (!filtered.isEmpty()) {
                out = filtered;
            }
            try {
                int dropped = Math.max(0, beforeAnchorFilter - out.size());
                TraceStore.put("web.brave.queryBurst.anchorDroppedCount", dropped);
                TraceStore.put("rag.metrics.burstReductionRate",
                        beforeAnchorFilter == 0 ? 0.0d : Math.max(0.0d, dropped / (double) beforeAnchorFilter));
            } catch (Throwable ignore) {
                traceSuppressed("trace.anchorMetrics");
            }
        }

        if (allowBurst && out.size() != baseQs.size()) {
            log.debug("[nova][query-burst] brave expand {} -> {} (seedHash12={})", baseQs.size(), out.size(),
                    SafeRedactor.hash12(seed));
        }

        return out;
    }

    private static String seedFromArgsOrFallback(Object[] args, List<String> baseQs) {
        // SmartQueryPlanner.plan(String question, @Nullable String modelDraft, int
        // maxQueries)
        if (args != null && args.length >= 1 && args[0] instanceof String s && !s.isBlank()) {
            return s;
        }
        return baseQs.isEmpty() ? null : baseQs.get(0);
    }

    private static void traceSuppressed(String stage) {
        log.debug("[nova][query-burst] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }

}
