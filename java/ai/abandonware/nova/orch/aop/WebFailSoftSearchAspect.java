package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import ai.abandonware.nova.orch.web.WebFailSoftDomainStageReportService;
import ai.abandonware.nova.orch.web.WebFailSoftStage;
import ai.abandonware.nova.orch.web.WebSnippet;
import ai.abandonware.nova.orch.trace.OrchDigest;
import ai.abandonware.nova.orch.trace.TraceEventCanonicalizer;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.domain.enums.RerankSourceCredibility;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Nova Overlay: stage-based WEB fail-soft.
 *
 * <p>
 * Motivation: when strict filters starve, some pipelines fall back to "raw"
 * results
 * which can reintroduce finance/loan spam for TECH/API queries. This aspect
 * post-processes
 * the snippet list into an
 * OFFICIAL→DOCS→DEV_COMMUNITY→PROFILEBOOST→NOFILTER_SAFE order,
 * while enforcing TECH spam blocking and host-diverse top-up for minCitations.
 * </p>
 *
 * <p>
 * Design: fail-soft; never throws; if disabled, behaves as no-op.
 * </p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class WebFailSoftSearchAspect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebFailSoftSearchAspect.class);

    private static final org.slf4j.Logger soakKpiLog = org.slf4j.LoggerFactory.getLogger("SOAK_WEB_KPI");
    private static final com.fasterxml.jackson.databind.ObjectMapper soakKpiOm = new com.fasterxml.jackson.databind.ObjectMapper();

    // ---- Micrometer metrics (dashboard-friendly, low-cardinality) ----
    // NOTE: Prometheus will typically expose counters as <name>_total with dots
    // normalized to underscores.
    private static final String METRIC_RUN = "nova.orch.web_failsoft.run";
    private static final String METRIC_OUT_ZERO = "nova.orch.web_failsoft.out_zero";
    private static final String METRIC_STARVATION_FALLBACK = "nova.orch.web_failsoft.starvation_fallback";
    private static final String METRIC_STARVATION_FALLBACK_SKIPPED = "nova.orch.web_failsoft.starvation_fallback_skipped";
    private static final String METRIC_STAGE_SELECTED = "nova.orch.web_failsoft.stage_selected";
    private static final String METRIC_CANDIDATE_DROP = "nova.orch.web_failsoft.candidate_drop";
    private static final String METRIC_CANDIDATE_SCORE = "nova.orch.web_failsoft.candidate_score";

    private final NovaWebFailSoftProperties props;
    private final RuleBasedQueryAugmenter augmenter;

    @Nullable
    private final DomainProfileLoader domainProfileLoader;
    @Nullable
    private final AuthorityScorer authorityScorer;
    @Nullable
    private final WebFailSoftDomainStageReportService domainStageReport;

    @Nullable
    private final FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    @Nullable
    private final NightmareBreaker nightmareBreaker;

    /** Optional – may be null in tests or minimal deployments. */
    @Nullable
    private final MeterRegistry meterRegistry;

    /** Optional – may be null in tests or minimal deployments. */
    @Nullable
    private final DebugEventStore debugEventStore;

    public WebFailSoftSearchAspect(
            NovaWebFailSoftProperties props,
            RuleBasedQueryAugmenter augmenter,
            @Nullable DomainProfileLoader domainProfileLoader,
            @Nullable AuthorityScorer authorityScorer,
            @Nullable WebFailSoftDomainStageReportService domainStageReport,
            @Nullable FaultMaskingLayerMonitor faultMaskingLayerMonitor,
            @Nullable NightmareBreaker nightmareBreaker,
            @Nullable DebugEventStore debugEventStore,
            @Nullable MeterRegistry meterRegistry) {
        this.props = Objects.requireNonNull(props);
        this.augmenter = Objects.requireNonNull(augmenter);
        this.domainProfileLoader = domainProfileLoader;
        this.authorityScorer = authorityScorer;
        this.domainStageReport = domainStageReport;
        this.faultMaskingLayerMonitor = faultMaskingLayerMonitor;
        this.nightmareBreaker = nightmareBreaker;
        this.debugEventStore = debugEventStore;
        this.meterRegistry = meterRegistry;
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.search(..))")
    public Object aroundSearch(ProceedingJoinPoint pjp) throws Throwable {
        if (!props.isEnabled()) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof Number)) {
            return pjp.proceed();
        }

        String query = ((String) args[0]);
        int topK = ((Number) args[1]).intValue();
        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.get();
        } catch (Throwable t) {
            // Fail-soft: missing guard classes should not 500 the request.
            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("guard_context", t,
                        "GuardContextHolder missing in WebFailSoftSearchAspect");
            }
        }
        RuleBasedQueryAugmenter.Augment aug = augmenter.augment(query);
        String canonical = aug.canonical();
        if (canonical == null || canonical.isBlank()) {
            return pjp.proceed();
        }

        List<String> raw = castList(pjp.proceed(new Object[] { canonical, topK }));
        List<String> staged = applyStages(raw, ctx, aug, topK, canonical);

        // [UAW] If web is hard-down (both providers skipped / effective down), avoid
        // any extra web calls
        // inside this aspect (they will just spin on merged=0).
        boolean webHardDown = false;
        try {
            webHardDown = (ctx != null && ctx.isWebRateLimited())
                    || Boolean.TRUE.equals(TraceStore.get("web.hardDown"))
                    || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                    || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"))
                    || TraceStore.getLong("web.await.skipped.count") >= 2;
            if (webHardDown) {
                TraceStore.put("web.failsoft.extraCalls.skipped", true);
                TraceStore.put("web.failsoft.extraCalls.skipped.reason", "webHardDown");
            }
        } catch (Exception ignore) {
            // best-effort
        }

        // Min-citations rescue (officialOnly): when we are below minCitations, try a
        // few extra searches to
        // pull additional OFFICIAL/DOCS snippets and prepend only the deficit. This
        // keeps officialOnly from
        // relaxing to NOFILTER_SAFE due to BELOW_MIN_CITATIONS.
        try {
            boolean officialOnly = (ctx != null && ctx.isOfficialOnly())
                    || Boolean.TRUE.equals(TraceStore.get("web.failsoft.officialOnly"));
            long needed = TraceStore.getLong("web.failsoft.minCitationsNeeded");
            long citeable = TraceStore.getLong("web.failsoft.citeableCount");
            int deficit = (int) Math.max(0L, needed - citeable);

            if (!webHardDown
                    && officialOnly
                    && staged != null && !staged.isEmpty()
                    && deficit > 0
                    && props.isAllowExtraSearchCalls()
                    && props.getMaxExtraSearchCalls() > 0
                    && aug != null && aug.queries() != null
                    && !Boolean.TRUE.equals(TraceStore.get("web.failsoft.minCitationsRescue.attempted"))) {

                TraceStore.put("web.failsoft.minCitationsRescue.attempted", true);
                TraceStore.put("web.failsoft.minCitationsRescue.deficit", deficit);

                // Prefer "official/docs" flavored queries first.
                List<String> qCandidates = new ArrayList<>();
                for (String q2 : aug.queries()) {
                    if (q2 == null || q2.isBlank())
                        continue;
                    if (q2.equals(canonical))
                        continue;
                    qCandidates.add(q2);
                }
                qCandidates.sort(
                        (a, b) -> Integer.compare(scoreOfficialDocsRescueQuery(b), scoreOfficialDocsRescueQuery(a)));

                int callBudget = Math.max(0, Math.min(props.getMaxExtraSearchCalls(), 4));

                try {
                    TraceStore.put("web.failsoft.minCitationsRescue.budget", callBudget);
                    TraceStore.put("web.failsoft.minCitationsRescue.candidates",
                            capList(new ArrayList<>(qCandidates), 5));
                } catch (Exception ignore) {
                }

                Set<String> existing = new HashSet<>(staged);
                List<String> rescueSnippets = new ArrayList<>();

                int calls = 0;
                for (String q2 : qCandidates) {
                    if (calls++ >= callBudget)
                        break;
                    if (rescueSnippets.size() >= deficit)
                        break;
                    try {
                        TraceStore.put("web.failsoft.minCitationsRescue.rescueQuery", q2);
                        List<String> raw2 = castList(pjp.proceed(new Object[] { q2, topK }));
                        List<String> staged2 = applyStages(raw2, ctx, aug, topK, q2);
                        if (staged2 == null || staged2.isEmpty())
                            continue;

                        for (String s : staged2) {
                            if (rescueSnippets.size() >= deficit)
                                break;
                            if (s == null)
                                continue;
                            String t = s.trim();
                            if (!(t.startsWith("[WEB:OFFICIAL") || t.startsWith("[WEB:DOCS")))
                                continue;
                            if (existing.contains(s))
                                continue;
                            if (rescueSnippets.contains(s))
                                continue;
                            rescueSnippets.add(s);
                        }
                    } catch (Exception ignoreOne) {
                        // fail-soft; try next query
                    }
                }

                int inserted = 0;
                if (!rescueSnippets.isEmpty()) {
                    LinkedHashSet<String> merged = new LinkedHashSet<>();
                    merged.addAll(rescueSnippets);
                    merged.addAll(staged);

                    List<String> mergedList = new ArrayList<>(merged);
                    if (mergedList.size() > topK) {
                        mergedList = new ArrayList<>(mergedList.subList(0, topK));
                    }
                    staged = mergedList;
                    inserted = rescueSnippets.size();
                }

                TraceStore.put("web.failsoft.minCitationsRescue.insertedCount", inserted);
                if (inserted > 0) {
                    int after = 0;
                    for (String s : staged) {
                        if (countsTowardMinCitationsFromTaggedOutput(s)) {
                            after++;
                        }
                    }
                    TraceStore.put("web.failsoft.minCitationsRescue.citeableCount.after", after);
                    TraceStore.put("web.failsoft.minCitationsRescue.satisfied", after >= needed);
                }
            }
        } catch (Exception ignore) {
            // best-effort
        }

        // Quality gate rescue: when officialOnly starvation fallback produced mostly
        // UNVERIFIED snippets
        // and we had no OFFICIAL/DOCS candidate in the current buckets, optionally
        // allow one more
        // deterministic search using augmented/rescue queries. This increases the
        // success rate of
        // "force OFFICIAL/DOCS" without depending on LLM query transforms.
        try {
            Object needRescue = TraceStore.get("web.failsoft.starvationFallback.qualityGate.needRescueExtraSearch");
            if (Boolean.TRUE.equals(needRescue)
                    && !webHardDown
                    && props.isAllowExtraSearchCalls()
                    && aug.queries() != null
                    && props.getMaxExtraSearchCalls() > 0) {

                // Prefer "official/docs" flavored queries first.
                List<String> qCandidates = new ArrayList<>();
                for (String q2 : aug.queries()) {
                    if (q2 == null || q2.isBlank())
                        continue;
                    if (q2.equals(canonical))
                        continue;
                    qCandidates.add(q2);
                }
                qCandidates.sort(
                        (a, b) -> Integer.compare(scoreOfficialDocsRescueQuery(b), scoreOfficialDocsRescueQuery(a)));

                int callBudget = Math.max(0, Math.min(props.getMaxExtraSearchCalls(), 4));

                // Trace candidates so we can debug why rescue did/did not work.
                try {
                    TraceStore.put("web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempted", true);
                    TraceStore.put("web.failsoft.starvationFallback.qualityGate.rescueInserted", false);
                    TraceStore.put("web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.candidates",
                            capList(new ArrayList<>(qCandidates), 5));
                    TraceStore.put("web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.budget", callBudget);
                } catch (Exception ignore) {
                }

                int calls = 0;
                for (String q2 : qCandidates) {
                    if (calls++ >= callBudget)
                        break;
                    try {
                        long t0 = System.nanoTime();
                        List<String> raw2 = castList(pjp.proceed(new Object[] { q2, topK }));
                        List<String> staged2 = applyStages(raw2, ctx, aug, topK, q2);
                        String rescueSnippet = firstOfficialOrDocsSnippet(staged2);
                        try {
                            Map<String, Object> ev = new LinkedHashMap<>();
                            ev.put("seq", TraceStore.nextSequence(
                                    "web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempts"));
                            ev.put("executedQuery", safeTrim(canonical, 240));
                            ev.put("rescueQuery", safeTrim(q2, 240));
                            ev.put("outCount", staged2 == null ? 0 : staged2.size());
                            ev.put("foundOfficialOrDocs", rescueSnippet != null && !rescueSnippet.isBlank());
                            ev.put("tookMs", Math.max(0L, (System.nanoTime() - t0) / 1_000_000L));
                            Object stageCounts = TraceStore.get("web.failsoft.stageCountsSelected");
                            if (stageCounts != null) {
                                ev.put("stageCountsSelected", stageCounts);
                            }
                            TraceStore.append("web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempts",
                                    ev);
                        } catch (Exception ignore) {
                            // fail-soft
                        }
                        if (rescueSnippet != null && !rescueSnippet.isBlank()) {
                            LinkedHashSet<String> merged = new LinkedHashSet<>();
                            merged.add(rescueSnippet);
                            if (staged != null)
                                merged.addAll(staged);
                            List<String> mergedFinal = new ArrayList<>(merged);
                            if (mergedFinal.size() > topK) {
                                mergedFinal = mergedFinal.subList(0, topK);
                            }
                            staged = mergedFinal;

                            TraceStore.put("web.failsoft.starvationFallback.qualityGate.rescueExtraQuery", q2);
                            TraceStore.put("web.failsoft.starvationFallback.qualityGate.rescueInserted", true);

                            if (debugEventStore != null) {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("note", "officialOnly quality gate rescue extra search");
                                data.put("executedQuery", safeTrim(canonical, 240));
                                data.put("rescueQuery", safeTrim(q2, 240));
                                data.put("outCount", staged == null ? 0 : staged.size());
                                debugEventStore.emit(
                                        DebugProbeType.WEB_SEARCH,
                                        DebugEventLevel.INFO,
                                        "web-failsoft.starvationFallback.qualityGate.rescueExtraSearch",
                                        "Rescue extra search inserted OFFICIAL/DOCS snippet",
                                        "WebFailSoftSearchAspect.aroundSearch",
                                        data,
                                        null);
                            }
                            break;
                        }
                    } catch (Exception e) {
                        // fail-soft
                        log.debug("[nova][web-failsoft] qualityGate rescue search failed: {}", e.toString());
                    }
                }
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        // Optional hard-guard: when enabled (typically in tests), fail if the quality
        // gate
        // triggered but we still could not produce an OFFICIAL/DOCS snippet.
        if (props.isOfficialOnlyStarvationFallbackQualityGateRequireForceInsert()) {
            Object triggered = TraceStore.get("web.failsoft.starvationFallback.qualityGate.triggered");
            if (Boolean.TRUE.equals(triggered) && firstOfficialOrDocsSnippet(staged) == null) {
                throw new IllegalStateException(
                        "[web-failsoft] qualityGate triggered but no OFFICIAL/DOCS snippet found (requireForceInsert=true)");
            }
        }

        // Deterministic extra calls if strict staging yielded nothing.
        if ((staged == null || staged.isEmpty())
                && !webHardDown
                && props.isAllowExtraSearchCalls()
                && aug.queries() != null
                && props.getMaxExtraSearchCalls() > 0) {
            int calls = 0;
            for (String q2 : aug.queries()) {
                if (q2 == null || q2.isBlank())
                    continue;
                if (q2.equals(canonical))
                    continue;
                if (calls++ >= props.getMaxExtraSearchCalls())
                    break;
                try {
                    long t0 = System.nanoTime();
                    List<String> raw2 = castList(pjp.proceed(new Object[] { q2, topK }));
                    List<String> staged2 = applyStages(raw2, ctx, aug, topK, q2);
                    if (staged2 != null && !staged2.isEmpty()) {
                        TraceStore.put("web.failsoft.extraQuery", q2);
                        summarizeAwaitEventsForTrace();
                        return staged2;
                    }
                } catch (Exception e) {
                    // fail-soft
                    log.debug("[nova][web-failsoft] extra search failed: {}", e.toString());
                }
            }
        }

        summarizeAwaitEventsForTrace();
        return staged;
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.searchWithTrace(..))")
    public Object aroundSearchWithTrace(ProceedingJoinPoint pjp) throws Throwable {
        if (!props.isEnabled()) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof Number)) {
            return pjp.proceed();
        }

        String query = ((String) args[0]);
        int topK = ((Number) args[1]).intValue();
        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.get();
        } catch (Throwable t) {
            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("guard-context", t,
                        "GuardContextHolder missing in WebFailSoftSearchAspect.aroundSearchWithTrace");
            } else {
                log.warn("[WebFailSoftSearchAspect] GuardContextHolder missing (searchWithTrace).", t);
            }
        }

        RuleBasedQueryAugmenter.Augment aug = augmenter.augment(query);
        String canonical = aug.canonical();
        if (canonical == null || canonical.isBlank()) {
            return pjp.proceed();
        }

        Object obj = pjp.proceed(new Object[] { canonical, topK });
        if (!(obj instanceof NaverSearchService.SearchResult res)) {
            return obj;
        }

        List<String> staged = applyStages(res.snippets(), ctx, aug, topK, canonical);
        if (res.trace() != null) {
            try {
                String dp = ctx == null ? null : stringOrNull(ctx.getDomainProfile());
                if ("GENERAL".equalsIgnoreCase(dp))
                    dp = null;
                res.trace().domainFilterEnabled = ctx != null && (ctx.isOfficialOnly() || dp != null);
                res.trace().keywordFilterEnabled = (aug.intent() == RuleBasedQueryAugmenter.Intent.TECH_API);
                res.trace().suffixApplied = "failsoft-stage:" + String.join(",", props.getStageOrder());
            } catch (Exception ignore) {
            }
        }
        summarizeAwaitEventsForTrace();
        return new NaverSearchService.SearchResult(staged, res.trace());
    }

    private List<String> applyStages(List<String> raw,
            @Nullable GuardContext ctx,
            RuleBasedQueryAugmenter.Augment aug,
            int topK,
            String executedQuery) {
        if (raw == null) {
            raw = List.of();
        }

        // Per-run debug meta (multiple searches in a single request can otherwise
        // clobber web.failsoft.* keys).
        final long runId = TraceStore.nextSequence("web.failsoft.run");
        final String canonicalQuery = (aug == null || aug.canonical() == null) ? "" : String.valueOf(aug.canonical());
        final String executedQuerySafe = (executedQuery == null) ? "" : String.valueOf(executedQuery);

        // Provider-stage input size (debug): distinguish "provider returned empty" vs
        // "filtered later (domain/stage/clamp)".
        final int rawInputCount = (raw == null) ? 0 : raw.size();
        int rawInputNonBlankCount = 0;
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    rawInputNonBlankCount++;
                }
            }
        }

        try {
            TraceStore.put("web.failsoft.runId.last", String.valueOf(runId));
            TraceStore.put("web.failsoft.rawInputCount", rawInputCount);
            TraceStore.put("web.failsoft.rawInputNonBlankCount", rawInputNonBlankCount);
            TraceStore.put("web.failsoft.rawInputCount.runId", String.valueOf(runId));

            if (!executedQuerySafe.isBlank()) {
                TraceStore.put("web.failsoft.executedQuery", executedQuerySafe);
            }

            // Per-run markers may otherwise leak across multiple searches within the same
            // request.
            // Clear them early to avoid confusing RCA (ex: fallback label showing up in a
            // later run).
            TraceStore.put("web.failsoft.starvationFallback", null);
            TraceStore.put("starvationFallback.trigger", null);
            TraceStore.put("web.failsoft.officialOnlyClamp.evidence", null);
            TraceStore.put("web.failsoft.officialOnlyClamp.exclusionSummary", null);
        } catch (Exception ignore) {
            // fail-soft
        }

        boolean officialOnly = ctx != null && ctx.isOfficialOnly();
        boolean highRisk = ctx != null && ctx.isHighRiskQuery();
        int minCitations = 0;
        boolean minCitationsDefaultUsed = false;
        try {
            Integer mc = (ctx == null) ? null : ctx.getMinCitations();
            if (mc == null) {
                minCitations = Math.max(0, props.getMinCitationsDefault());
                minCitationsDefaultUsed = (minCitations > 0);
            } else {
                minCitations = Math.max(0, mc);
            }
        } catch (Exception ignore) {
            minCitations = 0;
        }
        String whitelistProfile = (ctx == null) ? null : stringOrNull(ctx.getDomainProfile());
        if ("GENERAL".equalsIgnoreCase(whitelistProfile)) {
            whitelistProfile = null;
            try {
                TraceStore.put("web.failsoft.domainProfile.hatch", "GENERAL->null");
            } catch (Exception ignore) {
            }
        }
        try {
            TraceStore.put("web.failsoft.highRisk", highRisk);
            TraceStore.put("web.failsoft.minCitationsDefaultUsed", minCitationsDefaultUsed);
        } catch (Exception ignore) {
        }

        List<WebFailSoftStage> configuredOrder = parseStageOrder(props.getStageOrder());
        List<WebFailSoftStage> order = configuredOrder;

        // OfficialOnly clamp options (DEV_COMMUNITY include toggle)
        final boolean officialOnlyIncludeDevCommunity = props.isOfficialOnlyIncludeDevCommunity();
        try {
            TraceStore.put("web.failsoft.officialOnlyClamp.includeDevCommunity", officialOnlyIncludeDevCommunity);
        } catch (Exception ignore) {
            // fail-soft
        }

        // If the plan requires official-only evidence, prefer
        // OFFICIAL/DOCS/DEV_COMMUNITY/PROFILEBOOST.
        // (Starvation escape hatch below may still admit NOFILTER_SAFE when everything
        // else filters out.)
        if (officialOnly) {
            ArrayList<WebFailSoftStage> clamped = new ArrayList<>();
            for (WebFailSoftStage st : configuredOrder) {
                if (st == WebFailSoftStage.OFFICIAL
                        || st == WebFailSoftStage.DOCS
                        || (st == WebFailSoftStage.DEV_COMMUNITY && officialOnlyIncludeDevCommunity)
                        || st == WebFailSoftStage.PROFILEBOOST) {
                    clamped.add(st);
                }
            }
            order = clamped.isEmpty() ? List.of(WebFailSoftStage.OFFICIAL, WebFailSoftStage.DOCS) : clamped;
        }

        boolean nofilterEnabled = !officialOnly && props.isAllowNoFilterStage()
                && order.contains(WebFailSoftStage.NOFILTER);
        boolean tagSnippetBody = props.isTagSnippetBody();

        // Domain/stage pair tracing (for EOR/FIND_LOG pattern extraction + misroute
        // reports)
        final boolean tracePairs = props.isTraceDomainStagePairs();
        final int tracePairsMax = Math.max(0, props.getMaxTraceDomainStagePairs());
        int tracedRaw = 0;
        int tracedSelected = 0;

        Map<WebFailSoftStage, List<TaggedSnippet>> buckets = new LinkedHashMap<>();
        for (WebFailSoftStage st : WebFailSoftStage.values()) {
            buckets.put(st, new ArrayList<>());
        }

        // Candidate-level debug capture (score/label/dropReason + evidence) stored into
        // web.failsoft.runs
        final List<String> posTokens = tokenizeForHits(canonicalQuery);
        final Set<String> negTerms = (aug == null || aug.negativeTerms() == null) ? Set.of() : aug.negativeTerms();
        final List<Map<String, Object>> candidateDiagnostics = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> candidatesByKey = new LinkedHashMap<>();

        int rawIdx = 0;
        for (String s : raw) {
            rawIdx++;
            WebSnippet sn = WebSnippet.parse(s);
            String key = keyFor(sn);

            // Always classify for diagnostics (even if later dropped as tech-spam).
            StageDecision baseDecision;
            try {
                baseDecision = classifyDetailed(sn, whitelistProfile);
            } catch (Throwable ignore) {
                baseDecision = new StageDecision(WebFailSoftStage.NOFILTER_SAFE, "classify:exception",
                        RerankSourceCredibility.UNVERIFIED,
                        false, false, false, false, false, false, false, false);
            }

            boolean techSpam = isTechSpam(sn, aug);
            WebFailSoftStage stageUsed = baseDecision.stage;
            StageDecision usedDecision = baseDecision;
            String overridePath = "base";
            String dropReason = null;

            if (techSpam) {
                if (nofilterEnabled) {
                    stageUsed = WebFailSoftStage.NOFILTER;
                    usedDecision = new StageDecision(
                            WebFailSoftStage.NOFILTER, "techspam:nofilter", RerankSourceCredibility.UNVERIFIED,
                            false, false, false,
                            false, false, false,
                            false, false);
                    overridePath = "techSpam->NOFILTER";
                } else {
                    // Not bucketed; will be dropped.
                    dropReason = "tech_spam";
                    overridePath = "techSpam->drop";
                }
            }

            // Evidence for score/label
            List<String> tokenHits = findTokenHits(sn, posTokens);
            List<String> negHits = findNegHits(sn, negTerms);
            int stageScore = scoreStage(stageUsed);
            int credScore = scoreCred(baseDecision.credibility);
            int score = stageScore + credScore + (tokenHits.size() * 2) - (negHits.size() * 5);
            if (techSpam && !nofilterEnabled) {
                score -= 100;
            }

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("idx", rawIdx);
            if (key != null && !key.isBlank()) {
                cand.put("key", safeTrim(key, 280));
            }
            cand.put("url", sn == null ? null : sn.url());
            cand.put("host", sn == null ? null : sn.host());
            cand.put("label", stageUsed == null ? "" : stageUsed.name());
            cand.put("stage", stageUsed == null ? "" : stageUsed.name());
            cand.put("stageFinal", stageUsed == null ? "" : stageUsed.name());
            cand.put("baseStage", baseDecision.stage == null ? "" : baseDecision.stage.name());
            cand.put("cred", baseDecision.credibility == null ? "UNVERIFIED" : baseDecision.credibility.name());
            cand.put("rule", baseDecision.by);
            cand.put("ruleUsed", usedDecision.by);
            cand.put("overridePath", overridePath);
            cand.put("techSpam", techSpam);

            // Rule evidence (which list matched)
            cand.put("propsOfficial", baseDecision.propsOfficial);
            cand.put("propsDocs", baseDecision.propsDocs);
            cand.put("propsDevCommunity", baseDecision.propsDevCommunity);
            cand.put("profileOfficial", baseDecision.profileOfficial);
            cand.put("profileDocs", baseDecision.profileDocs);
            cand.put("profileDevCommunity", baseDecision.profileDevCommunity);
            cand.put("profileWhitelist", baseDecision.profileWhitelist);
            cand.put("denyDevCommunity", baseDecision.denyDevCommunity);

            cand.put("selected", false);
            cand.put("considered", false);
            if (dropReason != null) {
                cand.put("dropReason", dropReason);
            }

            // Score + breakdown
            int scoreTokenHits = tokenHits.size() * 2;
            int scoreNegHits = negHits.size() * (-5);
            int techSpamPenalty = (techSpam && !nofilterEnabled) ? -100 : 0;

            cand.put("score", score);
            cand.put("scoreStage", stageScore);
            cand.put("scoreCred", credScore);
            cand.put("scoreTokenHits", scoreTokenHits);
            cand.put("scoreNegHits", scoreNegHits);
            if (techSpamPenalty != 0) {
                cand.put("scoreTechSpamPenalty", techSpamPenalty);
            }

            Map<String, Object> scoreBreakdown = new LinkedHashMap<>();
            scoreBreakdown.put("stage", stageScore);
            scoreBreakdown.put("cred", credScore);
            scoreBreakdown.put("tokenHits", scoreTokenHits);
            scoreBreakdown.put("negHits", scoreNegHits);
            if (techSpamPenalty != 0) {
                scoreBreakdown.put("techSpamPenalty", techSpamPenalty);
            }
            scoreBreakdown.put("total", score);
            cand.put("scoreBreakdown", scoreBreakdown);
            cand.put("scoreWeights", Map.of("tokenHit", 2, "negHit", -5, "techSpamPenalty", -100));

            // Evidence (truncate to keep run meta small)
            cand.put("tokenHitCount", tokenHits.size());
            cand.put("tokenHits", capList(tokenHits, 12));
            cand.put("tokenHitDetails", buildHitDetails(sn, tokenHits, 6, 36));
            cand.put("negHitCount", negHits.size());
            cand.put("negHits", capList(negHits, 12));
            cand.put("negHitDetails", buildHitDetails(sn, negHits, 6, 36));

            // Decision chain (override/selection path)
            List<Map<String, Object>> decisionChain = new ArrayList<>();
            Map<String, Object> step0 = new LinkedHashMap<>();
            step0.put("step", "classify");
            step0.put("stage", baseDecision.stage == null ? "" : baseDecision.stage.name());
            step0.put("cred", baseDecision.credibility == null ? "UNVERIFIED" : baseDecision.credibility.name());
            step0.put("rule", baseDecision.by);
            decisionChain.add(step0);
            if (techSpam) {
                Map<String, Object> step1 = new LinkedHashMap<>();
                step1.put("step", "techSpam");
                step1.put("action", nofilterEnabled ? "override_to_NOFILTER" : "penalize");
                step1.put("stage", stageUsed == null ? "" : stageUsed.name());
                step1.put("rule", usedDecision.by);
                step1.put("overridePath", overridePath);
                decisionChain.add(step1);
            }
            cand.put("decisionChain", decisionChain);

            candidateDiagnostics.add(cand);
            if (key != null && !key.isBlank()) {
                candidatesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(cand);
            }

            // TECH spam is dropped by default, but can be optionally included only in the
            // explicit NOFILTER stage.
            if (techSpam && !nofilterEnabled) {
                continue;
            }

            buckets.get(stageUsed).add(new TaggedSnippet(sn, usedDecision));

            if (tracePairs && tracedRaw < tracePairsMax) {
                Map<String, Object> ev = stageEvent(runId, executedQuerySafe, canonicalQuery, aug.intent().name(), sn,
                        usedDecision, false,
                        cand);
                TraceStore.append("web.failsoft.domainStagePairs", ev);
                tracedRaw++;
            }
            // Always feed the rolling report when enabled; it is bounded and host-only.
            if (domainStageReport != null) {
                try {
                    domainStageReport.record(
                            stageEvent(runId, executedQuerySafe, canonicalQuery, aug.intent().name(), sn, usedDecision,
                                    false, cand));
                } catch (Exception ignore) {
                }
            }
        }

        int targetCount = Math.max(1, topK);
        int desiredHosts = minCitations > 0 ? minCitations : 0;

        // Citeable count is more stable than out.size() because non-citeable stages
        // (e.g., PROFILEBOOST) can fill slots and mask citation starvation.
        int citeableTarget = minCitations > 0 ? minCitations : 0;
        int citeableCount = 0;

        boolean profileBoostDeferred = false;

        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        LinkedHashSet<String> seenHosts = new LinkedHashSet<>();
        LinkedHashSet<String> seenCiteableHosts = new LinkedHashSet<>();
        ArrayList<String> out = new ArrayList<>(Math.min(targetCount, raw.size()));
        EnumMap<WebFailSoftStage, Integer> selectedCounts = new EnumMap<>(WebFailSoftStage.class);

        // Track whether the officialOnly starvation fallback actually added
        // NOFILTER_SAFE snippets.
        // We use this as a guard so the quality gate only triggers on the intended
        // boundary.
        boolean starvationFallbackUsed = false;
        int starvationFallbackAdded = 0;

        for (WebFailSoftStage stage : order) {
            // In officialOnly mode, defer PROFILEBOOST until we satisfy minCitations with
            // citeable stages (OFFICIAL/DOCS/DEV_COMMUNITY/NOFILTER_SAFE).
            if (officialOnly
                    && citeableTarget > 0
                    && stage == WebFailSoftStage.PROFILEBOOST
                    && citeableCount < citeableTarget) {
                profileBoostDeferred = true;
                try {
                    TraceStore.put("web.failsoft.profileBoost.deferred", true);
                    TraceStore.put("web.failsoft.profileBoost.deferred.reason", "citeable_not_met");
                    TraceStore.put("web.failsoft.profileBoost.deferred.citeableCount", citeableCount);
                    TraceStore.put("web.failsoft.profileBoost.deferred.citeableTarget", citeableTarget);
                } catch (Exception ignore) {
                    // fail-soft
                }
                continue;
            }

            List<TaggedSnippet> list = buckets.getOrDefault(stage, List.of());
            for (TaggedSnippet ts : list) {
                WebSnippet sn = ts.sn;
                StageDecision d = ts.decision;

                String key = (sn.url() != null && !sn.url().isBlank()) ? sn.url() : sn.raw();
                if (key == null || key.isBlank()) {
                    continue;
                }

                // Candidate diagnostics: mark as considered before any skip.
                Map<String, Object> cand = pickCandidateForKey(candidatesByKey, key);
                if (cand != null) {
                    String stageName = stage == null ? "" : stage.name();
                    cand.put("considered", true);
                    cand.put("consideredStage", stageName);
                    cand.put("stageFinal", stageName);

                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step", "consider");
                    step.put("stage", stageName);
                    appendDecisionChainStep(cand, step);
                }

                if (seenKeys.contains(key)) {
                    if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                        cand.put("dropReason", "duplicate_key");
                    }
                    continue;
                }

                RerankSourceCredibility cred = d.credibility == null ? RerankSourceCredibility.UNVERIFIED
                        : d.credibility;
                boolean countsForMinCitations = countsTowardMinCitations(stage, cred);

                String host = (sn.host() == null) ? "" : sn.host().toLowerCase(Locale.ROOT);
                // While we are still under minCitations, enforce host diversity for credible
                // citeable items only.
                if (desiredHosts > 0 && countsForMinCitations && seenCiteableHosts.size() < desiredHosts) {
                    if (!host.isBlank() && seenCiteableHosts.contains(host)) {
                        if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                            cand.put("dropReason", "host_duplicate");
                        }
                        continue;
                    }
                }

                if (!host.isBlank()) {
                    seenHosts.add(host);
                    if (countsForMinCitations) {
                        seenCiteableHosts.add(host);
                    }
                }
                seenKeys.add(key);

                out.add(formatSnippet(sn.raw(), stage, cred, tagSnippetBody));
                selectedCounts.merge(stage, 1, Integer::sum);
                if (countsForMinCitations) {
                    citeableCount++;
                }

                // DROP: DEV_COMMUNITY + UNVERIFIED selection often indicates a strict/misrouted
                // profile.
                if (stage == WebFailSoftStage.DEV_COMMUNITY && cred == RerankSourceCredibility.UNVERIFIED) {
                    try {
                        Object prev = TraceStore.putIfAbsent("web.failsoft.domainMisroute.reported", Boolean.TRUE);
                        if (prev == null) {
                            TraceStore.put("web.failsoft.domainMisroute.host", host);
                            TraceStore.put("web.failsoft.domainMisroute.query", safeTrim(executedQuerySafe, 240));
                        }
                    } catch (Exception ignore) {
                    }
                    if (faultMaskingLayerMonitor != null) {
                        try {
                            Object prev = TraceStore.putIfAbsent("web.failsoft.domainMisroute.faultmask", Boolean.TRUE);
                            if (prev == null) {
                                faultMaskingLayerMonitor.record("domain-misroute",
                                        new RuntimeException("DEV_COMMUNITY selected but UNVERIFIED"),
                                        host,
                                        "DEV_COMMUNITY+UNVERIFIED selected");
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                    if (nightmareBreaker != null) {
                        try {
                            nightmareBreaker.recordSilentFailure(NightmareKeys.WEB_FAILSOFT_MISROUTE, canonicalQuery,
                                    "devCommunityUnverifiedSelected");
                        } catch (Throwable ignore) {
                        }
                    }
                }

                if (cand != null) {
                    String stageName = stage == null ? "" : stage.name();
                    cand.put("selected", true);
                    cand.put("selectedStage", stageName);
                    cand.put("selectedBy", "stageOrder");
                    cand.put("stageFinal", stageName);
                    cand.remove("dropReason");

                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step", "select");
                    step.put("stage", stageName);
                    step.put("by", "stageOrder");
                    appendDecisionChainStep(cand, step);
                }

                // selected domain/stage pairs for debug/report
                if (tracePairs && tracedSelected < tracePairsMax) {
                    Map<String, Object> evSel = stageEvent(runId, executedQuerySafe, canonicalQuery,
                            aug.intent().name(), sn, d, true, cand);
                    TraceStore.append("web.failsoft.domainStagePairs.selected", evSel);
                    tracedSelected++;
                }
                if (domainStageReport != null) {
                    try {
                        domainStageReport
                                .record(stageEvent(runId, executedQuerySafe, canonicalQuery, aug.intent().name(), sn, d,
                                        true, cand));
                    } catch (Exception ignore) {
                    }
                }

                if (out.size() >= targetCount)
                    break;
            }
            if (out.size() >= targetCount)
                break;
        }

        // CiteableTopUp: ensure minCitations is satisfied by *credible citeable*
        // results.
        // - Only counts OFFICIAL/DOCS/DEV_COMMUNITY where credibility != UNVERIFIED
        // - Two-pass: prefer new hosts first, then allow host duplicates if still short
        int citeableTopUpAdded = 0;
        int citeableTopUpTailDropCount = 0;
        boolean citeableTopUpRelaxedHostDuplicateUsed = false;
        if (officialOnly && citeableTarget > 0 && citeableCount < citeableTarget) {
            int before = citeableCount;
            int needed = citeableTarget - citeableCount;
            // Insert only the missing amount, but allow a small cap to reduce churn.
            int maxAdd = Math.min(Math.min(4, targetCount), Math.max(0, needed));

            // Risk control: control how citeableTopUp inserts items so we keep ordering as
            // stable as possible.
            // - HEAD: always at index 0 (legacy behavior)
            // - HEAD_STABLE: insert at a growing head cursor (stable insertion order)
            // - PREFIX_STABLE: insert after the existing citeable prefix (default)
            String citeableTopUpInsertMode = null;
            try {
                citeableTopUpInsertMode = props == null ? null : props.getCiteableTopUpInsertMode();
            } catch (Throwable ignore) {
                // fail-soft
            }
            String _insertMode = (citeableTopUpInsertMode == null || citeableTopUpInsertMode.isBlank())
                    ? "PREFIX_STABLE"
                    : citeableTopUpInsertMode.trim().toUpperCase(Locale.ROOT);
            boolean stableInsertCursor = !"HEAD".equals(_insertMode);
            int insertCursor = 0;
            if ("PREFIX_STABLE".equals(_insertMode)) {
                // Keep the existing citeable prefix order stable; insert just after it.
                if (out != null && !out.isEmpty()) {
                    for (int i = 0; i < out.size(); i++) {
                        if (!countsTowardMinCitationsFromTaggedOutput(out.get(i))) {
                            break;
                        }
                        insertCursor++;
                    }
                }
            } else if ("HEAD_STABLE".equals(_insertMode) || "HEAD".equals(_insertMode)) {
                insertCursor = 0;
            } else {
                // Unknown mode: default to PREFIX_STABLE.
                _insertMode = "PREFIX_STABLE";
                stableInsertCursor = true;
                if (out != null && !out.isEmpty()) {
                    for (int i = 0; i < out.size(); i++) {
                        if (!countsTowardMinCitationsFromTaggedOutput(out.get(i))) {
                            break;
                        }
                        insertCursor++;
                    }
                }
            }
            try {
                TraceStore.put("web.failsoft.citeableTopUp.insert.mode", _insertMode);
                TraceStore.put("web.failsoft.citeableTopUp.insert.cursor.start", insertCursor);
            } catch (Exception ignore) {
                // best-effort
            }

            java.util.List<WebFailSoftStage> stages = java.util.List.of(
                    WebFailSoftStage.OFFICIAL,
                    WebFailSoftStage.DOCS,
                    WebFailSoftStage.DEV_COMMUNITY);

            for (int pass = 0; pass < 2 && citeableTopUpAdded < maxAdd && citeableCount < citeableTarget; pass++) {
                boolean relaxHostDuplicate = (pass == 1);

                for (WebFailSoftStage st : stages) {
                    if (st == null) {
                        continue;
                    }
                    java.util.List<TaggedSnippet> pool = buckets.getOrDefault(st, java.util.List.of());
                    if (pool == null || pool.isEmpty()) {
                        continue;
                    }

                    for (TaggedSnippet ts : pool) {
                        if (citeableTopUpAdded >= maxAdd || citeableCount >= citeableTarget) {
                            break;
                        }
                        if (ts == null || ts.sn == null) {
                            continue;
                        }
                        WebSnippet sn = ts.sn;
                        StageDecision d = ts.decision;

                        String key = (sn.url() != null && !sn.url().isBlank()) ? sn.url() : sn.raw();
                        if (key == null || key.isBlank()) {
                            continue;
                        }

                        Map<String, Object> cand = pickCandidateForKey(candidatesByKey, key);
                        if (seenKeys.contains(key)) {
                            if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                                cand.put("dropReason", "duplicate_key");
                            }
                            continue;
                        }

                        RerankSourceCredibility cred = (d == null || d.credibility == null)
                                ? RerankSourceCredibility.UNVERIFIED
                                : d.credibility;
                        if (!countsTowardMinCitations(st, cred)) {
                            continue;
                        }

                        String host = (sn.host() == null) ? "" : sn.host().toLowerCase(Locale.ROOT);
                        if (!relaxHostDuplicate && desiredHosts > 0 && seenCiteableHosts.size() < desiredHosts) {
                            if (!host.isBlank() && seenCiteableHosts.contains(host)) {
                                if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                                    cand.put("dropReason", "host_duplicate");
                                }
                                continue;
                            }
                        }

                        // Make room when the out list is already full: evict one tail non-citeable.
                        if (out.size() >= targetCount) {
                            boolean evicted = tryEvictTailNonCiteableForCiteableInsert(out, selectedCounts);
                            if (!evicted) {
                                break;
                            }
                            citeableTopUpTailDropCount++;
                        }

                        // Candidate diagnostics (best-effort)
                        if (cand != null) {
                            String stageName = st.name();
                            cand.put("considered", true);
                            cand.put("consideredStage", stageName);
                            cand.put("stageFinal", stageName);

                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("step", "consider");
                            step.put("stage", stageName);
                            step.put("by", "citeableTopUp");
                            appendDecisionChainStep(cand, step);
                        }

                        if (relaxHostDuplicate) {
                            citeableTopUpRelaxedHostDuplicateUsed = true;
                        }

                        seenKeys.add(key);
                        if (!host.isBlank()) {
                            seenHosts.add(host);
                            seenCiteableHosts.add(host);
                        }

                        // Insert citeable evidence early, but keep ordering stable per insert mode.
                        int at = Math.max(0, Math.min(insertCursor, out.size()));
                        out.add(at, formatSnippet(sn.raw(), st, cred, tagSnippetBody));
                        if (stableInsertCursor) {
                            insertCursor++;
                        }
                        selectedCounts.merge(st, 1, Integer::sum);
                        citeableCount++;
                        citeableTopUpAdded++;
                        if (relaxHostDuplicate) {
                            citeableTopUpRelaxedHostDuplicateUsed = true;
                        }

                        if (cand != null) {
                            String stageName = st.name();
                            cand.put("selected", true);
                            cand.put("selectedStage", stageName);
                            cand.put("selectedBy", "citeableTopUp");
                            cand.put("overridePath", "citeableTopUp");
                            cand.put("stageFinal", stageName);
                            cand.remove("dropReason");

                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("step", "select");
                            step.put("stage", stageName);
                            step.put("by", "citeableTopUp");
                            appendDecisionChainStep(cand, step);
                        }
                    }
                }
            }

            if (citeableTopUpAdded > 0) {
                try {
                    TraceStore.put("web.failsoft.citeableTopUp.used", true);
                    TraceStore.put("web.failsoft.citeableTopUp.added", citeableTopUpAdded);
                    TraceStore.put("web.failsoft.citeableTopUp.tailDrop.count", citeableTopUpTailDropCount);
                    TraceStore.put("web.failsoft.citeableTopUp.tailDrop.used", citeableTopUpTailDropCount > 0);
                    TraceStore.put("web.failsoft.citeableTopUp.citeableCount.before", before);
                    TraceStore.put("web.failsoft.citeableTopUp.citeableCount.after", citeableCount);
                    TraceStore.put("web.failsoft.citeableTopUp.target", citeableTarget);
                    TraceStore.put("web.failsoft.citeableTopUp.relaxedHostDuplicateUsed",
                            citeableTopUpRelaxedHostDuplicateUsed);
                    TraceStore.put("web.failsoft.citeableTopUp.insert.cursor.end", insertCursor);
                } catch (Exception ignore) {
                    // fail-soft
                }
            } else {
                try {
                    TraceStore.put("web.failsoft.citeableTopUp.used", false);
                    TraceStore.put("web.failsoft.citeableTopUp.target", citeableTarget);
                    TraceStore.put("web.failsoft.citeableTopUp.citeableCount.before", before);
                    TraceStore.put("web.failsoft.citeableTopUp.tailDrop.count", citeableTopUpTailDropCount);
                    TraceStore.put("web.failsoft.citeableTopUp.tailDrop.used", false);
                } catch (Exception ignore) {
                    // fail-soft
                }
            }
        }

        // Starvation escape hatch (optionized): limited safe top-up when officialOnly
        // clamp starves.
        //
        // Primary pool: NOFILTER_SAFE
        // Secondary pool: DEV_COMMUNITY (only when NOFILTER_SAFE is empty)
        //
        // NOTE: Keep the legacy behavior but gate it by props
        // (enabled/max/intents/trigger).
        List<TaggedSnippet> safe = buckets.getOrDefault(WebFailSoftStage.NOFILTER_SAFE, List.of());
        List<TaggedSnippet> devRescue = buckets.getOrDefault(WebFailSoftStage.DEV_COMMUNITY, List.of());

        WebFailSoftStage fallbackStage = WebFailSoftStage.NOFILTER_SAFE;
        List<TaggedSnippet> fallbackPool = safe;
        boolean safePoolEmpty = (fallbackPool == null || fallbackPool.isEmpty());
        if (safePoolEmpty && devRescue != null && !devRescue.isEmpty()) {
            fallbackStage = WebFailSoftStage.DEV_COMMUNITY;
            fallbackPool = devRescue;
        }

        try {
            TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", safePoolEmpty);
            TraceStore.put("starvationFallback.poolSafeEmpty", safePoolEmpty);
            TraceStore.put("poolSafeEmpty", safePoolEmpty);
            TraceStore.put("web.failsoft.starvationFallback.poolUsed", fallbackStage.name());
            TraceStore.put("web.failsoft.starvationFallback.pool.safe.size", safe == null ? 0 : safe.size());
            TraceStore.put("web.failsoft.starvationFallback.pool.dev.size", devRescue == null ? 0 : devRescue.size());
            TraceStore.put("web.failsoft.starvationFallback.pool.size", fallbackPool == null ? 0 : fallbackPool.size());
        } catch (Exception ignore) {
            // fail-soft
        }

        try {
            TraceStore.put("web.failsoft.starvationFallback.citeableCount.before", citeableCount);
            TraceStore.put("web.failsoft.starvationFallback.citeableTarget", Math.max(0, minCitations));
        } catch (Exception ignore) {
            // fail-soft
        }

        if (shouldStarvationFallback(officialOnly, highRisk, aug, out, minCitations, topK, fallbackPool,
                citeableCount)) {
            String trigger = normalizeFallbackTrigger(props.getOfficialOnlyStarvationFallbackTrigger());

            // Base cap (config) for how many items we may add from the fallback pool.
            int outBeforeBase = out.size();
            int fallbackAddLimit = Math.max(0, Math.min(props.getOfficialOnlyStarvationFallbackMax(), topK));

            // Severe starvation: if we only top-up to "min citations", we can end up with
            // outCount<<topK
            // and re-entry loops. In that case, allow a full top-up to targetCount (but
            // only from
            // NOFILTER_SAFE, not DEV_COMMUNITY).
            boolean severeStarvationOutCount = outBeforeBase < Math.max(1, (targetCount + 1) / 2);
            boolean severeTopUp = "BELOW_MIN_CITATIONS".equals(trigger)
                    && fallbackStage == WebFailSoftStage.NOFILTER_SAFE
                    && severeStarvationOutCount;

            if (severeTopUp) {
                // Expand the add-limit only in severe starvation so we can actually reach
                // targetCount
                // even when the default max is small.
                fallbackAddLimit = Math.max(fallbackAddLimit, Math.max(0, targetCount - outBeforeBase));
                try {
                    TraceStore.put("web.failsoft.starvationFallback.severeTopUp", true);
                    TraceStore.put("web.failsoft.starvationFallback.severeTopUp.outBefore", outBeforeBase);
                    TraceStore.put("web.failsoft.starvationFallback.severeTopUp.fallbackAddLimit", fallbackAddLimit);
                } catch (Exception ignore) {
                    // fail-soft
                }
            }

            int maxOutSize = Math.min(targetCount, outBeforeBase + fallbackAddLimit);
            int desiredOutSize;
            if ("BELOW_MIN_CITATIONS".equals(trigger)) {
                if (severeTopUp) {
                    desiredOutSize = maxOutSize;
                    try {
                        TraceStore.put("web.failsoft.starvationFallback.severeTopUp.desiredOutSize", desiredOutSize);
                    } catch (Exception ignore) {
                        // fail-soft
                    }
                } else {
                    int desiredEvidence = Math.max(1, minCitations);
                    int nonCiteableAlready = Math.max(0, outBeforeBase - citeableCount);
                    desiredOutSize = Math.min(maxOutSize, nonCiteableAlready + desiredEvidence);
                    try {
                        TraceStore.put("web.failsoft.starvationFallback.citeableTarget", desiredEvidence);
                    } catch (Exception ignore) {
                        // fail-soft
                    }
                }
            } else {
                desiredOutSize = maxOutSize;
            }

            int outBefore = out.size();
            int added = 0;
            for (TaggedSnippet ts : fallbackPool) {
                WebSnippet sn = ts.sn;
                StageDecision d = ts.decision;

                String key = (sn.url() != null && !sn.url().isBlank()) ? sn.url() : sn.raw();
                if (key == null || key.isBlank())
                    continue;

                // Candidate diagnostics: mark as considered before any skip.
                Map<String, Object> cand = pickCandidateForKey(candidatesByKey, key);
                if (cand != null) {
                    String stageName = fallbackStage.name();
                    cand.put("considered", true);
                    cand.put("consideredStage", stageName);
                    cand.put("stageFinal", stageName);

                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step", "consider");
                    step.put("stage", stageName);
                    step.put("by", "starvationFallback");
                    appendDecisionChainStep(cand, step);
                }

                if (seenKeys.contains(key)) {
                    if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                        cand.put("dropReason", "duplicate_key");
                    }
                    continue;
                }

                RerankSourceCredibility cred = d.credibility == null ? RerankSourceCredibility.UNVERIFIED
                        : d.credibility;
                boolean countsForMinCitations = countsTowardMinCitations(fallbackStage, cred);

                String host = (sn.host() == null) ? "" : sn.host().toLowerCase(Locale.ROOT);
                if (desiredHosts > 0 && countsForMinCitations && seenCiteableHosts.size() < desiredHosts) {
                    if (!host.isBlank() && seenCiteableHosts.contains(host)) {
                        if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                            cand.put("dropReason", "host_duplicate");
                        }
                        continue;
                    }
                }

                if (!host.isBlank()) {
                    seenHosts.add(host);
                    if (countsForMinCitations) {
                        seenCiteableHosts.add(host);
                    }
                }
                seenKeys.add(key);

                out.add(formatSnippet(sn.raw(), fallbackStage, cred, tagSnippetBody));
                selectedCounts.merge(fallbackStage, 1, Integer::sum);
                if (countsForMinCitations) {
                    citeableCount++;
                }
                added++;

                if (cand != null) {
                    String stageName = fallbackStage.name();
                    cand.put("selected", true);
                    cand.put("selectedStage", stageName);
                    cand.put("selectedBy", "starvationFallback");
                    cand.put("overridePath", "starvationFallback");
                    cand.put("stageFinal", stageName);
                    cand.remove("dropReason");

                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step", "select");
                    step.put("stage", stageName);
                    step.put("by", "starvationFallback");
                    appendDecisionChainStep(cand, step);
                }

                // selected domain/stage pairs for debug/report
                if (tracePairs && tracedSelected < tracePairsMax) {
                    Map<String, Object> evSel = stageEvent(runId, executedQuerySafe, canonicalQuery,
                            aug.intent().name(), sn, d, true, cand);
                    TraceStore.append("web.failsoft.domainStagePairs.selected", evSel);
                    tracedSelected++;
                }
                if (domainStageReport != null) {
                    try {
                        domainStageReport
                                .record(stageEvent(runId, executedQuerySafe, canonicalQuery, aug.intent().name(), sn, d,
                                        true, cand));
                    } catch (Exception ignore) {
                    }
                }

                if (out.size() >= desiredOutSize)
                    break;
            }

            int outAfter = out.size();
            try {
                TraceStore.put("web.failsoft.starvationFallback.citeableCount.after", citeableCount);
            } catch (Exception ignore) {
                // fail-soft
            }
            try {
                Object prevLog = TraceStore.putIfAbsent("web.failsoft.starvationFallback.log.once", Boolean.TRUE);
                if (prevLog == null) {
                    boolean auxDegraded = (ctx != null && ctx.isAuxDegraded());
                    boolean auxHardDown = (ctx != null && ctx.isAuxHardDown());
                    log.warn(
                            "[nova][web-failsoft] starvationFallback branch (runId={}, trigger={}, officialOnly={}, outBefore={}, added={}, outAfter={}, desiredOutSize={}, addLimit={}, minCitations={}, topK={}, auxDegraded={}, auxHardDown={}, q={})",
                            runId,
                            trigger,
                            officialOnly,
                            outBefore,
                            added,
                            outAfter,
                            desiredOutSize,
                            fallbackAddLimit,
                            minCitations,
                            topK,
                            auxDegraded,
                            auxHardDown,
                            SafeRedactor.redact(executedQuerySafe));
                }
            } catch (Throwable ignore) {
                // fail-soft
            }

            TraceStore.put("web.failsoft.starvationFallback", "officialOnly->" + fallbackStage.name());
            TraceStore.put("starvationFallback.trigger", "officialOnly->" + fallbackStage.name());
            TraceStore.put("web.failsoft.starvationFallback.count", String.valueOf(added));
            // Unmask starvation as a fault-mask signal (DROP): evidence pipeline would
            // otherwise look 'normal'.
            if (added > 0) {
                starvationFallbackUsed = true;
                starvationFallbackAdded = added;
                try {
                    TraceStore.put("web.failsoft.starvationFallback.used", true);
                    TraceStore.put("web.failsoft.starvationFallback.added", added);
                    // Bridge key: some downstream dashboards expect the legacy thinRescue marker.
                    TraceStore.put("web.domainFilter.thinRescue", true);
                    TraceStore.put("web.domainFilter.thinRescue.by", "starvationFallback");
                    TraceStore.put("web.domainFilter.thinRescue.added", added);
                } catch (Exception ignore) {
                }
                boolean severeStarvation = out.isEmpty() || (minCitations > 0 && out.size() < minCitations);
                TraceStore.put("web.failsoft.starvationFallback.severity", severeStarvation ? "SEVERE" : "MILD");
                if (!severeStarvation) {
                    TraceStore.put("web.failsoft.starvationFallback.faultmaskSuppressed", true);
                }
                if (faultMaskingLayerMonitor != null && severeStarvation) {
                    try {
                        // Avoid runaway faultmask counters when this request executes multiple
                        // web searches (e.g., qualityGate rescue / extraSearchCalls).
                        Object prev = TraceStore.putIfAbsent(
                                "web.failsoft.starvationFallback.faultmaskEmitted",
                                Boolean.TRUE);

                        String note = "officialOnly starved; used " + fallbackStage.name() + " (added=" + added
                                + (safePoolEmpty ? ", poolSafeEmpty=true" : "") + ")";
                        if (prev == null) {
                            TraceStore.put("web.failsoft.starvationFallback.faultmaskNote", note);
                            faultMaskingLayerMonitor.record("websearch:starvation",
                                    new RuntimeException("web-failsoft starvation fallback used"),
                                    executedQuerySafe,
                                    note);
                        } else {
                            TraceStore.inc("web.failsoft.starvationFallback.faultmaskEmitted.skipped");
                        }
                    } catch (Throwable ignore) {
                    }
                }
            } else {
                starvationFallbackUsed = false;
                starvationFallbackAdded = 0;
                try {
                    TraceStore.put("web.failsoft.starvationFallback.used", false);
                } catch (Exception ignore) {
                }
            }
        }

        // In officialOnly mode, PROFILEBOOST can mask citeable starvation by filling
        // slots early.
        // Defer it until we have satisfied minCitations (unless we have no citeable
        // results at all).
        if (officialOnly && out.size() < targetCount) {
            List<TaggedSnippet> profileBoost = buckets.getOrDefault(WebFailSoftStage.PROFILEBOOST, List.of());
            if (profileBoost != null && !profileBoost.isEmpty()) {
                boolean citeableSatisfied = (citeableTarget <= 0) || (citeableCount >= citeableTarget);
                boolean allowFill = citeableSatisfied || out.isEmpty();
                if (!allowFill) {
                    // Keep deferred; citeable ladder should take precedence.
                    profileBoostDeferred = true;
                    try {
                        TraceStore.put("web.failsoft.profileBoost.deferred", true);
                        TraceStore.put("web.failsoft.profileBoost.deferred.reason", "citeable_not_met.afterFallback");
                        TraceStore.put("web.failsoft.profileBoost.deferred.citeableCount", citeableCount);
                        TraceStore.put("web.failsoft.profileBoost.deferred.citeableTarget", citeableTarget);
                    } catch (Exception ignore) {
                        // fail-soft
                    }
                } else {
                    int filled = 0;
                    for (TaggedSnippet ts : profileBoost) {
                        WebSnippet sn = ts.sn;
                        StageDecision d = ts.decision;

                        String key = (sn.url() != null && !sn.url().isBlank()) ? sn.url() : sn.raw();
                        if (key == null || key.isBlank()) {
                            continue;
                        }

                        Map<String, Object> cand = pickCandidateForKey(candidatesByKey, key);
                        if (cand != null) {
                            String stageName = WebFailSoftStage.PROFILEBOOST.name();
                            cand.put("considered", true);
                            cand.put("consideredStage", stageName);
                            cand.put("stageFinal", stageName);

                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("step", "consider");
                            step.put("stage", stageName);
                            step.put("by", "profileBoostFill");
                            appendDecisionChainStep(cand, step);
                        }

                        if (seenKeys.contains(key)) {
                            if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                                cand.put("dropReason", "duplicate_key");
                            }
                            continue;
                        }

                        String host = (sn.host() == null) ? "" : sn.host().toLowerCase(Locale.ROOT);
                        if (desiredHosts > 0 && seenHosts.size() < desiredHosts) {
                            if (!host.isBlank() && seenHosts.contains(host)) {
                                if (cand != null && !Boolean.TRUE.equals(cand.get("selected"))) {
                                    cand.put("dropReason", "host_duplicate");
                                }
                                continue;
                            }
                        }

                        if (!host.isBlank()) {
                            seenHosts.add(host);
                        }
                        seenKeys.add(key);

                        RerankSourceCredibility cred = d.credibility == null ? RerankSourceCredibility.UNVERIFIED
                                : d.credibility;
                        out.add(formatSnippet(sn.raw(), WebFailSoftStage.PROFILEBOOST, cred, tagSnippetBody));
                        selectedCounts.merge(WebFailSoftStage.PROFILEBOOST, 1, Integer::sum);
                        filled++;

                        if (cand != null) {
                            String stageName = WebFailSoftStage.PROFILEBOOST.name();
                            cand.put("selected", true);
                            cand.put("selectedStage", stageName);
                            cand.put("selectedBy", "profileBoostFill");
                            cand.put("overridePath", "profileBoostFill");
                            cand.put("stageFinal", stageName);
                            cand.remove("dropReason");

                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("step", "select");
                            step.put("stage", stageName);
                            step.put("by", "profileBoostFill");
                            appendDecisionChainStep(cand, step);
                        }

                        if (out.size() >= targetCount) {
                            break;
                        }
                    }

                    try {
                        TraceStore.put("web.failsoft.profileBoost.filled", filled);
                    } catch (Exception ignore) {
                        // fail-soft
                    }
                }
            }
        }

        // If we are still starving under officialOnly even after the safe fallback,
        // record as a silent-failure signal for circuit breaking / diagnostics.
        if (officialOnly && out.isEmpty() && raw != null && !raw.isEmpty() && nightmareBreaker != null) {
            try {
                Object prevLog = TraceStore.putIfAbsent("web.failsoft.starvation.log.once", Boolean.TRUE);
                if (prevLog == null) {
                    boolean auxDegraded = (ctx != null && ctx.isAuxDegraded());
                    boolean auxHardDown = (ctx != null && ctx.isAuxHardDown());
                    log.warn(
                            "[nova][web-failsoft] officialOnly starved (runId={}, rawCount={}, outCount=0, auxDegraded={}, auxHardDown={}, q={})",
                            runId,
                            raw == null ? 0 : raw.size(),
                            auxDegraded,
                            auxHardDown,
                            SafeRedactor.redact(executedQuerySafe));
                }
            } catch (Throwable ignore) {
                // fail-soft
            }

            try {
                String cq = (aug == null || aug.canonical() == null) ? "" : aug.canonical();
                nightmareBreaker.recordSilentFailure(NightmareKeys.WEB_FAILSOFT_STARVED, cq, "officialOnlyStarved");
            } catch (Throwable ignore) {
                // fail-soft
            }
        }

        // Finalize candidate diagnostics (derive stageFinal + fill default dropReason
        // when missing).
        try {
            Set<String> stageOrderNames = new HashSet<>();
            for (WebFailSoftStage st : order) {
                if (st != null) {
                    stageOrderNames.add(st.name());
                }
            }
            boolean targetFilled = out.size() >= targetCount;

            // Fallback stage eligibility (for diagnostics only).
            // Even when the primary stage order clamps out NOFILTER_SAFE, the starvation
            // fallback
            // may still admit it depending on props + intent.
            boolean fallbackPossible = false;
            try {
                boolean fbEnabled = props.isOfficialOnlyStarvationFallbackEnabled();
                boolean fbIntentAllowed = isFallbackIntentAllowed(aug,
                        props.getOfficialOnlyStarvationFallbackAllowedIntents());
                fallbackPossible = officialOnly && fbEnabled && fbIntentAllowed;
            } catch (Exception ignore) {
                fallbackPossible = false;
            }

            for (Map<String, Object> cand : candidateDiagnostics) {
                if (cand == null) {
                    continue;
                }

                boolean selected = Boolean.TRUE.equals(cand.get("selected"));
                boolean considered = Boolean.TRUE.equals(cand.get("considered"));
                String stageName = stringOrNull(cand.get("stage"));

                boolean excludedByClamp = stageName != null && !stageName.isBlank()
                        && !stageOrderNames.contains(stageName);

                // Preserve the actual stage label in stageFinal. Exclusion is represented by
                // dropReason/overridePath (stageFinal=EXCLUDED caused confusion and broke
                // allowlists that expect a concrete stage name like NOFILTER_SAFE).
                boolean fallbackStageAllowed = fallbackPossible
                        && fallbackStage != null
                        && stageName != null
                        && fallbackStage.name().equalsIgnoreCase(stageName);

                // stageFinal: selectedStage -> consideredStage -> stage
                String stageFinal;
                if (selected) {
                    stageFinal = stringOrNull(cand.get("selectedStage"));
                } else {
                    stageFinal = stringOrNull(cand.get("consideredStage"));
                }
                if (stageFinal == null || stageFinal.isBlank()) {
                    stageFinal = (stageName == null) ? "" : stageName;
                }
                if (stageFinal == null) {
                    stageFinal = "";
                }
                cand.put("stageFinal", stageFinal);

                if (selected) {
                    continue;
                }

                String dr = stringOrNull(cand.get("dropReason"));
                if (dr == null || dr.isBlank()) {
                    if (excludedByClamp) {
                        // Most commonly: officialOnly clamp excludes DEV_COMMUNITY.
                        // But the starvation fallback may still admit a fallbackStage, so label it
                        // separately to avoid misdiagnosis.
                        if (officialOnly && fallbackStageAllowed) {
                            dr = "officialOnly_clamped_fallback_candidate";
                            cand.put("overridePath", "officialOnlyClamp.fallbackAllowlist");
                        } else if (officialOnly && WebFailSoftStage.DEV_COMMUNITY.name().equals(stageName)
                                && !officialOnlyIncludeDevCommunity) {
                            dr = "officialOnly_exclude_devCommunity";
                            cand.put("overridePath", "officialOnlyClamp.excludeDevCommunity");
                        } else if (officialOnly) {
                            dr = "officialOnly_stage_excluded";
                            cand.put("overridePath", "officialOnlyClamp");
                        } else {
                            dr = "stage_excluded";
                        }
                        cand.put("dropReason", dr);
                    } else if (!considered && targetFilled) {
                        dr = "target_filled";
                        cand.put("dropReason", dr);
                    } else if (!considered) {
                        dr = "not_considered";
                        cand.put("dropReason", dr);
                    } else {
                        dr = "not_selected";
                        cand.put("dropReason", dr);
                    }
                }

                // decision chain: drop/exclude
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", excludedByClamp ? "exclude" : "drop");
                step.put("reason", dr);
                step.put("stageFinal", stageFinal);
                if (excludedByClamp) {
                    step.put("stage", stageName);
                }
                appendDecisionChainStep(cand, step);
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        // officialOnly clamp evidence (why a stage was clamped/excluded, and whether
        // a starvation fallback stage is still eligible).
        // One-line fixed keys help RCA without having to diff tables.
        try {
            if (officialOnly) {
                StringBuilder orderCsv = new StringBuilder();
                if (order != null) {
                    for (WebFailSoftStage st : order) {
                        if (st == null)
                            continue;
                        if (orderCsv.length() > 0)
                            orderCsv.append(",");
                        orderCsv.append(st.name());
                    }
                }

                boolean fbEnabled = false;
                boolean fbIntentAllowed = false;
                try {
                    fbEnabled = props.isOfficialOnlyStarvationFallbackEnabled();
                    fbIntentAllowed = isFallbackIntentAllowed(aug,
                            props.getOfficialOnlyStarvationFallbackAllowedIntents());
                } catch (Exception ignore2) {
                    // fail-soft
                }
                boolean fbPossible = officialOnly && fbEnabled && fbIntentAllowed;

                int nofilterExcluded = 0;
                int nofilterFallbackCandidate = 0;
                if (candidateDiagnostics != null) {
                    for (Map<String, Object> cand : candidateDiagnostics) {
                        if (cand == null)
                            continue;
                        String st = stringOrNull(cand.get("stage"));
                        String dr = stringOrNull(cand.get("dropReason"));
                        if (!WebFailSoftStage.NOFILTER_SAFE.name().equalsIgnoreCase(st))
                            continue;
                        if ("officialOnly_stage_excluded".equals(dr))
                            nofilterExcluded++;
                        if ("officialOnly_clamped_fallback_candidate".equals(dr))
                            nofilterFallbackCandidate++;
                    }
                }

                String fbStage = (fallbackStage == null) ? "" : fallbackStage.name();
                String diagAllowlist = orderCsv.toString();
                if (fbPossible && !fbStage.isBlank() && !diagAllowlist.contains(fbStage)) {
                    diagAllowlist = diagAllowlist.isBlank() ? fbStage : (diagAllowlist + "," + fbStage);
                }

                String evidence = "runId=" + runId
                        + " officialOnly=true"
                        + " stageOrderEffective=[" + orderCsv + "]"
                        + " fallbackPossible=" + fbPossible
                        + " fallbackStage=" + fbStage
                        + " diagAllowlist=[" + diagAllowlist + "]"
                        + " NOFILTER_SAFE{excluded=" + nofilterExcluded
                        + ",fallbackCandidate=" + nofilterFallbackCandidate + "}"
                        + " rule=stageNotIn(stageOrderEffective)=>clamped (fallbackStage labeled separately)";

                TraceStore.put("web.failsoft.officialOnlyClamp.evidence", evidence);
                TraceStore.put("web.failsoft.officialOnlyClamp.exclusionSummary",
                        "NOFILTER_SAFE:excluded=" + nofilterExcluded
                                + ",fallbackCandidate=" + nofilterFallbackCandidate
                                + ",stageOrder=" + orderCsv
                                + ",fallbackStage=" + fbStage);
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        // FAIL-SOFT QUALITY GATE (officialOnly starvation fallback)
        //
        // Problem: In officialOnly runs, the starvation fallback may yield a citation
        // set composed
        // almost entirely of UNVERIFIED sources (e.g., when authority scoring is
        // conservative).
        //
        // Strategy: If the selected set is mostly UNVERIFIED and contains no
        // OFFICIAL/DOCS snippet,
        // attempt to force-in at least one OFFICIAL/DOCS candidate (best-effort).
        //
        // Observability: write TraceStore keys + emit DebugEvent (if available) so that
        // merge-boundary
        // misroutes can be reproduced from logs.
        // MERGE_HOOK:PROJ_AGENT::FAILSOFT_FORCE_OFFICIAL_IF_UNVERIFIED_V2
        boolean qgTriggered = false;
        boolean qgForced = false;
        boolean qgNeedRescueExtraSearch = false;
        double qgUnverifiedRatio = 0.0d;
        double qgThreshold = 0.0d;
        try {
            if (officialOnly
                    && starvationFallbackUsed
                    && props.isOfficialOnlyStarvationFallbackQualityGateEnabled()
                    && tagSnippetBody
                    && !out.isEmpty()) {

                int totalTagged = 0;
                int unverified = 0;
                int officialDocsSelected = 0;

                for (String s : out) {
                    if (s == null)
                        continue;
                    String sn = s.trim();
                    if (!sn.startsWith("[WEB:"))
                        continue;
                    totalTagged++;
                    if (sn.startsWith("[WEB:OFFICIAL") || sn.startsWith("[WEB:DOCS")) {
                        officialDocsSelected++;
                    }
                    // Fast path for our tag format: ...|CRED:UNVERIFIED]
                    if (sn.contains("|CRED:UNVERIFIED")) {
                        int credStart = sn.indexOf("|CRED:");
                        int credEnd = (credStart >= 0) ? sn.indexOf("]", credStart) : -1;
                        if (credStart >= 0 && credEnd > credStart) {
                            String cred = sn.substring(credStart + 6, credEnd);
                            if ("UNVERIFIED".equals(cred)) {
                                unverified++;
                            }
                        }
                    }
                }

                qgThreshold = props.getOfficialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold();
                if (Double.isNaN(qgThreshold) || Double.isInfinite(qgThreshold)) {
                    qgThreshold = 0.75d;
                }
                qgThreshold = Math.max(0.0d, Math.min(1.0d, qgThreshold));
                qgUnverifiedRatio = (totalTagged <= 0) ? 0.0d : ((double) unverified / (double) totalTagged);

                boolean mostlyUnverified = totalTagged > 0 && qgUnverifiedRatio >= qgThreshold;
                boolean missingOfficialDocs = officialDocsSelected <= 0;

                if (missingOfficialDocs && mostlyUnverified) {
                    qgTriggered = true;

                    TaggedSnippet forced = null;
                    // Prefer OFFICIAL, then DOCS. Skip duplicates.
                    List<TaggedSnippet> off = buckets.getOrDefault(WebFailSoftStage.OFFICIAL, List.of());
                    for (TaggedSnippet ts : off) {
                        if (ts == null || ts.sn == null)
                            continue;
                        String k = keyFor(ts.sn);
                        if (k != null && !k.isBlank() && !seenKeys.contains(k)) {
                            forced = ts;
                            break;
                        }
                    }
                    if (forced == null) {
                        List<TaggedSnippet> docs = buckets.getOrDefault(WebFailSoftStage.DOCS, List.of());
                        for (TaggedSnippet ts : docs) {
                            if (ts == null || ts.sn == null)
                                continue;
                            String k = keyFor(ts.sn);
                            if (k != null && !k.isBlank() && !seenKeys.contains(k)) {
                                forced = ts;
                                break;
                            }
                        }
                    }

                    if (forced != null) {
                        String k = keyFor(forced.sn);
                        if (k != null && !k.isBlank() && !seenKeys.contains(k)) {
                            String formatted = formatSnippet(forced.sn.raw(), forced.decision.stage,
                                    forced.decision.credibility, true);

                            if (out.size() >= targetCount) {
                                // Keep list length stable: drop the last element.
                                String removed = out.remove(out.size() - 1);
                                // Keep selected stage counts roughly consistent for diagnostics.
                                try {
                                    if (removed != null) {
                                        String rr = removed.trim();
                                        if (rr.startsWith("[WEB:")) {
                                            int rSep = rr.indexOf("|CRED:");
                                            if (rSep > 5) {
                                                String st = rr.substring(5, rSep);
                                                WebFailSoftStage removedStage = null;
                                                try {
                                                    removedStage = WebFailSoftStage.valueOf(st);
                                                } catch (Exception ignore) {
                                                }
                                                if (removedStage != null) {
                                                    Integer prev = selectedCounts.get(removedStage);
                                                    if (prev != null && prev > 0) {
                                                        if (prev == 1) {
                                                            selectedCounts.remove(removedStage);
                                                        } else {
                                                            selectedCounts.put(removedStage, prev - 1);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {
                                }
                            }

                            out.add(0, formatted);
                            seenKeys.add(k);
                            if (forced.sn.host() != null && !forced.sn.host().isBlank()) {
                                String qgHost = forced.sn.host().toLowerCase(Locale.ROOT);
                                seenHosts.add(qgHost);
                                boolean countsForMinCitations = countsTowardMinCitations(forced.decision.stage,
                                        forced.decision.credibility);
                                if (countsForMinCitations) {
                                    citeableCount++;
                                    seenCiteableHosts.add(qgHost);
                                }
                            }
                            selectedCounts.merge(forced.decision.stage, 1, Integer::sum);
                            qgForced = true;

                            // Candidate diagnostics (best-effort)
                            Map<String, Object> cand = pickCandidateForKey(candidatesByKey, k);
                            if (cand != null) {
                                String stName = forced.decision.stage == null ? "" : forced.decision.stage.name();
                                cand.put("selected", true);
                                cand.put("selectedStage", stName);
                                cand.put("selectedBy", "qualityGateForceInsert");
                                cand.put("overridePath", "qualityGateForceInsert");
                                cand.put("stageFinal", stName);
                                cand.remove("dropReason");

                                Map<String, Object> step = new LinkedHashMap<>();
                                step.put("step", "select");
                                step.put("stage", stName);
                                step.put("by", "qualityGateForceInsert");
                                appendDecisionChainStep(cand, step);
                            }

                            TraceStore.put("web.failsoft.forceOfficial.used", true);
                            TraceStore.put("web.failsoft.forceOfficial.stage", forced.decision.stage.name());
                            TraceStore.put("web.failsoft.forceOfficial.cred", forced.decision.credibility.name());
                        }
                    } else {
                        // No OFFICIAL/DOCS candidate in the current buckets.
                        qgNeedRescueExtraSearch = true;
                        TraceStore.put("web.failsoft.forceOfficial.used", false);
                        TraceStore.put("web.failsoft.forceOfficial.reason", "no_official_or_docs_candidate");
                    }
                }
            }
        } catch (Exception e) {
            // fail-soft; surface the exception in trace for RCA.
            try {
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.error", e.toString());
            } catch (Exception ignore) {
            }
        }

        if (qgTriggered) {
            try {
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.triggered", true);
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.unverifiedRatio", qgUnverifiedRatio);
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.threshold", qgThreshold);
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.forceInserted", qgForced);
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.needRescueExtraSearch",
                        qgNeedRescueExtraSearch);
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.starvationAdded", starvationFallbackAdded);
                TraceStore.put("web.failsoft.starvationFallback.qualityGate.runId", String.valueOf(runId));
            } catch (Exception ignore) {
            }

            if (debugEventStore != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("note", "officialOnly starvationFallback quality gate");
                data.put("runId", runId);
                data.put("executedQuery", safeTrim(executedQuerySafe, 240));
                data.put("canonicalQuery", safeTrim(canonicalQuery, 240));
                data.put("intent", aug == null || aug.intent() == null ? "" : aug.intent().name());
                data.put("officialOnly", officialOnly);
                data.put("starvationFallbackUsed", starvationFallbackUsed);
                data.put("starvationFallbackAdded", starvationFallbackAdded);
                data.put("unverifiedRatio", qgUnverifiedRatio);
                data.put("threshold", qgThreshold);
                data.put("forcedInserted", qgForced);
                data.put("needRescueExtraSearch", qgNeedRescueExtraSearch);
                data.put("outCount", out.size());
                data.put("minCitations", minCitations);

                String msg = qgForced
                        ? "Quality gate triggered; OFFICIAL/DOCS force-inserted"
                        : "Quality gate triggered; no OFFICIAL/DOCS candidate in current buckets";

                debugEventStore.emit(
                        DebugProbeType.WEB_SEARCH,
                        DebugEventLevel.WARN,
                        "web-failsoft.starvationFallback.qualityGate",
                        msg,
                        "WebFailSoftSearchAspect.applyStages",
                        data,
                        null);
            }
        }

        // ---------------------------------------------------------------------
        // Empty-merge starvation guard: demotion ladder (fail-soft)
        // ---------------------------------------------------------------------
        // If we had raw inputs but the final selection yielded 0 (due to stage clamp,
        // conservative drops, host diversity, etc.), force-in exactly 1 snippet by
        // progressively relaxing the stage boundary.
        //
        // Ladder (first hit wins):
        // OFFICIAL -> DOCS -> DEV_COMMUNITY -> PROFILEBOOST -> NOFILTER_SAFE ->
        // (optional) NOFILTER
        //
        // This is intentionally a "last safety net" to avoid merged=0 downstream.
        if ((out == null || out.isEmpty()) && rawInputNonBlankCount > 0) {
            boolean used = false;
            WebFailSoftStage usedStage = null;
            TaggedSnippet usedSnippet = null;
            String usedKey = null;
            Map<String, Object> usedCand = null;

            List<WebFailSoftStage> ladder = new ArrayList<>(List.of(
                    WebFailSoftStage.OFFICIAL,
                    WebFailSoftStage.DOCS,
                    WebFailSoftStage.DEV_COMMUNITY,
                    WebFailSoftStage.PROFILEBOOST,
                    WebFailSoftStage.NOFILTER_SAFE));
            if (props != null && props.isAllowNoFilterStage()) {
                ladder.add(WebFailSoftStage.NOFILTER);
            }

            // Pick the best candidate within the first stage that has any candidates.
            for (WebFailSoftStage st : ladder) {
                List<TaggedSnippet> pool = buckets.getOrDefault(st, List.of());
                if (pool == null || pool.isEmpty()) {
                    continue;
                }

                int bestScore = Integer.MIN_VALUE;
                TaggedSnippet best = null;
                String bestKey = null;
                Map<String, Object> bestCand = null;

                for (TaggedSnippet ts : pool) {
                    if (ts == null || ts.sn == null) {
                        continue;
                    }
                    WebSnippet sn = ts.sn;
                    String key = (sn.url() != null && !sn.url().isBlank()) ? sn.url() : sn.raw();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    Map<String, Object> cand = pickCandidateForKey(candidatesByKey, key);
                    int score = 0;
                    if (cand != null) {
                        Object sc = cand.get("score");
                        if (sc instanceof Number n) {
                            score = n.intValue();
                        } else if (sc != null) {
                            try {
                                score = Integer.parseInt(String.valueOf(sc));
                            } catch (Exception ignore) {
                                score = 0;
                            }
                        }
                    }
                    if (best == null || score > bestScore) {
                        bestScore = score;
                        best = ts;
                        bestKey = key;
                        bestCand = cand;
                    }
                }

                if (best != null) {
                    used = true;
                    usedStage = st;
                    usedSnippet = best;
                    usedKey = bestKey;
                    usedCand = bestCand;
                    break;
                }
            }

            if (used && usedSnippet != null && usedStage != null && usedSnippet.sn != null) {
                WebSnippet sn = usedSnippet.sn;
                StageDecision d = usedSnippet.decision;

                String host = (sn.host() == null) ? "" : sn.host().toLowerCase(Locale.ROOT);
                if (usedKey != null && !usedKey.isBlank()) {
                    seenKeys.add(usedKey);
                }
                if (!host.isBlank()) {
                    seenHosts.add(host);
                }

                RerankSourceCredibility cred = (d == null || d.credibility == null)
                        ? RerankSourceCredibility.UNVERIFIED
                        : d.credibility;
                boolean countsForMinCitations = countsTowardMinCitations(usedStage, cred);

                if (countsForMinCitations) {
                    citeableCount++;
                    if (!host.isBlank()) {
                        seenCiteableHosts.add(host);
                    }
                }

                out.add(formatSnippet(sn.raw(), usedStage, cred, tagSnippetBody));
                selectedCounts.merge(usedStage, 1, Integer::sum);

                // Candidate diagnostics (best-effort)
                if (usedCand != null) {
                    String stageName = usedStage.name();
                    usedCand.put("considered", true);
                    usedCand.put("consideredStage", stageName);
                    usedCand.put("selected", true);
                    usedCand.put("selectedStage", stageName);
                    usedCand.put("selectedBy", "demotionLadder");
                    usedCand.put("overridePath", "demotionLadder");
                    usedCand.put("stageFinal", stageName);
                    usedCand.remove("dropReason");

                    Map<String, Object> step1 = new LinkedHashMap<>();
                    step1.put("step", "consider");
                    step1.put("stage", stageName);
                    step1.put("by", "demotionLadder");
                    appendDecisionChainStep(usedCand, step1);

                    Map<String, Object> step2 = new LinkedHashMap<>();
                    step2.put("step", "select");
                    step2.put("stage", stageName);
                    step2.put("by", "demotionLadder");
                    appendDecisionChainStep(usedCand, step2);
                }

                // selected domain/stage pairs for debug/report
                if (tracePairs && tracedSelected < tracePairsMax) {
                    String intentName = aug == null || aug.intent() == null ? "" : aug.intent().name();
                    Map<String, Object> evSel = stageEvent(runId, executedQuerySafe, canonicalQuery,
                            intentName, sn, d, true, usedCand);
                    TraceStore.append("web.failsoft.domainStagePairs.selected", evSel);
                    tracedSelected++;
                }
                if (domainStageReport != null) {
                    try {
                        String intentName = aug == null || aug.intent() == null ? "" : aug.intent().name();
                        domainStageReport.record(stageEvent(runId, executedQuerySafe, canonicalQuery,
                                intentName, sn, d, true, usedCand));
                    } catch (Exception ignore) {
                    }
                }

                // TraceStore keys (observability)
                try {
                    TraceStore.put("web.failsoft.demotionLadder.used", true);
                    TraceStore.put("web.failsoft.demotionLadder.runId", String.valueOf(runId));
                    TraceStore.put("web.failsoft.demotionLadder.stage", usedStage.name());
                    TraceStore.put("web.failsoft.demotionLadder.cred", cred == null ? "" : cred.name());
                    TraceStore.put("web.failsoft.demotionLadder.host", host);
                    TraceStore.put("web.failsoft.demotionLadder.order", ladder.toString());
                    TraceStore.put("web.failsoft.demotionLadder.rawInputNonBlankCount", rawInputNonBlankCount);
                    TraceStore.put("web.failsoft.demotionLadder.rawInputCount", rawInputCount);
                    if (usedKey != null && !usedKey.isBlank()) {
                        TraceStore.put("web.failsoft.demotionLadder.key", safeTrim(usedKey, 260));
                    }
                    if (!executedQuerySafe.isBlank()) {
                        TraceStore.put("web.failsoft.demotionLadder.executedQuery", safeTrim(executedQuerySafe, 240));
                    }
                    if (!canonicalQuery.isBlank()) {
                        TraceStore.put("web.failsoft.demotionLadder.canonicalQuery", safeTrim(canonicalQuery, 240));
                    }
                } catch (Exception ignore) {
                    // fail-soft
                }

                try {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("runId", runId);
                    ev.put("stage", usedStage.name());
                    ev.put("cred", cred == null ? "" : cred.name());
                    ev.put("host", host);
                    ev.put("key", safeTrim(usedKey, 260));
                    ev.put("intent", aug == null || aug.intent() == null ? "" : aug.intent().name());
                    ev.put("rawInputCount", rawInputCount);
                    ev.put("rawInputNonBlankCount", rawInputNonBlankCount);
                    TraceStore.append("web.failsoft.demotionLadder.events", ev);
                } catch (Exception ignore) {
                    // fail-soft
                }
            } else {
                // We had raw inputs but no eligible bucket survived (e.g., everything was
                // dropped).
                try {
                    TraceStore.put("web.failsoft.demotionLadder.used", false);
                    TraceStore.put("web.failsoft.demotionLadder.runId", String.valueOf(runId));
                    TraceStore.put("web.failsoft.demotionLadder.reason", "no_candidate_in_buckets");
                    TraceStore.put("web.failsoft.demotionLadder.rawInputNonBlankCount", rawInputNonBlankCount);
                    TraceStore.put("web.failsoft.demotionLadder.rawInputCount", rawInputCount);
                } catch (Exception ignore) {
                    // fail-soft
                }
            }
        }

        Map<String, Integer> stageCountsRaw = new LinkedHashMap<>();
        for (WebFailSoftStage st : WebFailSoftStage.values()) {
            List<TaggedSnippet> b = buckets.get(st);
            stageCountsRaw.put(st.name(), (b == null) ? 0 : b.size());
        }

        Map<String, Integer> stageCountsSelected = new LinkedHashMap<>();
        for (WebFailSoftStage st : WebFailSoftStage.values()) {
            Integer c = selectedCounts.get(st);
            stageCountsSelected.put(st.name(), (c == null) ? 0 : c);
        }

        // Derive selected stage counts from the actual out list (ground truth).
        // This helps diagnose cases where per-run keys get clobbered by later empty
        // runs
        // (e.g., extraSearchCalls executing after a successful fallback run).
        Map<String, Integer> stageCountsSelectedFromOut = countStagesFromOut(out);
        TraceStore.put("web.failsoft.stageCountsSelectedFromOut.last", stageCountsSelectedFromOut);
        TraceStore.put("stageCountsSelectedFromOut.last", stageCountsSelectedFromOut);
        TraceStore.put("web.failsoft.stageCountsSelectedFromOut.last.runId", String.valueOf(runId));
        TraceStore.put("web.failsoft.stageCountsSelectedFromOut.last.outCount",
                String.valueOf(out == null ? 0 : out.size()));

        boolean fromOutNonZero = false;
        for (Integer v : stageCountsSelectedFromOut.values()) {
            if (v != null && v.intValue() > 0) {
                fromOutNonZero = true;
                break;
            }
        }
        if (fromOutNonZero) {
            // Sticky: do not overwrite on later empty-out runs.
            TraceStore.put("web.failsoft.stageCountsSelectedFromOut", stageCountsSelectedFromOut);
            TraceStore.put("stageCountsSelectedFromOut", stageCountsSelectedFromOut);
            TraceStore.put("web.failsoft.stageCountsSelectedFromOut.runId", String.valueOf(runId));
            TraceStore.put("web.failsoft.stageCountsSelectedFromOut.outCount",
                    String.valueOf(out == null ? 0 : out.size()));
        }

        TraceStore.put("web.failsoft.stageOrder.configured", configuredOrder.toString());
        TraceStore.put("web.failsoft.stageOrder.effective", order.toString());
        TraceStore.put("web.failsoft.stageOrder.clamped",
                String.valueOf(officialOnly && !configuredOrder.equals(order)));
        TraceStore.put("web.failsoft.stageCountsRaw", stageCountsRaw);
        TraceStore.put("web.failsoft.stageCountsSelected", stageCountsSelected);

        // DROP: expose starvation/host-diversity signals for RCA.
        try {
            int neededCitations = (minCitations > 0) ? Math.min(minCitations, targetCount) : 0;
            boolean minCitationsUnmet = neededCitations > 0 && citeableCount < neededCitations;
            int minOut = (neededCitations > 0) ? Math.min(targetCount, Math.max(1, neededCitations)) : 1;
            boolean starved = out.size() < minOut;

            TraceStore.put("web.failsoft.outHosts", new ArrayList<>(seenHosts));
            TraceStore.put("web.failsoft.outHosts.count", seenHosts.size());
            TraceStore.put("web.failsoft.citeableHosts", new ArrayList<>(seenCiteableHosts));
            TraceStore.put("web.failsoft.citeableHosts.count", seenCiteableHosts.size());
            TraceStore.put("web.failsoft.citeableCount", citeableCount);
            TraceStore.put("web.failsoft.minCitationsNeeded", neededCitations);
            TraceStore.put("web.failsoft.minCitationsUnmet", minCitationsUnmet);
            TraceStore.put("web.failsoft.starved", starved);
        } catch (Exception ignore) {
        }

        TraceStore.put("web.failsoft.canonicalQuery", aug.canonical());
        TraceStore.put("web.failsoft.intent", aug.intent().name());
        TraceStore.put("web.failsoft.minCitations", String.valueOf(minCitations));
        TraceStore.put("web.failsoft.officialOnly", String.valueOf(officialOnly));
        TraceStore.put("web.failsoft.outCount", String.valueOf(out.size()));
        TraceStore.put("outCount", out == null ? 0 : out.size());
        TraceStore.put("web.failsoft.nofilterEnabled", String.valueOf(nofilterEnabled));
        TraceStore.put("web.failsoft.tagSnippetBody", String.valueOf(tagSnippetBody));
        TraceStore.put("web.failsoft.traceDomainStagePairs", String.valueOf(tracePairs));
        TraceStore.put("web.failsoft.tracePairsMax", String.valueOf(tracePairsMax));
        TraceStore.put("web.failsoft.tracePairsRaw", String.valueOf(tracedRaw));
        TraceStore.put("web.failsoft.tracePairsSelected", String.valueOf(tracedSelected));
        // Per-run summary (append-only) so that extraSearchCalls don't clobber earlier
        // runs.
        try {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("runId", runId);
            run.put("rawInputCount", rawInputCount);
            run.put("rawInputNonBlankCount", rawInputNonBlankCount);
            // Snapshot of starvation fallback pools (so later empty runs don't clobber).
            run.put("starvationFallbackPoolUsed", fallbackStage == null ? "" : fallbackStage.name());
            run.put("starvationFallbackPoolSafeEmpty", safePoolEmpty);
            run.put("starvationFallbackPoolSafeSize", safe == null ? 0 : safe.size());
            run.put("starvationFallbackPoolDevSize", devRescue == null ? 0 : devRescue.size());
            run.put("starvationFallbackPoolSize", fallbackPool == null ? 0 : fallbackPool.size());
            if (!executedQuerySafe.isBlank()) {
                run.put("executedQuery", executedQuerySafe);
            }
            if (!canonicalQuery.isBlank()) {
                run.put("canonicalQuery", canonicalQuery);
            }
            run.put("intent", aug == null || aug.intent() == null ? "" : aug.intent().name());
            run.put("officialOnly", officialOnly);
            run.put("minCitations", minCitations);
            run.put("minCitationsDefaultUsed", minCitationsDefaultUsed);
            run.put("highRisk", highRisk);
            if (whitelistProfile != null && !whitelistProfile.isBlank()) {
                run.put("domainProfile", whitelistProfile);
            }

            run.put("stageOrderConfigured", configuredOrder.toString());
            run.put("stageOrderEffective", order.toString());
            run.put("stageOrderClamped", (officialOnly && !configuredOrder.equals(order)));

            // --- Options / toggles (run meta) ---
            run.put("officialOnlyClampIncludeDevCommunity", officialOnlyIncludeDevCommunity);

            String fallbackTrigger = normalizeFallbackTrigger(props.getOfficialOnlyStarvationFallbackTrigger());
            int fallbackMax = Math.max(0, Math.min(props.getOfficialOnlyStarvationFallbackMax(), topK));
            boolean fallbackEnabled = props.isOfficialOnlyStarvationFallbackEnabled();
            boolean fallbackIntentAllowed = isFallbackIntentAllowed(aug,
                    props.getOfficialOnlyStarvationFallbackAllowedIntents());
            run.put("starvationFallbackEnabled", fallbackEnabled);
            run.put("starvationFallbackTrigger", fallbackTrigger);
            run.put("starvationFallbackMax", fallbackMax);
            run.put("starvationFallbackIntentAllowed", fallbackIntentAllowed);

            run.put("outCount", out.size());
            run.put("stageCountsRaw", stageCountsRaw);
            run.put("stageCountsSelected", stageCountsSelected);
            run.put("stageCountsSelectedFromOut", stageCountsSelectedFromOut);

            run.put("nofilterEnabled", nofilterEnabled);
            run.put("tagSnippetBody", tagSnippetBody);

            run.put("traceDomainStagePairs", tracePairs);
            run.put("tracePairsMax", tracePairsMax);
            run.put("tracePairsRaw", tracedRaw);
            run.put("tracePairsSelected", tracedSelected);

            Object fallback = TraceStore.get("web.failsoft.starvationFallback");
            if (fallback != null) {
                run.put("starvationFallback", String.valueOf(fallback));
            }

            if (candidateDiagnostics != null && !candidateDiagnostics.isEmpty()) {
                run.put("candidates", candidateDiagnostics);
            }

            TraceStore.append("web.failsoft.runs", run);
        } catch (Exception ignore) {
            // fail-soft
        }

        // Best-effort Micrometer metrics (dashboard-friendly, never affects selection)
        recordFailSoftMetrics(officialOnly, aug, out, stageCountsSelected, candidateDiagnostics);

        emitSoakKpiJson(runId, stageCountsSelectedFromOut, out);

        return out;
    }

    private static Map<String, Object> stageEvent(long runId,
            String executedQuery,
            String canonicalQuery,
            String intent,
            WebSnippet sn,
            StageDecision d,
            boolean selected,
            Map<String, Object> cand) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("runId", runId);
        ev.put("executedQuery", safeTrim(executedQuery, 260));
        ev.put("canonicalQuery", safeTrim(canonicalQuery, 260));
        ev.put("intent", safeTrim(intent, 60));
        ev.put("url", sn == null ? null : sn.url());
        ev.put("host", sn == null ? null : sn.host());
        ev.put("selected", selected);

        if (d != null) {
            ev.put("stage", d.stage == null ? "" : d.stage.name());
            ev.put("cred", d.credibility == null ? "UNVERIFIED" : d.credibility.name());
            ev.put("by", selected ? "selected" : d.by);
            ev.put("classifiedBy", d.by);

            ev.put("propsOfficial", d.propsOfficial);
            ev.put("propsDocs", d.propsDocs);
            ev.put("propsDevCommunity", d.propsDevCommunity);

            ev.put("profileOfficial", d.profileOfficial);
            ev.put("profileDocs", d.profileDocs);
            ev.put("profileDevCommunity", d.profileDevCommunity);
            ev.put("profileWhitelist", d.profileWhitelist);

            ev.put("denyDevCommunity", d.denyDevCommunity);
        }

        if (cand != null) {
            Object score = cand.get("score");
            if (score != null) {
                ev.put("score", score);
            }
            Object overridePath = cand.get("overridePath");
            if (overridePath != null) {
                ev.put("overridePath", overridePath);
            }
            Object rule = cand.get("rule");
            if (rule != null) {
                ev.put("rule", rule);
            }
            Object ruleUsed = cand.get("ruleUsed");
            if (ruleUsed != null) {
                ev.put("ruleUsed", ruleUsed);
            }
            Object stageFinal = cand.get("stageFinal");
            if (stageFinal != null) {
                ev.put("stageFinal", stageFinal);
            }
            Object dropReason = cand.get("dropReason");
            if (dropReason != null) {
                ev.put("dropReason", dropReason);
            }
        }

        return ev;
    }

    private static final class TaggedSnippet {
        final WebSnippet sn;
        final StageDecision decision;

        TaggedSnippet(WebSnippet sn, StageDecision decision) {
            this.sn = sn;
            this.decision = decision;
        }
    }

    private static final class StageDecision {
        final WebFailSoftStage stage;
        final String by;
        final RerankSourceCredibility credibility;

        final boolean propsOfficial;
        final boolean propsDocs;
        final boolean propsDevCommunity;

        final boolean profileOfficial;
        final boolean profileDocs;
        final boolean profileDevCommunity;
        final boolean profileWhitelist;

        final boolean denyDevCommunity;

        StageDecision(WebFailSoftStage stage,
                String by,
                RerankSourceCredibility credibility,
                boolean propsOfficial,
                boolean propsDocs,
                boolean propsDevCommunity,
                boolean profileOfficial,
                boolean profileDocs,
                boolean profileDevCommunity,
                boolean profileWhitelist,
                boolean denyDevCommunity) {
            this.stage = stage == null ? WebFailSoftStage.NOFILTER_SAFE : stage;
            this.by = by;
            this.credibility = credibility == null ? RerankSourceCredibility.UNVERIFIED : credibility;
            this.propsOfficial = propsOfficial;
            this.propsDocs = propsDocs;
            this.propsDevCommunity = propsDevCommunity;
            this.profileOfficial = profileOfficial;
            this.profileDocs = profileDocs;
            this.profileDevCommunity = profileDevCommunity;
            this.profileWhitelist = profileWhitelist;
            this.denyDevCommunity = denyDevCommunity;
        }
    }

    /**
     * Stage-based credibility boost.
     *
     * <p>
     * Motivation: DomainProfile/props routing can correctly classify a host as
     * OFFICIAL/DOCS even when {@code AuthorityScorer} has no entry and returns
     * {@code UNVERIFIED}. When {@code plan.minCitations} is enforced, this can
     * incorrectly look like "no citeable" and prematurely trigger
     * {@code starvationFallback(BELOW_MIN_CITATIONS)}.
     */

    private RerankSourceCredibility boostCredForStage(WebFailSoftStage st, RerankSourceCredibility cred) {
        RerankSourceCredibility c = (cred == null) ? RerankSourceCredibility.UNVERIFIED : cred;
        if (c != RerankSourceCredibility.UNVERIFIED) {
            return c;
        }

        // Policy gate: some deployments may require that "credibility" remains purely
        // scorer-driven. In that case, disable this boost and rely on stage routing
        // only.
        boolean enabled = true;
        String mode = "CONSERVATIVE";
        try {
            if (props != null) {
                enabled = props.isStageBasedCredibilityBoostEnabled();
                String m = props.getStageBasedCredibilityBoostMode();
                if (m != null && !m.isBlank()) {
                    mode = m.trim().toUpperCase(java.util.Locale.ROOT);
                }
            }
        } catch (Throwable ignore) {
            // fail-soft: keep defaults
        }

        if (!enabled) {
            try {
                TraceStore.putIfAbsent("web.failsoft.credibilityBoost.enabled", false);
            } catch (Throwable ignore) {
            }
            return c;
        }

        // Conservative default: satisfy minCitations (cred != UNVERIFIED) while
        // avoiding
        // over-claiming OFFICIAL when the scorer lacks entries.
        RerankSourceCredibility boosted = c;
        if (st == WebFailSoftStage.OFFICIAL) {
            boosted = ("AGGRESSIVE".equals(mode))
                    ? RerankSourceCredibility.OFFICIAL
                    : RerankSourceCredibility.TRUSTED;
        } else if (st == WebFailSoftStage.DOCS) {
            boosted = RerankSourceCredibility.TRUSTED;
        }

        if (boosted != c) {
            try {
                TraceStore.putIfAbsent("web.failsoft.credibilityBoost.enabled", true);
                TraceStore.putIfAbsent("web.failsoft.credibilityBoost.mode", mode);
                TraceStore.inc("web.failsoft.credibilityBoost.count");
                if (st != null) {
                    TraceStore
                            .inc("web.failsoft.credibilityBoost.count." + st.name().toLowerCase(java.util.Locale.ROOT));
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        return boosted;
    }

    /**
     * Stage classification that tries to keep "domain → stage" wiring stable.
     *
     * <p>
     * Priority:
     * <ol>
     * <li>AuthorityScorer OFFICIAL/TRUSTED → OFFICIAL/DOCS (fixes vendor docs not
     * present in suffix lists)</li>
     * <li>DomainProfileLoader (official/docs/dev_community) when available</li>
     * <li>Configured suffix lists in NovaWebFailSoftProperties</li>
     * <li>Fallback NOFILTER_SAFE</li>
     * </ol>
     *
     * <p>
     * <b>Important:</b> COMMUNITY credibility does <i>not</i> automatically imply
     * DEV_COMMUNITY stage.
     * Only strong dev community hosts (profile/devCommunityDomains) should be
     * routed there.
     * </p>
     */
    private StageDecision classifyDetailed(WebSnippet sn, @Nullable String whitelistProfile) {
        String host = sn == null ? null : sn.host();
        if (host == null || host.isBlank()) {
            return new StageDecision(WebFailSoftStage.NOFILTER_SAFE, "no-host", RerankSourceCredibility.UNVERIFIED,
                    false, false, false, false, false, false, false, false);
        }

        String url = sn.url();
        String h = host.toLowerCase(Locale.ROOT);

        boolean propsOfficial = matchesAny(h, props.getOfficialDomains());
        boolean propsDocs = matchesAny(h, props.getDocsDomains());
        boolean propsDevCommunity = matchesAny(h, props.getDevCommunityDomains());
        boolean denyDevCommunity = matchesAny(h, props.getDevCommunityDenyDomains());

        boolean profileOfficial = false;
        boolean profileDocs = false;
        boolean profileDevCommunity = false;
        boolean profileWhitelist = false;

        if (domainProfileLoader != null && url != null && !url.isBlank()) {
            try {
                profileOfficial = domainProfileLoader.isAllowedByProfile(url, "official");
                profileDocs = domainProfileLoader.isAllowedByProfile(url, "docs");
                profileDevCommunity = domainProfileLoader.isAllowedByProfile(url, "dev_community");
                if (whitelistProfile != null) {
                    profileWhitelist = domainProfileLoader.isAllowedByProfile(url, whitelistProfile);
                }
            } catch (Exception ignore) {
                // fail-soft
            }
        }

        RerankSourceCredibility cred = credibility(sn);

        // 1) Authority-based correction (prevents OFFICIAL vendor docs drifting into
        // DEV_COMMUNITY/NOFILTER)
        if (cred == RerankSourceCredibility.OFFICIAL) {
            return new StageDecision(WebFailSoftStage.OFFICIAL, "authority:OFFICIAL", cred,
                    propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    profileWhitelist, denyDevCommunity);
        }
        if (cred == RerankSourceCredibility.TRUSTED) {
            return new StageDecision(WebFailSoftStage.DOCS, "authority:TRUSTED", cred,
                    propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    profileWhitelist, denyDevCommunity);
        }

        // 2) DomainProfileLoader-based routing (more configurable than hard-coded
        // suffix lists)
        if (profileOfficial) {
            RerankSourceCredibility boosted = boostCredForStage(WebFailSoftStage.OFFICIAL, cred);
            return new StageDecision(WebFailSoftStage.OFFICIAL, "profile:official", boosted,
                    propsOfficial, propsDocs, propsDevCommunity, true, profileDocs, profileDevCommunity,
                    profileWhitelist, denyDevCommunity);
        }
        if (profileDocs) {
            RerankSourceCredibility boosted = boostCredForStage(WebFailSoftStage.DOCS, cred);
            return new StageDecision(WebFailSoftStage.DOCS, "profile:docs", boosted,
                    propsOfficial, propsDocs, propsDevCommunity, profileOfficial, true, profileDevCommunity,
                    profileWhitelist, denyDevCommunity);
        }
        if (profileDevCommunity) {
            if (denyDevCommunity) {
                return new StageDecision(WebFailSoftStage.NOFILTER_SAFE, "deny:dev_community(profile)", cred,
                        propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, true,
                        profileWhitelist, true);
            }
            String by = (cred == RerankSourceCredibility.COMMUNITY) ? "profile:dev_community+cred:COMMUNITY"
                    : "profile:dev_community";
            return new StageDecision(WebFailSoftStage.DEV_COMMUNITY, by, cred,
                    propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    profileWhitelist, false);
        }
        if (profileWhitelist) {
            return new StageDecision(WebFailSoftStage.PROFILEBOOST, "profile:" + whitelistProfile, cred,
                    propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    true, denyDevCommunity);
        }

        // 3) Configured domain suffix lists (fallback)
        if (propsOfficial) {
            RerankSourceCredibility boosted = boostCredForStage(WebFailSoftStage.OFFICIAL, cred);
            return new StageDecision(WebFailSoftStage.OFFICIAL, "props:officialDomains", boosted,
                    true, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    profileWhitelist, denyDevCommunity);
        }
        if (propsDocs) {
            RerankSourceCredibility boosted = boostCredForStage(WebFailSoftStage.DOCS, cred);
            return new StageDecision(WebFailSoftStage.DOCS, "props:docsDomains", boosted,
                    propsOfficial, true, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    profileWhitelist, denyDevCommunity);
        }
        if (propsDevCommunity) {
            if (denyDevCommunity) {
                return new StageDecision(WebFailSoftStage.NOFILTER_SAFE, "deny:devCommunityDomains", cred,
                        propsOfficial, propsDocs, true, profileOfficial, profileDocs, profileDevCommunity,
                        profileWhitelist, true);
            }
            String by = (cred == RerankSourceCredibility.COMMUNITY) ? "props:devCommunityDomains+cred:COMMUNITY"
                    : "props:devCommunityDomains";
            return new StageDecision(WebFailSoftStage.DEV_COMMUNITY, by, cred,
                    propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                    profileWhitelist, false);
        }

        return new StageDecision(WebFailSoftStage.NOFILTER_SAFE, "fallback", cred,
                propsOfficial, propsDocs, propsDevCommunity, profileOfficial, profileDocs, profileDevCommunity,
                profileWhitelist, denyDevCommunity);
    }

    private RerankSourceCredibility credibility(WebSnippet sn) {
        if (sn == null || sn.url() == null || sn.url().isBlank() || authorityScorer == null) {
            return RerankSourceCredibility.UNVERIFIED;
        }
        try {
            return authorityScorer.getSourceCredibility(sn.url());
        } catch (Exception e) {
            return RerankSourceCredibility.UNVERIFIED;
        }
    }

    private static String formatSnippet(String raw,
            WebFailSoftStage stage,
            RerankSourceCredibility credibility,
            boolean tagSnippetBody) {
        String r = raw == null ? "" : raw;
        if (!tagSnippetBody) {
            return r;
        }
        // Avoid double tagging
        String trimmed = r.stripLeading();
        if (trimmed.startsWith("[WEB:") || trimmed.startsWith("[WS:") || trimmed.startsWith("[web:")) {
            return r;
        }
        String st = (stage == null) ? "NA" : stage.name();
        String cr = (credibility == null) ? "UNVERIFIED" : credibility.name();
        return "[WEB:" + st + "|CRED:" + cr + "] " + r;
    }

    /**
     * Count stage tags from the rendered output list.
     *
     * <p>
     * Motivation: in multi-run scenarios (extraSearchCalls), per-run keys can be
     * clobbered
     * by later empty runs. This provides a "ground truth" derived from the out list
     * itself.
     * </p>
     */
    private static Map<String, Integer> countStagesFromOut(@Nullable List<String> out) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WebFailSoftStage st : WebFailSoftStage.values()) {
            counts.put(st.name(), 0);
        }
        if (out == null || out.isEmpty()) {
            return counts;
        }
        for (String s : out) {
            String st = parseStageTag(s);
            if (st == null) {
                continue;
            }
            Integer cur = counts.get(st);
            if (cur != null) {
                counts.put(st, cur + 1);
            }
        }
        return counts;
    }

    @Nullable
    private static String parseStageTag(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.stripLeading();
        if (!(t.startsWith("[WEB:") || t.startsWith("[WS:") || t.startsWith("[web:") || t.startsWith("[ws:"))) {
            return null;
        }
        int colon = t.indexOf(':');
        if (colon < 0) {
            return null;
        }
        int end = t.indexOf('|', colon + 1);
        if (end < 0) {
            end = t.indexOf(']', colon + 1);
        }
        if (end < 0) {
            return null;
        }
        String st = t.substring(colon + 1, end).trim();
        if (st.isBlank()) {
            return null;
        }
        return st.toUpperCase(Locale.ROOT);
    }

    @Nullable
    private static String parseCredTag(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.stripLeading();
        int idx = t.indexOf("|CRED:");
        if (idx < 0) {
            return null;
        }
        int start = idx + "|CRED:".length();
        int end = t.indexOf(']', start);
        if (end < 0) {
            return null;
        }
        String cr = t.substring(start, end).trim();
        if (cr.isBlank()) {
            return null;
        }
        return cr.toUpperCase(Locale.ROOT);
    }

    /**
     * Interpret a tagged snippet output line and decide if it contributes to
     * minCitations.
     *
     * <p>
     * We keep this derived-from-output helper small and best-effort: it is only
     * used
     * for tail-drop eviction bookkeeping in citeableTopUp.
     * </p>
     */
    private static boolean countsTowardMinCitationsFromTaggedOutput(@Nullable String raw) {
        String st = parseStageTag(raw);
        if (st == null || st.isBlank()) {
            return false;
        }
        WebFailSoftStage stage;
        try {
            stage = WebFailSoftStage.valueOf(st);
        } catch (Exception ignore) {
            return false;
        }
        String cr = parseCredTag(raw);
        RerankSourceCredibility cred;
        try {
            cred = (cr == null || cr.isBlank()) ? RerankSourceCredibility.UNVERIFIED
                    : RerankSourceCredibility.valueOf(cr);
        } catch (Exception ignore) {
            cred = RerankSourceCredibility.UNVERIFIED;
        }
        return countsTowardMinCitations(stage, cred);
    }

    /**
     * Tail-drop eviction to make room for citeableTopUp inserts.
     *
     * <p>
     * Policy: evict the latest element that does NOT count toward minCitations.
     * This prevents "out list full" from blocking OFFICIAL/DOCS top-up.
     * </p>
     */
    private static boolean tryEvictTailNonCiteableForCiteableInsert(
            List<String> out,
            EnumMap<WebFailSoftStage, Integer> selectedCounts) {
        if (out == null || out.isEmpty()) {
            return false;
        }
        for (int i = out.size() - 1; i >= 0; i--) {
            String s = out.get(i);
            if (countsTowardMinCitationsFromTaggedOutput(s)) {
                continue;
            }
            String st = parseStageTag(s);
            if (st != null && !st.isBlank() && selectedCounts != null) {
                try {
                    WebFailSoftStage stage = WebFailSoftStage.valueOf(st);
                    Integer cur = selectedCounts.get(stage);
                    if (cur != null && cur > 0) {
                        selectedCounts.put(stage, cur - 1);
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            }
            out.remove(i);
            return true;
        }
        return false;
    }

    /**
     * Best-effort lookup for a candidate meta-map by key. For duplicate keys we
     * prefer the first unselected/unconsidered.
     */
    @Nullable
    private static Map<String, Object> pickCandidateForKey(Map<String, List<Map<String, Object>>> candidatesByKey,
            String key) {
        if (candidatesByKey == null || key == null || key.isBlank())
            return null;
        List<Map<String, Object>> list = candidatesByKey.get(key);
        if (list == null || list.isEmpty())
            return null;
        for (Map<String, Object> cand : list) {
            if (cand == null)
                continue;
            if (!Boolean.TRUE.equals(cand.get("selected")) && !Boolean.TRUE.equals(cand.get("considered"))) {
                return cand;
            }
        }
        for (Map<String, Object> cand : list) {
            if (cand == null)
                continue;
            if (!Boolean.TRUE.equals(cand.get("selected"))) {
                return cand;
            }
        }
        return list.get(0);
    }

    private static List<String> tokenizeForHits(String query) {
        if (query == null || query.isBlank())
            return List.of();

        String norm = query
                .replaceAll("[-]+", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
        if (norm.isBlank())
            return List.of();

        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String tok : norm.split("\\s+")) {
            if (tok == null)
                continue;
            String t = tok.trim();
            if (t.isBlank())
                continue;
            if (t.length() < 2)
                continue;
            // skip obvious stop-like fragments
            if ("and".equals(t) || "or".equals(t) || "the".equals(t))
                continue;
            uniq.add(t);
            if (uniq.size() >= 20)
                break;
        }
        return new ArrayList<>(uniq);
    }

    private static List<String> findTokenHits(WebSnippet sn, List<String> tokens) {
        if (sn == null || sn.lower() == null || tokens == null || tokens.isEmpty())
            return List.of();
        String lower = sn.lower();
        ArrayList<String> hits = new ArrayList<>();
        for (String t : tokens) {
            if (t == null || t.isBlank())
                continue;
            if (lower.contains(t)) {
                hits.add(t);
                if (hits.size() >= 12)
                    break;
            }
        }
        return hits;
    }

    private static List<String> findNegHits(WebSnippet sn, Set<String> negTerms) {
        if (sn == null || sn.lower() == null || negTerms == null || negTerms.isEmpty())
            return List.of();
        String lower = sn.lower();
        ArrayList<String> hits = new ArrayList<>();
        for (String n : negTerms) {
            if (n == null || n.isBlank())
                continue;
            String term = n.toLowerCase(Locale.ROOT);
            if (lower.contains(term)) {
                hits.add(term);
                if (hits.size() >= 12)
                    break;
            }
        }
        return hits;
    }

    private static int scoreStage(WebFailSoftStage stage) {
        if (stage == null)
            return 0;
        return switch (stage) {
            case OFFICIAL -> 100;
            case DOCS -> 80;
            case PROFILEBOOST -> 70;
            case DEV_COMMUNITY -> 60;
            case NOFILTER_SAFE -> 40;
            case NOFILTER -> 20;
        };
    }

    private static int scoreCred(RerankSourceCredibility cred) {
        if (cred == null)
            return 0;
        return switch (cred) {
            case OFFICIAL -> 50;
            case TRUSTED -> 30;
            case COMMUNITY -> 15;
            case UNVERIFIED -> 0;
        };
    }

    private static String normalizeFallbackTrigger(String trigger) {
        if (trigger == null)
            return "EMPTY_ONLY";
        String t = trigger.trim().toUpperCase(Locale.ROOT);
        if ("BELOW_MIN_CITATIONS".equals(t))
            return "BELOW_MIN_CITATIONS";
        return "EMPTY_ONLY";
    }

    private static boolean isFallbackIntentAllowed(@Nullable RuleBasedQueryAugmenter.Augment aug,
            @Nullable List<String> allowedIntents) {
        if (aug == null || aug.intent() == null)
            return false;
        if (allowedIntents == null || allowedIntents.isEmpty())
            return false;
        String intentName = aug.intent().name();
        for (String a : allowedIntents) {
            if (a == null)
                continue;
            if (a.trim().equalsIgnoreCase(intentName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCiteableStage(WebFailSoftStage stage) {
        if (stage == null) {
            return false;
        }
        return stage == WebFailSoftStage.OFFICIAL
                || stage == WebFailSoftStage.DOCS
                || stage == WebFailSoftStage.DEV_COMMUNITY;
    }

    /**
     * Counts toward minCitations only when the snippet is both:
     * - from a citeable stage (OFFICIAL/DOCS/DEV_COMMUNITY)
     * - and has credibility != UNVERIFIED (OFFICIAL/DOCS are stage-boosted to
     * TRUSTED when missing)
     */
    /**
     * Stage-based credibility boost used only for minCitations accounting.
     *
     * <p>
     * Rationale: in practice OFFICIAL/DOCS stages are already filtered by
     * domain/stage heuristics.
     * If a provider returns no credibility metadata, treating those as UNVERIFIED
     * causes an
     * accidental "BELOW_MIN_CITATIONS" starvation fallback (officialOnly ->
     * NOFILTER_SAFE), even
     * though we do have citeable sources.
     * </p>
     */
    private static RerankSourceCredibility boostCredibilityForStage(WebFailSoftStage stage,
            RerankSourceCredibility cred) {
        RerankSourceCredibility c = (cred == null) ? RerankSourceCredibility.UNVERIFIED : cred;
        if (stage == WebFailSoftStage.OFFICIAL || stage == WebFailSoftStage.DOCS) {
            if (c == RerankSourceCredibility.UNVERIFIED) {
                return RerankSourceCredibility.TRUSTED;
            }
        }
        return c;
    }

    private static boolean countsTowardMinCitations(WebFailSoftStage stage, RerankSourceCredibility cred) {
        if (!isCiteableStage(stage)) {
            return false;
        }
        RerankSourceCredibility boosted = boostCredibilityForStage(stage, cred);
        return boosted != RerankSourceCredibility.UNVERIFIED;
    }

    /**
     * Gate the officialOnly starvation fallback without deleting legacy behavior.
     * Adds trace keys for visibility:
     * -
     * web.failsoft.starvationFallback.enabled/trigger/max/intentAllowed/skipReason
     */
    private boolean shouldStarvationFallback(boolean officialOnly,
            boolean highRisk,
            RuleBasedQueryAugmenter.Augment aug,
            List<String> out,
            int minCitations,
            int topK,
            List<TaggedSnippet> safeCandidates,
            int citeableCount) {
        boolean enabled = props.isOfficialOnlyStarvationFallbackEnabled();
        String trigger = normalizeFallbackTrigger(props.getOfficialOnlyStarvationFallbackTrigger());
        int cappedMax = Math.max(0, Math.min(props.getOfficialOnlyStarvationFallbackMax(), topK));
        boolean intentAllowed = isFallbackIntentAllowed(aug, props.getOfficialOnlyStarvationFallbackAllowedIntents());

        boolean triggerMet;
        boolean deferForMinCitationsRescue = false;
        if ("BELOW_MIN_CITATIONS".equals(trigger)) {
            int min = Math.max(0, minCitations);
            triggerMet = (out == null || out.isEmpty()) || (citeableCount < min);

            // If we already have some output but are just short on citations, prefer an
            // OFFICIAL/DOCS top-up via extra search calls before relaxing to NOFILTER_SAFE.
            boolean belowMinButNonEmpty = (out != null && !out.isEmpty()) && (citeableCount < min);
            boolean rescuePossible = props.isAllowExtraSearchCalls()
                    && props.getMaxExtraSearchCalls() > 0
                    && aug != null
                    && aug.queries() != null
                    && !aug.queries().isEmpty();
            if (officialOnly && !highRisk && enabled && intentAllowed && belowMinButNonEmpty && rescuePossible) {
                deferForMinCitationsRescue = true;
            }
        } else {
            triggerMet = (out == null || out.isEmpty());
        }

        boolean hasCandidates = safeCandidates != null && !safeCandidates.isEmpty();
        boolean should = officialOnly && !highRisk && enabled && cappedMax > 0 && intentAllowed && triggerMet
                && hasCandidates && !deferForMinCitationsRescue;

        // Trace-only: make the config and gate outcome transparent.
        try {
            TraceStore.put("web.failsoft.starvationFallback.enabled", enabled);
            TraceStore.put("web.failsoft.starvationFallback.trigger", trigger);
            TraceStore.put("web.failsoft.starvationFallback.max", cappedMax);
            TraceStore.put("web.failsoft.starvationFallback.intentAllowed", intentAllowed);
            TraceStore.put("web.failsoft.starvationFallback.highRisk", highRisk);
            TraceStore.put("web.failsoft.starvationFallback.deferred", deferForMinCitationsRescue);
            if (deferForMinCitationsRescue) {
                TraceStore.put("web.failsoft.starvationFallback.deferredReason", "below_min_citations_extra_search");
            }

            if (officialOnly) {
                if (!should) {
                    String reason;
                    if (deferForMinCitationsRescue) {
                        reason = "deferred_to_extra_search";
                    } else if (!enabled || cappedMax <= 0) {
                        reason = "disabled";
                    } else if (highRisk) {
                        reason = "high_risk";
                    } else if (!intentAllowed) {
                        reason = "intent_denied";
                    } else if (!triggerMet) {
                        reason = "trigger_not_met";
                    } else if (!hasCandidates) {
                        reason = "no_candidates";
                    } else {
                        reason = "trigger_not_met";
                    }
                    TraceStore.put("web.failsoft.starvationFallback.skipReason", reason);
                } else {
                    // Clear any prior value from earlier stages in the same trace
                    TraceStore.put("web.failsoft.starvationFallback.skipReason", null);
                }
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        return should;
    }

    private void recordFailSoftMetrics(boolean officialOnly,
            RuleBasedQueryAugmenter.Augment aug,
            List<String> out,
            Map<String, Integer> stageCountsSelected,
            List<Map<String, Object>> candidateDiagnostics) {
        if (meterRegistry == null)
            return;
        try {
            String intent = (aug == null || aug.intent() == null) ? "UNKNOWN" : aug.intent().name();
            String off = officialOnly ? "true" : "false";

            meterRegistry.counter(METRIC_RUN, "intent", intent, "officialOnly", off).increment();

            if (out == null || out.isEmpty()) {
                meterRegistry.counter(METRIC_OUT_ZERO, "intent", intent, "officialOnly", off).increment();
            }

            Object fb = TraceStore.get("web.failsoft.starvationFallback");
            if (fb != null) {
                meterRegistry.counter(METRIC_STARVATION_FALLBACK, "intent", intent, "officialOnly", off).increment();
            } else if (officialOnly) {
                String reason = stringOrNull(TraceStore.get("web.failsoft.starvationFallback.skipReason"));
                if (reason != null && !reason.isBlank()) {
                    meterRegistry
                            .counter(METRIC_STARVATION_FALLBACK_SKIPPED, "reason", reason, "intent", intent,
                                    "officialOnly", off)
                            .increment();
                }
            }

            if (stageCountsSelected != null && !stageCountsSelected.isEmpty()) {
                for (Map.Entry<String, Integer> e : stageCountsSelected.entrySet()) {
                    if (e == null || e.getKey() == null || e.getKey().isBlank())
                        continue;
                    int c = (e.getValue() == null) ? 0 : e.getValue();
                    if (c <= 0)
                        continue;
                    meterRegistry
                            .counter(METRIC_STAGE_SELECTED, "stage", e.getKey(), "intent", intent, "officialOnly", off)
                            .increment(c);
                }
            }

            if (candidateDiagnostics != null && !candidateDiagnostics.isEmpty()) {
                for (Map<String, Object> cand : candidateDiagnostics) {
                    if (cand == null)
                        continue;

                    boolean selected = Boolean.TRUE.equals(cand.get("selected"));
                    String stageUsed = stringOrNull(cand.get("stage"));
                    String stageFinal = stringOrNull(cand.get("stageFinal"));
                    String stageLabel = (stageFinal == null || stageFinal.isBlank())
                            ? (stageUsed == null ? "" : stageUsed)
                            : stageFinal;

                    String dropReason = stringOrNull(cand.get("dropReason"));
                    if (!selected && dropReason != null && !dropReason.isBlank()) {
                        meterRegistry
                                .counter(METRIC_CANDIDATE_DROP, "reason", dropReason, "stage", stageLabel, "intent",
                                        intent, "officialOnly", off)
                                .increment();
                    }

                    Object scoreObj = cand.get("score");
                    if (scoreObj != null) {
                        try {
                            double score = Double.parseDouble(String.valueOf(scoreObj));
                            meterRegistry.summary(METRIC_CANDIDATE_SCORE,
                                    "stage", stageLabel,
                                    "selected", selected ? "true" : "false",
                                    "intent", intent,
                                    "officialOnly", off)
                                    .record(score);
                        } catch (Exception ignore) {
                            // ignore parse errors
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // fail-soft
        }
    }

    private static List<Map<String, Object>> buildHitDetails(WebSnippet sn, List<String> terms, int maxTerms,
            int window) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sn == null || terms == null || terms.isEmpty()) {
            return out;
        }
        String lower = sn.lower();
        if (lower == null || lower.isBlank()) {
            return out;
        }
        String raw = sn.raw();
        boolean rawAligned = raw != null && raw.length() == lower.length();
        String baseText = (rawAligned && raw != null) ? raw : lower;

        int added = 0;
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String t = term.toLowerCase(Locale.ROOT);
            int pos = lower.indexOf(t);
            if (pos < 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("term", safeTrim(term, 80));
            m.put("pos", pos);
            m.put("slice", safeTrim(sliceAround(baseText, pos, term.length(), window), 240));
            out.add(m);
            added += 1;
            if (added >= maxTerms) {
                break;
            }
        }
        return out;
    }

    private static String sliceAround(String text, int pos, int termLen, int window) {
        if (text == null || text.isEmpty() || pos < 0)
            return "";
        int start = Math.max(0, pos - window);
        int end = Math.min(text.length(), pos + termLen + window);
        String slice = text.substring(start, end);
        if (start > 0)
            slice = "…" + slice;
        if (end < text.length())
            slice = slice + "…";
        return slice.replace("\n", " ").replace("\r", " ");
    }

    @SuppressWarnings("unchecked")
    private static void appendDecisionChainStep(Map<String, Object> cand, Map<String, Object> step) {
        if (cand == null || step == null || step.isEmpty()) {
            return;
        }
        Object v = cand.get("decisionChain");
        List<Map<String, Object>> chain;
        if (v instanceof List) {
            chain = (List<Map<String, Object>>) v;
        } else {
            chain = new ArrayList<>();
            cand.put("decisionChain", chain);
        }

        // Avoid payload explosions.
        if (chain.size() >= 16) {
            return;
        }

        // Avoid obvious duplicates (e.g., finalize loop running after a drop was
        // already marked).
        if (!chain.isEmpty()) {
            Map<String, Object> last = chain.get(chain.size() - 1);
            if (Objects.equals(last.get("step"), step.get("step"))
                    && Objects.equals(last.get("reason"), step.get("reason"))) {
                return;
            }
        }

        chain.add(step);
    }

    private boolean isTechSpam(WebSnippet sn, RuleBasedQueryAugmenter.Augment aug) {
        if (aug == null || aug.intent() != RuleBasedQueryAugmenter.Intent.TECH_API)
            return false;
        if (sn == null || sn.lower() == null)
            return false;

        // keyword spam
        if (RuleBasedQueryAugmenter.containsAny(sn.lower(), props.getTechSpamKeywords()))
            return true;

        // domain spam (optional)
        if (sn.host() != null && matchesAny(sn.host(), props.getTechSpamDomains()))
            return true;

        return false;
    }

    private static boolean matchesAny(String host, List<String> suffixes) {
        if (host == null || host.isBlank() || suffixes == null || suffixes.isEmpty())
            return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String s : suffixes) {
            if (s == null || s.isBlank())
                continue;
            String suf = s.toLowerCase(Locale.ROOT);
            if (h.equals(suf) || h.endsWith("." + suf))
                return true;
        }
        return false;
    }

    private static List<WebFailSoftStage> parseStageOrder(List<String> stageNames) {
        // Default order is intentionally "safe-first" and roughly matches the
        // production search stack.
        List<WebFailSoftStage> def = List.of(WebFailSoftStage.OFFICIAL, WebFailSoftStage.DOCS,
                WebFailSoftStage.DEV_COMMUNITY,
                WebFailSoftStage.PROFILEBOOST, WebFailSoftStage.NOFILTER_SAFE, WebFailSoftStage.NOFILTER);

        if (stageNames == null || stageNames.isEmpty()) {
            return def;
        }

        // Clamp/normalize:
        // - ignore unknown stages instead of failing the whole config
        // - upper-case for robustness
        // - de-duplicate while preserving the configured order
        java.util.LinkedHashSet<WebFailSoftStage> uniq = new java.util.LinkedHashSet<>();
        for (String raw : stageNames) {
            if (raw == null) {
                continue;
            }
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                uniq.add(WebFailSoftStage.valueOf(s.toUpperCase(java.util.Locale.ROOT)));
            } catch (Exception ignore) {
                // ignore unknown token
            }
        }

        List<WebFailSoftStage> out = new java.util.ArrayList<>(uniq);
        return out.isEmpty() ? def : out;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object o) {
        return (List<T>) o;
    }

    /**
     * Normalize {@code web.await.events} into a string-friendly summary.
     *
     * <p>
     * Some renderers/diagnostic tables only display {@code List<String>} cleanly.
     * HybridWebSearchProvider stores await events as {@code List<Map<..>>} for rich
     * HTML trace output; this helper keeps a compact projection in trace keys so
     * merge-boundary failures (timeouts / missing_future / budget_exhausted) remain
     * visible even when the UI cannot render Map payloads.
     * </p>
     */

    /**
     * Operational normalization: reclassify scheduler/intentional-cancel await
     * events as SOFT.
     *
     * <p>
     * In production traces we often see a mix of real provider failures (timeouts)
     * and
     * orchestration-side decisions (budget exhaustion) or cancellations
     * (interrupt/cancel).
     * Those should not be treated as <em>hard</em> provider failures; otherwise
     * breaker/fail-soft
     * logic gets noisy and can cascade.
     * </p>
     *
     * <p>
     * This method only mutates trace events (no behavior changes). Fail-soft by
     * design.
     * </p>
     */
    private static void normalizeAwaitEventsForOps(@Nullable List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Map<String, Object> ev : list) {
            if (ev == null) {
                continue;
            }

            String stageS = stringOrNull(firstNonNull(ev.get("stage"), ev.get("kind")));
            String causeS = stringOrNull(firstNonNull(ev.get("cause"), ev.get("reason"), ev.get("status")));
            String errS = stringOrNull(ev.get("err"));

            if (causeS == null) {
                causeS = "";
            }
            String causeLower = causeS.trim().toLowerCase(Locale.ROOT);
            if (causeLower.startsWith("skip_")) {
                // Already classified as an intentional skip.
                continue;
            }

            String errLower = (errS == null) ? "" : errS.trim().toLowerCase(Locale.ROOT);

            boolean isBudgetExhausted = "budget_exhausted".equals(causeLower);
            boolean isInterrupted = "interrupted".equals(causeLower) || errLower.contains("interrupted");
            boolean isCanceled = causeLower.contains("cancel") || errLower.contains("cancel");
            boolean isIntentionalCancel = isInterrupted || isCanceled;

            // Reclassify only when the original stage is hard (or missing). Do not touch
            // soft.
            boolean wasHard = (stageS == null || stageS.isBlank() || "hard".equalsIgnoreCase(stageS));

            if (wasHard && (isBudgetExhausted || isIntentionalCancel)) {
                try {
                    ev.put("stage", "soft");
                    // These are not provider faults; avoid inflating nonOk counts.
                    ev.put("nonOk", false);
                    // Ensure skip is false so the UI still shows them (some renderers hide skip).
                    ev.put("skip", false);

                    if (isBudgetExhausted) {
                        // Keep cause as-is.
                        TraceStore.inc("web.await.reclassify.budget_exhausted.soft");
                    } else {
                        // Preserve the original cause for debugging.
                        if (!causeLower.equals("intentional_cancel")) {
                            ev.putIfAbsent("causeRaw", causeS);
                        }
                        ev.put("cause", "intentional_cancel");
                        TraceStore.inc("web.await.reclassify.intentional_cancel.soft");
                    }
                } catch (Exception ignore) {
                    // fail-soft
                }
            }
        }
    }

    private static void summarizeAwaitEventsForTrace() {
        List<Map<String, Object>> list = null;
        try {
            Object raw = TraceStore.get("web.await.events");
            list = castList(raw);
        } catch (Exception ignore) {
            // ignore
        }

        // Operational reclassification: budget_exhausted / intentional_cancel should be
        // SOFT.
        try {
            normalizeAwaitEventsForOps(list);
        } catch (Exception ignore) {
            // fail-soft
        }

        // Collect skipped counters even if the events list is missing.
        Map<String, Object> skippedCtx = new LinkedHashMap<>();
        Map<String, Object> ctx = null;
        try {
            ctx = TraceStore.context();
        } catch (Throwable ignore) {
            ctx = null;
        }
        if (ctx != null && !ctx.isEmpty()) {
            for (Map.Entry<String, Object> e : ctx.entrySet()) {
                if (e == null)
                    continue;
                String k = e.getKey();
                if (k == null)
                    continue;

                if (k.startsWith("web.await.skipped.")) {
                    if (k.endsWith(".count") || k.endsWith(".last") || k.endsWith(".last.engine")
                            || k.endsWith(".last.reason") || k.endsWith(".last.step")) {
                        skippedCtx.put(k, e.getValue());
                    }
                }
                if ("web.await.skipped.last".equals(k)
                        || "web.await.skipped.last.engine".equals(k)
                        || "web.await.skipped.last.reason".equals(k)
                        || "web.await.skipped.last.step".equals(k)) {
                    skippedCtx.put(k, e.getValue());
                }
            }
        }

        boolean hasEvents = list != null && !list.isEmpty();
        boolean hasSkipped = skippedCtx != null && !skippedCtx.isEmpty();
        if (!hasEvents && !hasSkipped) {
            return;
        }

        // A) events -> summary
        if (hasEvents) {
            try {
                // Keep only simple keys to avoid huge payloads.
                List<Map<String, Object>> simplified = new ArrayList<>();
                for (Map<String, Object> ev : list) {
                    if (ev == null)
                        continue;
                    Map<String, Object> m2 = new LinkedHashMap<>();

                    Object engine = firstNonNull(ev.get("engine"), ev.get("provider"));
                    Object stage = firstNonNull(ev.get("stage"), ev.get("kind"));
                    Object step = firstNonNull(ev.get("step"), ev.get("op"), ev.get("kind2"), ev.get("phase"));
                    Object cause = firstNonNull(ev.get("cause"), ev.get("reason"), ev.get("status"));
                    Object waitedMs = ev.get("waitedMs");
                    Object timeoutMs = ev.get("timeoutMs");
                    Object skip = ev.get("skip");
                    Object timeout = ev.get("timeout");
                    Object softTimeout = ev.get("softTimeout");
                    Object hardTimeout = ev.get("hardTimeout");
                    Object nonOk = ev.get("nonOk");

                    if (engine != null)
                        m2.put("engine", String.valueOf(engine));
                    if (stage != null)
                        m2.put("stage", String.valueOf(stage));
                    if (step != null)
                        m2.put("step", String.valueOf(step));
                    if (cause != null)
                        m2.put("cause", String.valueOf(cause));
                    if (waitedMs != null)
                        m2.put("waitedMs", waitedMs);
                    if (timeoutMs != null)
                        m2.put("timeoutMs", timeoutMs);
                    if (skip != null)
                        m2.put("skip", skip);
                    if (timeout != null)
                        m2.put("timeout", timeout);
                    if (softTimeout != null)
                        m2.put("softTimeout", softTimeout);
                    if (hardTimeout != null)
                        m2.put("hardTimeout", hardTimeout);
                    if (nonOk != null)
                        m2.put("nonOk", nonOk);

                    simplified.add(m2);
                }

                TraceStore.put("web.await.events.summary.count", simplified.size());

                long nonOkCount = 0L;
                long timeoutCount = 0L;
                long softTimeoutCount = 0L;
                long hardTimeoutCount = 0L;
                long skipCount = 0L;
                long interruptedCount = 0L;
                long budgetExhaustedCount = 0L;
                long missingFutureCount = 0L;

                // waitedMs=0 + (intentional-cancel/interrupt) => likely thread interrupt
                // residual.
                long intentionalCancelWaitedMsZeroCount = 0L;
                LinkedHashSet<String> intentionalCancelWaitedMsZeroEngines = new LinkedHashSet<>();

                long maxWaited = 0L;
                long maxTimeout = 0L;

                LinkedHashSet<String> engines = new LinkedHashSet<>();
                Map<String, Integer> engineCounts = new LinkedHashMap<>();
                Map<String, Integer> causeCounts = new LinkedHashMap<>();
                Map<String, Integer> stepCounts = new LinkedHashMap<>();
                Map<String, Integer> engineCauseCounts = new LinkedHashMap<>();

                List<String> digests = new ArrayList<>();

                for (Map<String, Object> ev : simplified) {
                    String eng = stringOrNull(ev.get("engine"));
                    if (eng == null)
                        eng = "?";
                    String stageS = stringOrNull(ev.get("stage"));
                    if (stageS == null)
                        stageS = "";
                    String stepS = stringOrNull(ev.get("step"));
                    if (stepS == null)
                        stepS = "";
                    String causeS = stringOrNull(ev.get("cause"));
                    if (causeS == null)
                        causeS = "";

                    engines.add(eng);
                    engineCounts.put(eng, engineCounts.getOrDefault(eng, 0) + 1);
                    if (!stepS.isBlank()) {
                        stepCounts.put(stepS, stepCounts.getOrDefault(stepS, 0) + 1);
                    }
                    if (!causeS.isBlank()) {
                        causeCounts.put(causeS, causeCounts.getOrDefault(causeS, 0) + 1);
                        String ec = eng + "|" + causeS;
                        engineCauseCounts.put(ec, engineCauseCounts.getOrDefault(ec, 0) + 1);
                    }

                    boolean nonOk = isTruthy(ev.get("nonOk"));
                    boolean timeout = isTruthy(ev.get("timeout"));
                    boolean skip = isTruthy(ev.get("skip"));

                    boolean softTimeout = isTruthy(ev.get("softTimeout"));
                    boolean hardTimeout = isTruthy(ev.get("hardTimeout"));

                    String causeLower = causeS.toLowerCase(Locale.ROOT);
                    if ("budget_exhausted".equals(causeLower)) {
                        budgetExhaustedCount++;
                    }
                    if ("missing_future".equals(causeLower)) {
                        missingFutureCount++;
                        skip = true;
                    }
                    if ("interrupted".equals(causeLower)) {
                        interruptedCount++;
                    }
                    if (causeLower.startsWith("skip_")) {
                        skip = true;
                    }

                    // Backfill timeout kind when legacy traces don't carry softTimeout/hardTimeout.
                    if (!softTimeout && !hardTimeout) {
                        boolean stageSoft = "soft".equalsIgnoreCase(stageS);
                        boolean stageHard = "hard".equalsIgnoreCase(stageS);
                        if ("budget_exhausted".equals(causeLower) || "timeout_soft".equals(causeLower)) {
                            softTimeout = true;
                        } else if ("timeout_hard".equals(causeLower)) {
                            hardTimeout = true;
                        } else if (causeLower.contains("timeout")) {
                            if (stageSoft) {
                                softTimeout = true;
                            } else if (stageHard) {
                                hardTimeout = true;
                            } else {
                                // safest default: treat unknown "timeout" as hard timeout
                                hardTimeout = true;
                            }
                        }
                    }

                    if (causeLower.contains("timeout") || "budget_exhausted".equals(causeLower)) {
                        timeout = true;
                    }

                    boolean timeoutAny = timeout || softTimeout || hardTimeout;
                    if (timeoutAny) {
                        // Prefer soft classification when both are marked.
                        if (softTimeout) {
                            softTimeoutCount++;
                        } else if (hardTimeout) {
                            hardTimeoutCount++;
                        } else if ("soft".equalsIgnoreCase(stageS)) {
                            softTimeoutCount++;
                        } else if ("hard".equalsIgnoreCase(stageS)) {
                            hardTimeoutCount++;
                        }
                    }

                    if (nonOk)
                        nonOkCount++;
                    if (timeoutAny)
                        timeoutCount++;
                    if (skip)
                        skipCount++;

                    long waited = toLong(ev.get("waitedMs"));
                    long tmo = toLong(ev.get("timeoutMs"));
                    if (waited == 0L) {
                        if ("intentional_cancel".equals(causeLower)
                                || "interrupted".equals(causeLower)
                                || causeLower.contains("cancel")) {
                            intentionalCancelWaitedMsZeroCount++;
                            intentionalCancelWaitedMsZeroEngines.add(eng);
                        }
                    }

                    if (waited > 0)
                        maxWaited = Math.max(maxWaited, waited);
                    if (tmo > 0)
                        maxTimeout = Math.max(maxTimeout, tmo);

                    // Digest for quick scan.
                    StringBuilder sb = new StringBuilder();
                    sb.append(eng);
                    if (!stageS.isBlank()) {
                        sb.append(":").append(stageS);
                    }
                    if (!stepS.isBlank()) {
                        sb.append(":").append(stepS);
                    }
                    if (!causeS.isBlank()) {
                        sb.append(":").append(safeTrim(causeS, 80));
                    }
                    if (waited > 0) {
                        sb.append(":w=").append(waited);
                    }
                    if (tmo > 0) {
                        sb.append(":t=").append(tmo);
                    }
                    if (skip) {
                        sb.append(":skip");
                    }
                    if (timeout) {
                        sb.append(":timeout");
                    }
                    if (softTimeout) {
                        sb.append(":softTimeout");
                    }
                    if (hardTimeout) {
                        sb.append(":hardTimeout");
                    }
                    if (nonOk) {
                        sb.append(":nonOk");
                    }
                    digests.add(sb.toString());
                }

                TraceStore.put("web.await.events.summary.engines", String.join(",", engines));
                TraceStore.put("web.await.events.summary.nonOk.count", nonOkCount);
                TraceStore.put("web.await.events.summary.timeout.count", timeoutCount);
                TraceStore.put("web.await.events.summary.timeout.soft.count", softTimeoutCount);
                TraceStore.put("web.await.events.summary.timeout.hard.count", hardTimeoutCount);
                TraceStore.put("web.await.events.summary.skip.count", skipCount);
                TraceStore.put("web.await.events.summary.interrupted.count", interruptedCount);
                TraceStore.put("web.await.events.summary.budget_exhausted.count", budgetExhaustedCount);
                TraceStore.put("web.await.events.summary.missing_future.count", missingFutureCount);
                TraceStore.put("web.await.events.summary.intentional_cancel.waitedMs0.count",
                        intentionalCancelWaitedMsZeroCount);
                if (!intentionalCancelWaitedMsZeroEngines.isEmpty()) {
                    TraceStore.put("web.await.events.summary.intentional_cancel.waitedMs0.engines",
                            String.join(",", intentionalCancelWaitedMsZeroEngines));
                }
                TraceStore.put("web.await.events.summary.maxWaitedMs", maxWaited);
                TraceStore.put("web.await.events.summary.maxTimeoutMs", maxTimeout);

                for (Map.Entry<String, Integer> e : engineCounts.entrySet()) {
                    if (e == null || e.getKey() == null)
                        continue;
                    TraceStore.put("web.await.events.summary.engine." + sanitizeKeyPart(e.getKey()) + ".count",
                            e.getValue());
                }

                // Top causes (cap to avoid trace explosion)
                List<Map.Entry<String, Integer>> causeEntries = new ArrayList<>(causeCounts.entrySet());
                causeEntries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                int causeCap = Math.min(12, causeEntries.size());
                for (int idx = 0; idx < causeCap; idx++) {
                    Map.Entry<String, Integer> e = causeEntries.get(idx);
                    if (e == null || e.getKey() == null)
                        continue;
                    TraceStore.put("web.await.events.summary.cause." + sanitizeKeyPart(e.getKey()) + ".count",
                            e.getValue());
                }

                // Top steps (cap)
                List<Map.Entry<String, Integer>> stepEntries = new ArrayList<>(stepCounts.entrySet());
                stepEntries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                int stepCap = Math.min(8, stepEntries.size());
                for (int idx = 0; idx < stepCap; idx++) {
                    Map.Entry<String, Integer> e = stepEntries.get(idx);
                    if (e == null || e.getKey() == null)
                        continue;
                    TraceStore.put("web.await.events.summary.step." + sanitizeKeyPart(e.getKey()) + ".count",
                            e.getValue());
                }

                // Engine|Cause counts (cap)
                List<Map.Entry<String, Integer>> ecEntries = new ArrayList<>(engineCauseCounts.entrySet());
                ecEntries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                int ecCap = Math.min(12, ecEntries.size());
                for (int idx = 0; idx < ecCap; idx++) {
                    Map.Entry<String, Integer> e = ecEntries.get(idx);
                    if (e == null || e.getKey() == null)
                        continue;
                    String[] parts = e.getKey().split("\\|", 2);
                    String eng = parts.length > 0 ? parts[0] : "?";
                    String cause = parts.length > 1 ? parts[1] : "";
                    TraceStore.put("web.await.events.summary.engine." + sanitizeKeyPart(eng) + ".cause."
                            + sanitizeKeyPart(cause) + ".count",
                            e.getValue());
                }

                TraceStore.put("web.await.events.summary.digests", capList(digests, 30));
            } catch (Exception ignore) {
                // fail-soft
            }
        } else {
            // Mark that there was no event list, but we might still have skip counters.
            try {
                TraceStore.put("web.await.events.summary.count", 0);
            } catch (Exception ignore) {
                // ignore
            }
        }

        // B) skipped counts -> summary
        if (hasSkipped) {
            try {
                long total = 0L;
                for (Map.Entry<String, Object> e : skippedCtx.entrySet()) {
                    if (e == null || e.getKey() == null)
                        continue;
                    String k = e.getKey();
                    if (k.startsWith("web.await.skipped.") && k.endsWith(".count")) {
                        String engine = k.substring("web.await.skipped.".length(),
                                k.length() - ".count".length());
                        long v = toLong(e.getValue());
                        total += v;
                        TraceStore.put("web.await.events.summary.skipped.engine." + sanitizeKeyPart(engine) + ".count",
                                v);
                    }
                    if (k.startsWith("web.await.skipped.") && k.endsWith(".last")) {
                        String engine = k.substring("web.await.skipped.".length(),
                                k.length() - ".last".length());
                        TraceStore.put("web.await.events.summary.skipped.engine." + sanitizeKeyPart(engine) + ".last",
                                safeTrim(String.valueOf(e.getValue()), 120));
                    }
                }

                Object last = skippedCtx.get("web.await.skipped.last");
                if (last != null) {
                    TraceStore.put("web.await.events.summary.skipped.last", safeTrim(String.valueOf(last), 160));
                }
                Object lastEngine = skippedCtx.get("web.await.skipped.last.engine");
                if (lastEngine != null) {
                    TraceStore.put("web.await.events.summary.skipped.last.engine", String.valueOf(lastEngine));
                }
                Object lastReason = skippedCtx.get("web.await.skipped.last.reason");
                if (lastReason != null) {
                    TraceStore.put("web.await.events.summary.skipped.last.reason",
                            safeTrim(String.valueOf(lastReason), 160));
                }
                Object lastStep = skippedCtx.get("web.await.skipped.last.step");
                if (lastStep != null) {
                    TraceStore.put("web.await.events.summary.skipped.last.step",
                            safeTrim(String.valueOf(lastStep), 120));
                }

                TraceStore.put("web.await.events.summary.skipped.total", total);
            } catch (Exception ignore) {
                // fail-soft
            }
        }
    }

    /**
     * WebSnippet의 유니크 키를 추출합니다. (URL 우선, 없으면 원문 사용)
     */
    private static String keyFor(WebSnippet sn) {
        if (sn == null)
            return "";
        String u = sn.url();
        return (u != null && !u.isBlank()) ? u : (sn.raw() != null ? sn.raw() : "");
    }

    /**
     * Find the first snippet tagged as OFFICIAL or DOCS.
     *
     * <p>
     * Expected format: [WEB:OFFICIAL|CRED:...] ...
     * </p>
     */
    @Nullable
    private static String firstOfficialOrDocsSnippet(@Nullable List<String> staged) {
        if (staged == null || staged.isEmpty())
            return null;
        for (String s : staged) {
            if (s == null)
                continue;
            String t = s.trim();
            if (t.startsWith("[WEB:OFFICIAL") || t.startsWith("[WEB:DOCS")) {
                return s;
            }
        }
        return null;
    }

    /**
     * Heuristic: prioritize augmented queries that are likely to yield
     * OFFICIAL/DOCS citations.
     */
    private static int scoreOfficialDocsRescueQuery(@Nullable String q) {
        if (q == null)
            return 0;
        String raw = q.trim();
        if (raw.isEmpty())
            return 0;

        int score = 0;
        // Korean signals
        if (raw.contains("공식") || raw.contains("문서") || raw.contains("매뉴얼") || raw.contains("홈페이지")
                || raw.contains("사이트") || raw.contains("가이드") || raw.contains("레퍼런스") || raw.contains("개발자")
                || raw.contains("API")) {
            score += 3;
        }

        String t = raw.toLowerCase(Locale.ROOT);
        if (t.contains("official") || t.contains("docs") || t.contains("documentation") || t.contains("developer")
                || t.contains("developers") || t.contains("api reference") || t.contains("reference")) {
            score += 3;
        }
        if (t.contains("site:") || t.contains("docs.") || t.contains("developer.") || t.contains("developers.")) {
            score += 1;
        }
        if (t.contains("github.com") || t.contains("readthedocs") || t.contains("confluence")
                || t.contains("notion.site")) {
            score += 1;
        }
        return score;
    }

    // Soak KPI: fixed-schema JSON line for log-based RCA / dashboards.
    // Fields are intentionally stable:
    // rid, sessionId, providerStatus(skipped|ok|cache_only), outCount,
    // stageCountsSelectedFromOut,
    // cacheOnly.merged.count, tracePool.size, rescueMerge.used,
    // starvationFallback.trigger, poolSafeEmpty
    private void emitSoakKpiJson(long runId, Map<String, Integer> stageCountsSelectedFromOut, List<String> out) {
        if (props == null || !props.isEmitSoakKpiJson()) {
            return;
        }

        try {
            // Avoid emitting duplicates when extraSearchCalls run.
            Object prev = TraceStore.putIfAbsent("web.failsoft.soakKpiJson.emitted.runId." + runId, Boolean.TRUE);
            if (prev != null) {
                return;
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        try {
            String rid = org.slf4j.MDC.get("trace");
            if (rid == null)
                rid = "";
            String sessionId = org.slf4j.MDC.get("sid");
            if (sessionId == null)
                sessionId = "";

            boolean cacheOnlyUsed = isTruthy(firstNonNull(
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.used"),
                    TraceStore.get("cacheOnly.used")));

            boolean naverSkipped = isTruthy(TraceStore.get("web.naver.skipped"));
            boolean braveSkipped = isTruthy(TraceStore.get("web.brave.skipped"));

            String providerStatus;
            if (cacheOnlyUsed) {
                providerStatus = "cache_only";
            } else if (naverSkipped && braveSkipped) {
                providerStatus = "skipped";
            } else {
                providerStatus = "ok";
            }

            long cacheMerged = toLong(firstNonNull(
                    TraceStore.get("cacheOnly.merged.count"),
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count")));

            long tracePoolSize = toLong(firstNonNull(
                    TraceStore.get("tracePool.size"),
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size")));

            boolean rescueMergeUsed = isTruthy(firstNonNull(
                    TraceStore.get("rescueMerge.used"),
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used")));

            String starvationTrigger = stringOrNull(firstNonNull(
                    TraceStore.get("starvationFallback.trigger"),
                    TraceStore.get("web.failsoft.starvationFallback")));

            boolean poolSafeEmpty = isTruthy(firstNonNull(
                    TraceStore.get("poolSafeEmpty"),
                    TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty")));

            Map<String, Object> j = new LinkedHashMap<>();
            j.put("rid", rid);
            j.put("sessionId", sessionId);
            j.put("providerStatus", providerStatus);
            j.put("outCount", out == null ? 0 : out.size());
            j.put("stageCountsSelectedFromOut",
                    stageCountsSelectedFromOut == null ? Collections.emptyMap() : stageCountsSelectedFromOut);
            j.put("cacheOnly.merged.count", cacheMerged);
            j.put("tracePool.size", tracePoolSize);
            j.put("rescueMerge.used", rescueMergeUsed);
            j.put("starvationFallback.trigger", starvationTrigger == null ? "" : starvationTrigger);
            j.put("poolSafeEmpty", poolSafeEmpty);

            boolean citeableTopUpUsed = isTruthy(TraceStore.get("web.failsoft.citeableTopUp.used"));
            long citeableTopUpAdded = toLong(TraceStore.get("web.failsoft.citeableTopUp.added"));
            j.put("citeableTopUp.used", citeableTopUpUsed);
            j.put("citeableTopUp.added", citeableTopUpAdded);

            soakKpiLog.info(soakKpiOm.writeValueAsString(j));
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    /**
     * 문자열을 안전하게 자르고 말줄임표를 추가합니다.
     */
    private static String safeTrim(String s, int max) {
        if (s == null)
            return "";
        String t = s.trim();
        if (t.length() <= max)
            return t;
        return (max <= 3) ? t.substring(0, max) : t.substring(0, max - 3) + "...";
    }

    /**
     * 리스트의 크기를 최대 max 개로 제한합니다.
     */
    private static List<String> capList(List<String> list, int max) {
        if (list == null || list.size() <= max)
            return list;
        return new ArrayList<>(list.subList(0, max));
    }

    private static Object firstNonNull(Object... values) {
        if (values == null)
            return null;
        for (Object v : values) {
            if (v != null)
                return v;
        }
        return null;
    }

    private static boolean isTruthy(Object o) {
        if (o == null)
            return false;
        if (o instanceof Boolean)
            return (Boolean) o;
        if (o instanceof Number)
            return ((Number) o).intValue() != 0;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static long toLong(Object o) {
        if (o == null)
            return 0L;
        if (o instanceof Number)
            return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignore) {
            return 0L;
        }
    }

    /**
     * Sanitize a string so it can be safely used as a TraceStore key segment.
     */
    private static String sanitizeKeyPart(String s) {
        if (s == null)
            return "null";
        String t = s.trim();
        if (t.isEmpty())
            return "empty";

        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            b.append(ok ? c : '_');
            if (b.length() >= 48)
                break;
        }
        String out = b.toString();
        while (out.contains("__")) {
            out = out.replace("__", "_");
        }
        return out;
    }

    private static String stringOrNull(Object o) {
        if (o == null)
            return null;
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }
}
