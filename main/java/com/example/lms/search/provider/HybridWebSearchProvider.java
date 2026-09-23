package com.example.lms.search.provider;

import com.example.lms.search.policy.GrokPromotionDiscovery;

import ai.abandonware.nova.orch.trace.OrchDigest;
import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpHeaders;
import java.net.URI;

import static com.example.lms.search.provider.HybridTraceSuppressions.traceSuppressed;
import static com.example.lms.search.provider.HybridSearchQueryPolicy.containsHangul;
import static com.example.lms.search.provider.HybridSearchQueryPolicy.extractKeywords;
import static com.example.lms.search.provider.HybridSearchQueryPolicy.convertToEnglishSearchTerm;

/**
 * [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
 */
@Service
@Primary // Default WebSearchProvider implementation.
@RequiredArgsConstructor
public class HybridWebSearchProvider implements WebSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchProvider.class);

    private final NaverSearchService naverService;
    private final BraveSearchService braveService;

    @Autowired(required = false)
    private GeminiGateway geminiGateway;

    @Value("${gpt-search.hybrid.bounded-fallback.enabled:true}")
    private boolean boundedFallbackEnabled;

    
    @Autowired(required = false)
    private RateLimitBackoffCoordinator rateLimitBackoffCoordinator;

    @Value("${gpt-search.hybrid.primary:BRAVE}")
    private String primary;

    @Value("${gpt-search.soak.enabled:true}")
    private boolean soakEnabled;

    /**
     * Parallel join timeout for Brave/Naver (seconds).
     * Keep a conservative default to avoid hanging threads.
     */
    @Value("${gpt-search.hybrid.timeout-sec:3}")
    private int timeoutSec = 3;

    /**
     * When the shared deadline is already exhausted (remainingMs<=0),
     * avoid an immediate cancel(0ms) which tends to amplify starvation
     * (merged=0) by missing "almost done" results.
     *
     * <p>
     * We allow a tiny floor wait (min-live-budget) to harvest near-complete futures
     * before falling back to cache-only ladders.
     */
    @Value("${gpt-search.hybrid.await.min-live-budget-ms:600}")
    private long awaitMinLiveBudgetMs;

    /**
     * OfficialOnly floor for the await(min-live-budget) path.
     *
     * <p>
     * Risk: may increase worst-case wait time for officialOnly requests.
     * Benefit: reduces "tiny_budget/budget_exhausted" cascades that end in merged=0.
     * </p>
     */
    @Value("${gpt-search.hybrid.await.min-live-budget-ms.official-only:900}")
    private long awaitMinLiveBudgetMsOfficialOnly;

    /**
     * Small safety margin to prevent "inner block timeout" and "outer await timeout" racing each other.
     *
     * <p>Example: when a sync facade blocks for exactly 3000ms and the outer Future.get also waits 3000ms,
     * the outer await often times out first due to scheduler overhead ??await_timeout=100%.
     * We shorten the inner block by this margin so the Future completes before the outer await expires.</p>
     */
    @Value("${gpt-search.hybrid.await.deadline-margin-ms:120}")
    private long awaitDeadlineMarginMs;

    /**
     * In officialOnly mode, allow applying the min-live-budget floor even when
     * remainingMs<=0 (budget_exhausted) to harvest near-complete futures.
     */
    @Value("${gpt-search.hybrid.await.floor-budget-exhausted.official-only:true}")
    private boolean awaitFloorBudgetExhaustedOfficialOnly;


    // Treat very small remaining budgets as near-exhausted (precision guard).
    @Value("${gpt-search.hybrid.await.near-exhausted-threshold-ms:10}")
    private long awaitNearExhaustedThresholdMs;

    // When remaining budget is tiny (1..minLiveBudget-1), optionally apply a floor
    // so late results can still be harvested by cache-only/remerge ladders.
    @Value("${gpt-search.hybrid.await.floor-tiny-budget:true}")
    private boolean awaitFloorTinyBudget;

    // When a floor is applied, suppress cancel(false) so queued tasks may still run
    // and populate caches (late-harvest).
    @Value("${gpt-search.hybrid.await.cancel-suppressed-when-floor:true}")
    private boolean awaitCancelSuppressedWhenFloor;

    // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
    @Value("${gpt-search.hybrid.skip-naver-if-brave-sufficient:true}")
    private boolean skipNaverIfBraveSufficient;

    @Value("${gpt-search.hybrid.naver-opportunistic-ms:250}")
    private long naverOpportunisticMs;

    /**
     * Cap how long we allow Naver to consume the remaining time budget in a single Hybrid call.
     *
     * <p>
     * Motivation: prevent Naver hard-timeout(??~7s) from
     * (1) being misclassified as a provider TIMEOUT ("await_timeout") and
     * (2) exhausting the shared deadline so Brave falls into tiny_budget/budget_exhausted.
     *
     * <p>
     * This is a <b>blocking/await budget cap</b> (not a retry/backoff). Tune per environment.
     */
    @Value("${gpt-search.hybrid.naver.block-timeout-cap-ms:3600}")
    private long naverBlockTimeoutCapMs;

    /**
     * In officialOnly mode, Brave should join up to the deadline (no soft-wait).
     * When Naver already filled topK, this optional cap limits extra wait time
     * for diversity join. 0 means wait until the overall deadline.
     */
    @Value("${gpt-search.hybrid.official-only.brave-full-join.max-wait-ms:4200}")
    private long officialOnlyBraveFullJoinMaxWaitMs;

    // Fail-soft: when merged=0 due to rate-limit/timeout/cancellation,
    // do a single cache-only "remerge" after a short delay before triggering
    // fallback ladder.
    @Value("${gpt-search.hybrid.remerge-on-empty.enabled:true}")
    private boolean remergeOnEmptyEnabled;

    @Value("${gpt-search.hybrid.remerge-on-empty.initial-delay-ms:80}")
    private long remergeOnEmptyInitialDelayMs;

    @Value("${gpt-search.hybrid.remerge-on-empty.max-total-wait-ms:350}")
    private long remergeOnEmptyMaxTotalWaitMs;

    @Value("${gpt-search.hybrid.remerge-on-empty.max-polls:3}")
    private int remergeOnEmptyMaxPolls;

    @Value("${gpt-search.hybrid.remerge-on-empty.brave-cache-only:true}")
    private boolean braveCacheOnlyEscape;

    @Value("${gpt-search.hybrid.remerge-on-empty.naver-cache-only:true}")
    private boolean naverCacheOnlyEscape;

    // Debug (very verbose): emit per-poll orch breadcrumbs for remergeOnce.
    // Keep disabled by default; TraceStore already captures poll events.
    @Value("${gpt-search.hybrid.remerge-on-empty.debug.emit-poll-events:false}")
    private boolean remergeDebugEmitPollEvents;

    @Value("${privacy.boundary.block-web-search:false}")
    private boolean blockWebSearch;

    @Value("${privacy.boundary.block-web-search-on-sensitive:false}")
    private boolean blockWebSearchOnSensitive;

    // Adaptive Naver soft-timeout (streak-based auto tuning)
    private final AtomicInteger naverSoftTimeoutStreak = new AtomicInteger(0);
    private final AtomicLong naverEwmaMs = new AtomicLong(350L);

    // --- Korean hedged search knobs (safe defaults) ---
    // Brave-first: wait briefly for Brave, then start Naver only if needed.
    @Value("${gpt-search.hybrid.korean.hedge-delay-ms:450}")
    private long koreanHedgeDelayMs;

    // If Brave returns at least this many results within hedge delay, skip starting
    // Naver.
    @Value("${gpt-search.hybrid.korean.skip-naver-if-brave-min-results:6}")
    private int skipNaverIfBraveMinResults;

    // Extra KR source diversity is opt-in. The default chatbot path should not keep
    // a live Naver call running after Brave already returned enough snippets.
    @Value("${gpt-search.hybrid.korean.force-opportunistic-naver-even-if-brave-fast:false}")
    private boolean forceOpportunisticNaverEvenIfBraveFast;

    // Symmetric: If Naver returns enough results within hedge delay, skip starting
    // Brave.
    @Value("${gpt-search.hybrid.korean.skip-brave-if-naver-min-results:6}")
    private int skipBraveIfNaverMinResults;

    @Autowired
    @Qualifier("searchIoExecutor")
    private ExecutorService searchIoExecutor;
    private final ThreadLocal<HybridSearchExecution> activeExecution = new ThreadLocal<>();

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Autowired(required = false)
    private SoakMetricRegistry soakMetricRegistry;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    private boolean isBravePrimary() {
        GuardContext ctx = GuardContextHolder.get();

        String primaryOverride = (ctx != null) ? ctx.getWebPrimary() : null;
        boolean wantBrave = StringUtils.hasText(primaryOverride)
                ? "BRAVE".equalsIgnoreCase(primaryOverride)
                : "BRAVE".equalsIgnoreCase(primary);

        // Provider health (best-effort)
        boolean braveUsable = false;
        boolean naverUsable = false;

        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE))
                    && !braveService.isCoolingDown();
        } catch (Exception e) {
            String m = e.getMessage(), k = "webSearch.primary.brave.healthFailure";
            TraceStore.put(k, true); TraceStore.put(k + ".kind", NightmareBreaker.classify(e).name().toLowerCase(Locale.ROOT)); TraceStore.put(k + ".errorType", SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown")); TraceStore.put(k + ".messageHash", SafeRedactor.hashValue(m)); TraceStore.put(k + ".messageLength", m == null ? 0 : m.length());
        }

        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception e) {
            String m = e.getMessage(), k = "webSearch.primary.naver.healthFailure";
            TraceStore.put(k, true); TraceStore.put(k + ".kind", NightmareBreaker.classify(e).name().toLowerCase(Locale.ROOT)); TraceStore.put(k + ".errorType", SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown")); TraceStore.put(k + ".messageHash", SafeRedactor.hashValue(m)); TraceStore.put(k + ".messageLength", m == null ? 0 : m.length());
        }

        // Auto-switch away from a down provider when the other is available
        // (fail-soft).
        if (wantBrave && !braveUsable && naverUsable) {
            try {
                TraceStore.put("webSearch.primary.autoSwitch", "BRAVE->NAVER");
            } catch (Exception suppressed) { traceSuppressed("primary.autoSwitch.braveToNaver", suppressed); }
            return false;
        }

        if (!wantBrave && !naverUsable && braveUsable) {
            try {
                TraceStore.put("webSearch.primary.autoSwitch", "NAVER->BRAVE");
            } catch (Exception suppressed) { traceSuppressed("primary.autoSwitch.naverToBrave", suppressed); }
            return true;
        }

        return wantBrave;
    }

    @Override
    public boolean supportsSiteOrSyntax() {
        // We only claim OR support when Brave is effectively the primary engine for
        // this request.
        // When Naver is primary, OR semantics are provider-specific and may behave like
        // a literal token,
        // so we stay conservative.
        return isBravePrimary();
    }

    @Override
    public List<String> search(String query, int topK) {
        return withRequestBudget(() -> searchWithinBudget(query, topK));
    }

    private List<String> searchWithinBudget(String query, int topK) {

        boolean singleCycle = Boolean.TRUE.equals(TraceStore.get("conversate.web.singleCycle"));
        if (boundedFallbackEnabled || singleCycle) {
            try {
                TraceStore.put("web.boundedRoute.completed", false);
                TraceStore.put("web.boundedRoute", true);
                TraceStore.put("web.boundedRoute.mode", singleCycle ? "naver-selective-brave-once" : "brave-naver-gemini-once");
            } catch (Exception suppressed) {
                traceSuppressed("boundedRoute.startTrace", suppressed);
            }
        }

        var gctx = GuardContextHolder.get();
        boolean sensitive = gctx != null && gctx.isSensitiveTopic();
        boolean planBlockAll = gctx != null && gctx.planBool("privacy.boundary.block-web-search", false);
        boolean planBlockOnSensitive = gctx != null
                && gctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
        if (blockWebSearch || planBlockAll || (sensitive && (blockWebSearchOnSensitive || planBlockOnSensitive))) {
            try {
                com.example.lms.search.TraceStore.put("privacy.web.blocked", true);
            } catch (Exception suppressed) { traceSuppressed("privacy.webBlocked.direct", suppressed); }
            markBoundedTerminal("privacy-blocked", 0, 0, 0);
            return java.util.Collections.emptyList();
        }

        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) {
            logSkipOnce("SKIP_EMPTY_QUERY", "HybridWebSearchProvider skipped (blank query)");
            markBoundedTerminal("blank-query", 0, 0, 0);
            return java.util.Collections.emptyList();
        }
        query = safeQuery;

        if (singleCycle && Boolean.TRUE.equals(TraceStore.get("conversate.web.ragSupplement"))) {
            return searchRagSupplement(query, topK);
        }
        if (boundedFallbackEnabled || singleCycle) {
            return searchBoundedFallback(query, topK).snippets();
        }

        if (!canStartSearchAttempt()) {
            return Collections.emptyList();
        }

        boolean isKorean = containsHangul(query);
        TraceStore.putIfAbsent("query.lang", isKorean ? "ko" : "en");

        if (!isKorean) {
            // 湲곗〈 ?숈옉 ?좎? (鍮꾪븳援?뼱 荑쇰━)
            java.util.List<String> out = isBravePrimary() ? searchBraveFirst(query, topK)
                    : searchNaverFirst(query, topK);
            if (Thread.currentThread().isInterrupted()) {
                return out == null ? Collections.emptyList() : out;
            }
            return maybeBackupOnce(query, topK, out);
        }

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        java.util.List<String> out = searchKoreanSmartMerge(query, topK);
        if (Thread.currentThread().isInterrupted()) {
            return out == null ? Collections.emptyList() : out;
        }
        return maybeBackupOnce(query, topK, out);
    }

    private BoundedSearchResult searchBoundedFallback(String originalQuery, int topK) {
        if (!canStartSearchAttempt()) {
            return finishBounded(Collections.emptyList(), 0, 0, searchStopReason());
        }
        boolean singleCycle = Boolean.TRUE.equals(TraceStore.get("conversate.web.singleCycle"));
        if (!singleCycle && GrokPromotionDiscovery.matches(originalQuery)) {
            return searchBoundedPromotion(originalQuery, topK);
        }
        SearchCycle initialCycle = new SearchCycle();
        List<String> initial = singleCycle ? searchNaverFirst(originalQuery, topK, initialCycle)
                : searchBraveFirst(originalQuery, topK, initialCycle);
        if (initial != null && !initial.isEmpty()) {
            return finishBounded(initial, 1, 0, "initial-hit");
        }
        if (!canStartSearchAttempt()) {
            return finishBounded(Collections.emptyList(), 1, 0, searchStopReason());
        }
        if (singleCycle || !"TRUE_ZERO".equals(initialCycle.reason())) {
            return finishBounded(Collections.emptyList(), 1, 0, initialCycle.reason());
        }

        String locallyRewritten = originalQuery;
        locallyRewritten = locallyRewritten.replaceAll("\\s+", " ").trim();
        try {
            TraceStore.put("web.boundedRoute.localRewrite.changed",
                    !locallyRewritten.equalsIgnoreCase(originalQuery));
            TraceStore.put("web.boundedRoute.localRewrite.queryHash", SafeRedactor.hashValue(locallyRewritten));
            TraceStore.put("web.boundedRoute.localRewrite.queryLength", locallyRewritten.length());
        } catch (Exception suppressed) {
            traceSuppressed("boundedRoute.localRewriteTrace", suppressed);
        }

        if (geminiGateway == null) {
            return finishBounded(Collections.emptyList(), 1, 0, "gemini-gateway-unavailable");
        }

        GeminiGateway.SearchExpansion expansion;
        try {
            long expansionWaitMs = capRequestWait(TimeUnit.SECONDS.toMillis(timeoutSec));
            if (expansionWaitMs == 0L || !canStartSearchAttempt()) {
                return finishBounded(Collections.emptyList(), 1, 0, searchStopReason());
            }
            expansion = geminiGateway.expandSearchQueryOnce(locallyRewritten)
                    .block(java.time.Duration.ofMillis(expansionWaitMs));
        } catch (RuntimeException failure) {
            traceSuppressed("boundedRoute.geminiExpansion", failure);
            return finishBounded(Collections.emptyList(), 1, 1, "gemini-expansion-failed");
        }
        int geminiAttempts = expansion == null || expansion.status() == null
                ? 0
                : Math.max(0, expansion.status().attemptCount());
        if (expansion == null || !StringUtils.hasText(expansion.query())) {
            String reason = expansion == null || expansion.status() == null
                    || !StringUtils.hasText(expansion.status().fallbackReason())
                            ? "gemini-expansion-empty"
                            : expansion.status().fallbackReason();
            return finishBounded(Collections.emptyList(), 1, geminiAttempts, reason);
        }

        if (!canStartSearchAttempt()) {
            return finishBounded(Collections.emptyList(), 1, geminiAttempts, searchStopReason());
        }
        if (!preservesResearchQuery(originalQuery, expansion.query())) {
            return finishBounded(Collections.emptyList(), 1, geminiAttempts, "query-constraints-changed");
        }
        SearchCycle researchCycle = new SearchCycle();
        List<String> researched = searchBraveFirst(expansion.query(), topK, researchCycle);
        return finishBounded(
                researched == null ? Collections.emptyList() : researched,
                2,
                geminiAttempts,
                researched != null && !researched.isEmpty() ? "research-hit"
                        : "TRUE_ZERO".equals(researchCycle.reason()) ? "research-empty" : researchCycle.reason());
    }

    private static boolean preservesResearchQuery(String original, String candidate) {
        if (!com.example.lms.search.SearchQueryConstraints.preserves(original, candidate)) return false;
        // Keep the original subject terms as well as dates, source operators and exclusions.
        java.util.Set<String> proposed = new java.util.HashSet<>(java.util.Arrays.asList(candidate.toLowerCase(Locale.ROOT).strip().split("\\s+")));
        return java.util.Arrays.stream(original.toLowerCase(Locale.ROOT).strip().split("\\s+")).allMatch(proposed::contains);
    }

    private static final class SearchCycle {
        private boolean trueZero;
        private String failure;
        void observe(String reason) {
            if ("TRUE_ZERO".equals(reason)) trueZero = true;
            else if (!"NONE".equals(reason) && failure == null) failure = reason == null ? "UNKNOWN" : reason;
        }
        String reason() { return failure != null ? failure : trueZero ? "TRUE_ZERO" : "UNKNOWN"; }
    }

    private static String braveOutcome(BraveSearchResult result) {
        if (result == null || result.status() == null) return "UNKNOWN";
        Integer status = result.httpStatus();
        if (status != null && (status == 401 || status == 403)) return "AUTH_OR_CONFIG";
        if (status != null && (status == 408 || status == 504)) return "TIMEOUT_OR_BUDGET";
        return switch (result.status()) {
            case OK -> result.snippets() == null ? "UNKNOWN" : result.snippets().isEmpty() ? "TRUE_ZERO" : "NONE";
            case HTTP_429, RATE_LIMIT_LOCAL -> "RATE_LIMIT";
            case DISABLED -> "AUTH_OR_CONFIG";
            case COOLDOWN -> "BREAKER_OR_COOLDOWN";
            case EXCEPTION -> "json-parse-error".equals(result.message()) ? "PARSE_ERROR"
                    : "timeout".equals(result.message()) ? "TIMEOUT_OR_BUDGET"
                    : "cancelled".equals(result.message()) ? "CLIENT_CANCELLED" : "PROVIDER_ERROR";
            default -> "PROVIDER_ERROR";
        };
    }

    private BoundedSearchResult searchBoundedPromotion(String originalQuery, int topK) {
        // Reuse the two-cycle ceiling; the bounded marker still disables nested provider expansion.
        TraceStore.put("web.promotionDiscovery.mode", "bounded-discovery-and-terms");
        List<String> offers = searchBraveFirst(originalQuery + GrokPromotionDiscovery.DISCOVERY_SUFFIX, topK);
        if (offers == null) offers = List.of();
        TraceStore.put("web.promotionDiscovery.reason",
                offers.isEmpty() ? "base_empty" : "base_results_not_exhaustive");
        TraceStore.put("web.promotionDiscovery.discoveryCount", offers.size());
        TraceStore.put("web.promotionDiscovery.eligibilityStatus", "evidence_needed");
        if (!canStartSearchAttempt()) {
            return finishBounded(offers, 1, 0, searchStopReason());
        }
        List<String> terms = searchBraveFirst(originalQuery + GrokPromotionDiscovery.VERIFICATION_SUFFIX, topK);
        if (terms == null) terms = List.of();
        TraceStore.put("web.promotionDiscovery.termsCount", terms.size());
        List<String> evidence = GrokPromotionDiscovery.mergeEvidence(List.of(offers, terms), topK);
        return finishBounded(evidence, 2, 0,
                terms.isEmpty() ? "promotion-terms-evidence-needed" : "promotion-evidence-collected");
    }

    private BoundedSearchResult finishBounded(
            List<String> snippets,
            int providerCycles,
            int geminiAttempts,
            String terminalReason) {
        List<String> safeSnippets = snippets == null ? Collections.emptyList() : List.copyOf(snippets);
        markBoundedTerminal(terminalReason, providerCycles, geminiAttempts, safeSnippets.size());
        return new BoundedSearchResult(safeSnippets, providerCycles, geminiAttempts, terminalReason);
    }

    private void markBoundedTerminal(
            String terminalReason,
            int providerCycles,
            int geminiAttempts,
            int outCount) {
        if (!boundedFallbackEnabled && !Boolean.TRUE.equals(TraceStore.get("conversate.web.singleCycle"))) {
            return;
        }
        try {
            TraceStore.put("web.boundedRoute.providerCycles", Math.max(0, providerCycles));
            TraceStore.put("web.boundedRoute.geminiAttempts", Math.max(0, geminiAttempts));
            TraceStore.put("web.boundedRoute.outCount", Math.max(0, outCount));
            TraceStore.put("web.boundedRoute.terminalReason",
                    SafeRedactor.traceLabelOrFallback(terminalReason, "unknown"));
            TraceStore.put("web.boundedRoute.completed", true);
        } catch (Exception suppressed) {
            traceSuppressed("boundedRoute.terminalTrace", suppressed);
        }
    }

    record BoundedSearchResult(
            List<String> snippets,
            int providerCycles,
            int geminiAttempts,
            String terminalReason) {
    }

    private List<String> searchKoreanSmartMerge(String query, int topK) {
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        GuardContext gctx = GuardContextHolder.get();
        boolean officialOnly = gctx != null && gctx.isOfficialOnly();
        boolean bravePrimary = isBravePrimary();
        boolean explicitPreferNaver = gctx != null && (gctx.planBool("search.web.preferNaver", false)
                || gctx.planBool("web.preferNaver", false));
        // officialOnly constrains evidence quality; it should not force an extra live
        // Naver-first call when the active provider preference is BRAVE.
        boolean preferNaver = explicitPreferNaver || (officialOnly && !bravePrimary);

        List<String> primary;
        if (preferNaver) {
            try {
                TraceStore.put("webSearch.providerPreference", "naver-first");
            } catch (Exception suppressed) { traceSuppressed("providerPreference.naverFirst", suppressed); }
            primary = searchKoreanNaverAndBrave(query, topK);
        } else if (bravePrimary) {
            try {
                TraceStore.put("webSearch.providerPreference", "brave-first");
                if (officialOnly && !explicitPreferNaver) {
                    TraceStore.put("webSearch.providerPreference.reason", "official_only_brave_primary");
                }
            } catch (Exception suppressed) { traceSuppressed("providerPreference.braveFirst", suppressed); }
            primary = searchKoreanBraveAndNaver(query, topK);
        } else {
            try {
                TraceStore.put("webSearch.providerPreference", "naver-first(config)");
            } catch (Exception suppressed) { traceSuppressed("providerPreference.naverFirstConfig", suppressed); }
            primary = searchKoreanNaverAndBrave(query, topK);
        }

        if (Thread.currentThread().isInterrupted()) {
            return primary != null ? primary : Collections.emptyList();
        }

        if (primary != null && primary.size() >= 3) {
            return primary;
        }

        if (!soakEnabled) {
            return primary != null ? primary : Collections.emptyList();
        }

        String extracted = extractKeywords(query);
        if (!StringUtils.hasText(extracted) || extracted.equals(query)) {
            return primary != null ? primary : Collections.emptyList();
        }

        log.info("[Hybrid] Soak keyword retry queryHash={} queryLength={} extractedHash={} extractedLength={}",
                SafeRedactor.hash12(query),
                query == null ? 0 : query.length(),
                SafeRedactor.hash12(extracted),
                extracted == null ? 0 : extracted.length());

        List<String> keywordResults;
        if (preferNaver) {
            keywordResults = searchKoreanNaverAndBrave(extracted, topK);
        } else if (bravePrimary) {
            keywordResults = searchKoreanBraveAndNaver(extracted, topK);
        } else {
            keywordResults = searchKoreanNaverAndBrave(extracted, topK);
        }

        return HybridSearchResultPolicy.mergeKeywordRetry(primary, keywordResults, topK);
    }

    private List<String> searchBraveFirst(String query, int topK) {
        return searchBraveFirst(query, topK, null);
    }

    private List<String> searchBraveFirst(String query, int topK, SearchCycle cycle) {
        if (!canStartSearchAttempt()) {
            return Collections.emptyList();
        }

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE))
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            traceSuppressed("braveUsable.direct", ignore);
        }

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
                if (!canStartSearchAttempt()) return Collections.emptyList();
                List<String> brave;
                if (cycle == null) brave = braveService.search(query, topK);
                else {
                    brave = braveService.searchCacheOnly(query, topK);
                    if (brave == null || brave.isEmpty()) {
                        BraveSearchResult result = braveService.searchWithMeta(query, topK);
                        cycle.observe(braveOutcome(result));
                        brave = result == null ? Collections.emptyList() : result.snippets();
                    }
                }
                if (brave != null && !brave.isEmpty()) {
                    log.info("[Hybrid] Brave primary returned {} snippets", brave.size());
                    return brave;
                }
                log.info("[Hybrid] Brave primary returned empty list. Falling back to Naver.");
            } catch (Exception e) {
                if (cycle != null) cycle.observe("PROVIDER_ERROR");
                traceSuppressed("bravePrimary.search", e);
                log.warn("[Hybrid] Brave primary failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "direct", 0L);
            log.warn("[Hybrid] Brave primary skipped (breaker/cooldown). Falling back to Naver.");
        }

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception ignore) {
            traceSuppressed("naverUsable.directFallback", ignore);
        }

        // 2. Fallback: Naver ?쒕룄
        if (naverUsable) {
            try {
                if (!canStartSearchAttempt()) return Collections.emptyList();
                List<String> naver;
                if (cycle == null) naver = naverService.searchSnippetsSync(query, topK);
                else {
                    NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                    cycle.observe(result == null || result.trace() == null ? "UNKNOWN" : result.trace().outcomeClass);
                    naver = result == null ? Collections.emptyList() : result.snippets();
                }
                if (naver != null && !naver.isEmpty()) {
                    log.info("[Hybrid] Naver fallback returned {} snippets", naver.size());
                    return naver;
                }
            } catch (Exception e) {
                if (cycle != null) cycle.observe("PROVIDER_ERROR");
                traceSuppressed("naverFallback.search", e);
                log.warn("[Hybrid] Naver fallback failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "direct");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception suppressed) { traceSuppressed("naverSkipped.directFallback", suppressed); }
            log.warn("[Hybrid] Naver fallback skipped (breaker OPEN/HALF_OPEN).");
        }

        // 3. All failed ??鍮?由ъ뒪??諛섑솚 (?쒖뒪?쒖? 二쎌? ?딆쓬)
        if (!braveUsable && !naverUsable) {
            recordWebHardDown("both_skipped", "direct.braveFirst");
        }
        log.info("[Hybrid] All search providers failed. Returning empty list.");
        return Collections.emptyList();
    }

    /** Conversate keeps one provider cycle: errors are terminal, low evidence may use Brave once. */
    private List<String> searchNaverFirst(String query, int topK, SearchCycle cycle) {
        TraceStore.put("conversate.web.queryHash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(query));
        List<String> naver = List.of();
        String failure = "NONE";
        int requests = 0;
        long began = System.nanoTime();
        try {
            if (!canStartSearchAttempt()) failure = "TIMEOUT_OR_BUDGET";
            else if (naverService == null || !naverService.isEnabled()) failure = "AUTH_OR_CONFIG";
            else if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER)) failure = "BREAKER_OR_COOLDOWN";
            else {
                requests = 1;
                var result = naverService.searchWithTraceSync(query, topK);
                failure = result == null || result.trace() == null ? "UNKNOWN" : result.trace().outcomeClass;
                if (result != null && result.snippets() != null) naver = result.snippets();
            }
        } catch (RuntimeException unavailable) { failure = "PROVIDER_ERROR"; }
        if (failure == null) failure = "UNKNOWN";
        cycle.observe(failure);
        recordConversateSearch("NAVER", requests, naver.size(), began, "none", failure);
        // An error is not permission to spend on another provider or hide the failure.
        if (!java.util.Set.of("NONE", "TRUE_ZERO", "FILTER_ZERO").contains(failure)) return naver;

        boolean broad = query.matches("(?isu).*(?:최신|현재|오늘|해외|국외|외국|글로벌|교차\\s*검증|사실\\s*확인|여러\\s*출처|다양한\\s*출처|추가\\s*근거|latest|current|international|cross.check).*");
        var terms = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 2).distinct().toList();
        List<String> relevantNaver = naver.stream().filter(java.util.Objects::nonNull).filter(snippet -> {
            String text = snippet.toLowerCase(Locale.ROOT);
            return terms.stream().filter(text::contains).count() >= Math.min(2, terms.size());
        }).toList();
        String supplement = broad ? "wider_or_fresh_evidence" : naver.isEmpty() ? "insufficient_results"
                : relevantNaver.size() < Math.min(2, Math.max(1, topK)) ? "low_relevance_or_coverage" : "none";
        if ("none".equals(supplement)) return naver;
        if (!canStartSearchAttempt()) return relevantNaver;

        List<String> brave = searchConversateBrave(query, topK, cycle, supplement);
        // Reserve a slot for independent evidence even when the Naver batch already fills topK.
        int primaryLimit = brave.isEmpty() ? topK : Math.max(0, topK - 1);
        var selected = new java.util.LinkedHashSet<String>(relevantNaver.stream().limit(primaryLimit).toList());
        selected.addAll(brave);
        selected.addAll(relevantNaver);
        return selected.stream().limit(topK).toList();
    }

    /** A later RAG insufficiency decision reuses this request's reservation, never another Naver cycle. */
    private List<String> searchRagSupplement(String query, int topK) {
        if (!canStartSearchAttempt()
                || !org.apache.commons.codec.digest.DigestUtils.sha256Hex(query).equals(TraceStore.get("conversate.web.queryHash"))
                || TraceStore.get("conversate.search.BRAVE") != null
                || !(TraceStore.get("conversate.search.NAVER") instanceof java.util.Map<?, ?> naver)
                || !"NONE".equals(naver.get("failureReason"))
                || !(naver.get("resultCount") instanceof Number count) || count.intValue() <= 0
                || TraceStore.context().putIfAbsent("conversate.web.ragSupplement.started", true) != null) {
            return List.of();
        }
        SearchCycle cycle = new SearchCycle();
        List<String> result = searchConversateBrave(query, topK, cycle, "rag_evidence_insufficient");
        return finishBounded(result, 1, 0, result.isEmpty() ? cycle.reason() : "initial-hit").snippets();
    }

    private List<String> searchConversateBrave(String query, int topK, SearchCycle cycle, String supplement) {
        long began = System.nanoTime();int requests = 0;String failure = "NONE";
        List<String> brave = List.of();
        try {
            if (!canStartSearchAttempt()) failure = "TIMEOUT_OR_BUDGET";
            else if (braveService == null || !braveService.isEnabled()) failure = "AUTH_OR_CONFIG";
            else if (braveService.isCoolingDown() || nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE)) failure = "BREAKER_OR_COOLDOWN";
            else {
                brave = braveService.searchCacheOnly(query, topK);
                if (brave == null || brave.isEmpty()) {
                    requests = 1;
                    var result = braveService.searchWithMeta(query, topK);
                    failure = braveOutcome(result);
                    brave = result == null || result.snippets() == null ? List.of() : result.snippets();
                }
            }
        } catch (RuntimeException unavailable) { failure = "PROVIDER_ERROR"; }
        cycle.observe(failure);
        recordConversateSearch("BRAVE", requests, brave.size(), began, supplement, failure);
        return brave;
    }

    private void recordConversateSearch(String provider, int requests, int results, long began, String fallback, String failure) {
        long latency = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        var receipt = java.util.Map.<String,Object>of("searchNeeded", true, "provider", provider,
                "requestCount", requests, "requestCountScope", "provider_search_method", "resultCount", results, "latencyMs", latency,
                "fallbackReason", fallback, "failureReason", failure == null ? "UNKNOWN" : failure);
        TraceStore.put("conversate.search." + provider, receipt);
        log.info("conversate.search {}", receipt);
    }

    private List<String> searchNaverFirst(String query, int topK) {
        if (!canStartSearchAttempt()) {
            return Collections.emptyList();
        }

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception ignore) {
            traceSuppressed("naverUsable.direct", ignore);
        }

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (naverUsable) {
            try {
                if (!canStartSearchAttempt()) return Collections.emptyList();
                List<String> naver = naverService.searchSnippetsSync(query, topK);
                if (naver != null && !naver.isEmpty()) {
                    log.info("[Hybrid] Naver primary returned {} snippets", naver.size());
                    return naver;
                }
                log.info("[Hybrid] Naver primary returned no results. Falling back to Brave.");
            } catch (Exception e) {
                traceSuppressed("naverPrimary.search", e);
                log.warn("[Hybrid] Naver primary failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "direct");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception suppressed) { traceSuppressed("naverSkipped.directPrimary", suppressed); }
            log.warn("[Hybrid] Naver primary skipped (breaker OPEN/HALF_OPEN). Falling back to Brave.");
        }

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE))
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            traceSuppressed("braveUsable.directFallback", ignore);
        }

        // 2. Fallback: Brave
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
                if (!canStartSearchAttempt()) return Collections.emptyList();
                List<String> brave = braveService.search(query, topK);
                if (brave != null && !brave.isEmpty()) {
                    log.debug("[Hybrid] Brave fallback returned {} snippets", brave.size());
                    return brave;
                }
            } catch (Exception e) {
                traceSuppressed("braveFallback.search", e);
                log.warn("[Hybrid] Brave fallback failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "direct", 0L);
            log.warn("[Hybrid] Brave fallback skipped (breaker/cooldown).");
        }

        // 3. All failed ??鍮?由ъ뒪??諛섑솚
        if (!braveUsable && !naverUsable) {
            recordWebHardDown("both_skipped", "direct.naverFirst");
        }
        log.info("[Hybrid] All search providers failed. Returning empty list.");
        return Collections.emptyList();
    }

    /**
     * Converts Korean tech queries to English for Brave Search.
     * Focuses on smartphone/tech leak queries where English sources dominate.
     */
    private static long remainingMs(long deadlineNs) {
        return TimeBudget.untilNanoDeadline(deadlineNs).remainingMillis();
    }

    private <T> T withRequestBudget(java.util.function.Supplier<T> action) {
        return TraceStore.withSearchContext("searchExecutionId", () -> withRequestBudgetObserved(action));
    }

    private <T> T withRequestBudgetObserved(java.util.function.Supplier<T> action) {
        TimeBudget previousBudget = TimeBudgetContext.get();
        TimeBudget traceBudget = com.example.lms.trace.TraceContext.current().timeBudget();
        TimeBudget selectedBudget = previousBudget;
        if (traceBudget != null && (selectedBudget == null
                || traceBudget.remainingMillis() < selectedBudget.remainingMillis())) {
            selectedBudget = traceBudget;
        }
        if (selectedBudget == null) {
            selectedBudget = new TimeBudget(TimeUnit.SECONDS.toMillis(timeoutSec));
        }
        TimeBudgetContext.set(selectedBudget);
        boolean executionOwner = activeExecution.get() == null;
        if (executionOwner) {
            activeExecution.set(new HybridSearchExecution(TimeBudgetContext.get()));
        }
        try {
            return action.get();
        } finally {
            try {
                if (executionOwner) activeExecution.get().close();
            } finally {
                if (executionOwner) activeExecution.remove();
                if (previousBudget == null) TimeBudgetContext.clear();
                else TimeBudgetContext.set(previousBudget);
            }
        }
    }

    private <T> Future<T> submitSearchAttempt(java.util.concurrent.Callable<T> work) {
        HybridSearchExecution execution = activeExecution.get();
        return execution == null ? searchIoExecutor.submit(work) : execution.submit(searchIoExecutor, work);
    }

    private long capRequestWait(long requestedMs) {
        TimeBudget budget = TimeBudgetContext.get();
        long capped = budget == null ? Math.max(0L, requestedMs) : budget.capWaitMillis(requestedMs);
        return Math.min(capped, com.example.lms.trace.TraceContext.current().remainingMillis());
    }

    private long requestDeadline(long providerLimitMs) {
        long now = System.nanoTime();
        return now + TimeUnit.MILLISECONDS.toNanos(capRequestWait(providerLimitMs));
    }

    private boolean canStartSearchAttempt() {
        if (Thread.currentThread().isInterrupted() || capRequestWait(Long.MAX_VALUE) == 0L) {
            TraceStore.put("web.hybrid.execution.stopReason", searchStopReason());
            return false;
        }
        return true;
    }

    private static String searchStopReason() {
        return Thread.currentThread().isInterrupted() ? "cancelled" : "budget_exhausted";
    }

    private static boolean isTraceTag(String tag) {
        if (tag == null)
            return false;
        return tag.contains("Trace") || tag.contains("trace");
    }

    private static boolean isNaverTag(String tag) {
        if (tag == null)
            return false;
        // Accept "Naver", "Naver-Trace" etc.
        return tag.regionMatches(true, 0, "Naver", 0, 5);
    }

    /**
     * Reserve a minimum live budget for Brave when running a Naver-first strategy.
     *
     * <p>We re-use the existing await(min-live-budget) floor values to ensure
     * Brave doesn't immediately fall into tiny_budget/budget_exhausted after Naver
     * consumes the shared deadline.
     */
    private long braveMinReserveMs(boolean officialOnly) {
        long reserve = Math.max(0L, awaitMinLiveBudgetMs);
        if (officialOnly) {
            reserve = Math.max(reserve, Math.max(0L, awaitMinLiveBudgetMsOfficialOnly));
        }
        // Small overhead buffer (merge/log) to make the reserve meaningful.
        return (reserve > 0L) ? (reserve + 100L) : 0L;
    }

    /**
     * Compute Naver's per-call block timeout with an optional cap and an optional
     * reserve budget for the follow-up branch (typically Brave).
     */
    private long resolveNaverBlockTimeoutMs(long deadlineNs, long reserveMs, String stage) {
        long remaining = remainingMs(deadlineNs);
        long margin = Math.max(0L, awaitDeadlineMarginMs);
        long raw = Math.max(0L, remaining - margin);
        long cap = Math.max(0L, naverBlockTimeoutCapMs);

        // Keep the inner block slightly shorter than the outer await cap.
        long capEffective = (cap > 0L) ? Math.max(0L, cap - margin) : 0L;

        long effective = raw;
        boolean capped = false;

        if (capEffective > 0L && effective > capEffective) {
            effective = capEffective;
            capped = true;
        }

        if (reserveMs > 0L) {
            long maxAllow = Math.max(0L, remaining - reserveMs);
            if (maxAllow > 0L && effective > maxAllow) {
                effective = maxAllow;
                capped = true;
            }
        }

        effective = Math.min(remaining, Math.max(250L, effective));
        if (cap > 0L) effective = Math.min(cap, effective);

        try {
            TraceStore.put("web.naver.blockTimeout.capMs", cap);
            TraceStore.put("web.naver.blockTimeout.deadlineMarginMs", margin);
            TraceStore.put("web.naver.blockTimeout.capEffectiveMs", capEffective);
            if (reserveMs > 0L) {
                TraceStore.put("web.naver.blockTimeout.reserveMs", reserveMs);
            }
            TraceStore.put("web.naver.blockTimeout.effectiveMs", effective);
            if (stage != null && !stage.isBlank()) {
                TraceStore.put("web.naver.blockTimeout.stage", stage);
            }
            if (capped) {
                TraceStore.inc("web.naver.blockTimeout.capped.count");
            }
        } catch (Throwable ignore) {
            traceSuppressed("naverBlockTimeout.trace", ignore);
        }

        return effective;
    }

    /**
     * Safe collection for an already-completed future.
     *
     * <p>
     * Why this exists: when we compute remaining budget from a shared deadline,
     * the remaining budget can become 0 even though the other provider has already
     * completed. In that case, we should still collect the result instead of
     * cancelling it and returning fallback.
     * </p>
     */

    private <T> T safeGetNow(Future<T> future, T fallback, String tag, String stage) {
        final String step = "safeGetNow";
        if (future == null) {
            // missing_future is a scheduling/branching outcome, not a hard failure.
            recordAwaitEvent("soft", tag, step, "missing_future", 0L, 0L, null);
            return fallback;
        }
        long startNs = System.nanoTime();
        try {
            T v = future.get();
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, (v != null ? "done" : "done_null"), 0L, waitedMs, null);
            return (v != null) ? v : fallback;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, "interrupted", 0L, waitedMs, ie);
            if (isTraceTag(tag)) {
                log.debug("[{}] Interrupted while collecting result{}", tag, LogCorrelation.suffix());
            } else {
                log.warn("[{}] Interrupted while collecting result{}", tag, LogCorrelation.suffix());
            }
            return fallback;
        } catch (ExecutionException ee) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, "execution", 0L, waitedMs, ee);
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed while collecting result: errorHash={} errorLength={}{}", tag, SafeRedactor.hashValue(ee.getMessage()), ee.getMessage() == null ? 0 : ee.getMessage().length(), LogCorrelation.suffix());
            } else {
                log.warn("[{}] Failed while collecting result: errorHash={} errorLength={}{}", tag, SafeRedactor.hashValue(ee.getMessage()), ee.getMessage() == null ? 0 : ee.getMessage().length(), LogCorrelation.suffix());
            }
            return fallback;
        } catch (Exception e) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, "exception", 0L, waitedMs, e);
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed while collecting result: errorHash={} errorLength={}{}", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix());
            } else {
                log.warn("[{}] Failed while collecting result: errorHash={} errorLength={}{}", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix());
            }
            return fallback;
        }
    }

    private static boolean isDbgSearch() {
        try {
            String v = MDC.get("dbgSearch");
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Throwable ignore) {
            traceSuppressed("debugSearch.mdc", ignore);
            return false;
        }
    }

    private static String safeMdc(String key) {
        try {
            return MDC.get(key);
        } catch (Throwable ignore) {
            traceSuppressed("safeMdc.get", ignore);
            return null;
        }
    }

    /**
     * Returns true if {@code text} contains any substring listed in a
     * comma-separated list.
     * Matching is case-insensitive and ignores surrounding whitespace.
     */
    private static boolean matchesAnyCsvSubstring(String text, String csvSubstrings) {
        if (text == null || text.isBlank() || csvSubstrings == null || csvSubstrings.isBlank()) {
            return false;
        }
        String hay = text.toLowerCase(java.util.Locale.ROOT);
        for (String raw : csvSubstrings.split(",")) {
            if (raw == null) {
                continue;
            }
            String needle = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (needle.isEmpty()) {
                continue;
            }
            if (hay.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ?????????????????????????????????????????????????????????????
    // Fail-soft checkpoint: record *why* Brave was not scheduled/awaited.
    // This is distinct from web.await.skipped.* (which only sees missing_future).
    // Expected values (ops/CI): breaker_open | cooldown | hedge_skip
    // ?????????????????????????????????????????????????????????????
    private static void recordBraveSkipped(String reason, String stage, long extraMs, Throwable err) {
        try {
            TraceStore.put("web.brave.skipped", true); TraceStore.put("hybrid.web.provider.brave.skipped", true);
            if (reason != null && !reason.isBlank()) { TraceStore.put("web.brave.skipped.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown")); TraceStore.put("hybrid.web.provider.brave.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown")); }
            if (stage != null && !stage.isBlank())
                TraceStore.put("web.brave.skipped.stage", stage);
            if (extraMs > 0)
                TraceStore.put("web.brave.skipped.extraMs", extraMs);
            if (err != null) {
                TraceStore.put("web.brave.skipped.err", err instanceof WebClientResponseException w && w.getStatusCode().value() == 429 ? "rate-limit" : NightmareBreaker.classify(err) == NightmareBreaker.FailureKind.TIMEOUT ? "timeout" : NightmareBreaker.classify(err) == NightmareBreaker.FailureKind.INTERRUPTED ? (err instanceof java.util.concurrent.CancellationException ? "cancelled" : "interrupted") : NightmareBreaker.classify(err) == NightmareBreaker.FailureKind.RATE_LIMIT ? "rate-limit" : SafeRedactor.traceLabelOrFallback(err.getClass().getSimpleName(), "unknown"));
            }
            TraceStore.put("web.brave.skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web.brave.skipped.count");
        } catch (Throwable ignore) {
            traceSuppressed("braveSkipped.trace", ignore);
        }
    }

    private static void recordBraveSkipped(String reason, String stage, long extraMs) {
        recordBraveSkipped(reason, stage, extraMs, null);
    }

    // ?????????????????????????????????????????????????????????????
    // Fail-soft checkpoint: record *why* Naver was not scheduled/awaited.
    // This is distinct from web.await.skipped.* (which only sees missing_future).
    // Expected values (ops/CI): breaker_open | disabled | submit_failed
    // ?????????????????????????????????????????????????????????????
    private static void recordNaverSkipped(String reason, String stage, long extraMs, Throwable err) {
        try {
            TraceStore.put("web.naver.skipped", true); TraceStore.put("hybrid.web.provider.naver.skipped", true);
            if (reason != null && !reason.isBlank()) { TraceStore.put("web.naver.skipped.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown")); TraceStore.put("hybrid.web.provider.naver.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown")); }
            if (stage != null && !stage.isBlank())
                TraceStore.put("web.naver.skipped.stage", stage);
            if (extraMs > 0)
                TraceStore.put("web.naver.skipped.extraMs", extraMs);
            if (err != null) {
                TraceStore.put("web.naver.skipped.err", err instanceof WebClientResponseException w && w.getStatusCode().value() == 429 ? "rate-limit" : NightmareBreaker.classify(err) == NightmareBreaker.FailureKind.TIMEOUT ? "timeout" : NightmareBreaker.classify(err) == NightmareBreaker.FailureKind.INTERRUPTED ? (err instanceof java.util.concurrent.CancellationException ? "cancelled" : "interrupted") : NightmareBreaker.classify(err) == NightmareBreaker.FailureKind.RATE_LIMIT ? "rate-limit" : SafeRedactor.traceLabelOrFallback(err.getClass().getSimpleName(), "unknown"));
            }
            TraceStore.put("web.naver.skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web.naver.skipped.count");
        } catch (Throwable ignore) {
            traceSuppressed("naverSkipped.trace", ignore);
        }
    }

    private static void recordNaverSkipped(String reason, String stage, long extraMs) {
        recordNaverSkipped(reason, stage, extraMs, null);
    }

    /**
     * Cache-only escape hatch for Naver results (never hits the network).
     *
     * <p>
     * This mirrors {@link #braveCacheOnlyMeta(String, int, String)} so that when
     * Naver scheduling is intentionally skipped (breaker-open / disabled /
     * submit-failed),
     * downstream await logic does not see {@code missing_future}. That keeps
     * {@code web.await.skipped.Naver.count} meaningful for wiring-bug detection.
     * </p>
     */
    private List<String> naverCacheOnlySnippets(String query, int topK, String reason) {
        try {
            List<String> cached = Collections.emptyList();
            if (naverService != null && query != null && !query.isBlank() && topK > 0) {
                // Default ladder: STRICT -> CONFIG -> CONFIG(boost) -> FREE.
                cached = naverService.searchSnippetsCacheOnly(query, topK, null);
            }
            if (cached == null) {
                cached = Collections.emptyList();
            }
            // Extra breadcrumb for the provider-skip path.
            try {
                TraceStore.put("web.naver.cacheOnlyFuture.used", true);
                if (reason != null && !reason.isBlank()) {
                    TraceStore.put("web.naver.cacheOnlyFuture.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                }
                TraceStore.put("web.naver.cacheOnlyFuture.count", cached.size());
            } catch (Throwable suppressed) { traceSuppressed("naverCacheOnlyFuture.trace", suppressed); }
            return cached;
        } catch (Throwable t) {
            traceSuppressed("naverCacheOnlyFuture", t);
            return Collections.emptyList();
        }
    }

    private NaverSearchService.SearchResult naverCacheOnlyTraceResult(String query, int topK, String reason) {
        try {
            List<String> cached = naverCacheOnlySnippets(query, topK, reason);
            if (cached == null) {
                cached = Collections.emptyList();
            }
            NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
            String step = "NAVER:CACHE_ONLY";
            if (reason != null && !reason.isBlank()) {
                step += "(" + SafeRedactor.traceLabelOrFallback(reason, "unknown") + ")";
            }
            trace.steps.add(new NaverSearchService.SearchStep(step, cached.size(), cached.size(), 0L));
            return new NaverSearchService.SearchResult(cached, trace);
        } catch (Throwable t) {
            traceSuppressed("naverCacheOnlyTraceResult", t);
            return new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }
    }

    /**
     * Build a synthetic BraveSearchResult from cache only (never hits the network).
     *
     * <p>
     * This is used when Brave scheduling is intentionally skipped (breaker-open /
     * cooldown / hedge-skip / submit-failed)
     * so downstream await logic does not see {@code missing_future} and the request
     * can still recover with cached snippets.
     * </p>
     */
    private BraveSearchResult braveCacheOnlyMeta(String braveQuery, int braveK, String reason) {
        try {
            List<String> cached = Collections.emptyList();
            if (braveService != null && braveQuery != null && !braveQuery.isBlank() && braveK > 0) {
                cached = braveService.searchCacheOnly(braveQuery, braveK);
            }
            if (cached == null) {
                cached = Collections.emptyList();
            }
            String msg = "cache_only";
            if (reason != null && !reason.isBlank()) {
                msg = msg + ":" + SafeRedactor.traceLabelOrFallback(reason, "unknown");
            }
            // elapsedMs is unknown for cache-only reads; keep 0.
            return new BraveSearchResult(cached, BraveSearchResult.Status.OK, 200, 0L, msg, 0L);
        } catch (Throwable t) {
            traceSuppressed("braveCacheOnlyResult", t);
            return BraveSearchResult.ok(Collections.emptyList(), 0L);
        }
    }

    private static void recordWebHardDown(String reason, String stage) {
        try {
            TraceStore.put("web.hardDown", true);
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("web.hardDown.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            }
            if (stage != null && !stage.isBlank()) {
                TraceStore.put("web.hardDown.stage", stage);
            }
            TraceStore.put("web.hardDown.tsMs", System.currentTimeMillis());
            TraceStore.inc("web.hardDown.count");
        } catch (Throwable ignore) {
            traceSuppressed("webHardDown.trace", ignore);
        }
    }

    private static void recordAwaitEvent(
            String stage,
            String engine,
            String step,
            String cause,
            long timeoutMs,
            long waitedMs,
            Throwable err) {
        try {
            boolean dbg = isDbgSearch();
            boolean boostMode = "boost".equalsIgnoreCase(safeMdc("dbgSearchSrc"));
            String boostDetailEnginesCsv = safeMdc("dbgSearchBoostEngines");
            boolean detail = !boostMode
                    || boostDetailEnginesCsv == null
                    || boostDetailEnginesCsv.isBlank()
                    || matchesAnyCsvSubstring(engine, boostDetailEnginesCsv);

            String c = (cause == null) ? "" : cause.trim().toLowerCase(java.util.Locale.ROOT);

            // missing_future / skip_* are scheduling outcomes (hedge skip, branch not
            // taken),
            // not provider failures. Treat them as okish so they don't pollute nonOk KPIs.
            boolean isSkip = c.equals("missing_future") || c.startsWith("skip_");
            boolean okish = c.equals("ok") || c.equals("done") || c.equals("done_null") || isSkip;

            boolean timeoutFlag = c.equals("timeout")
                    || c.equals("budget_exhausted")
                    || c.equals("timeout_soft")
                    || c.equals("timeout_hard");

            // Timeout kind split (dashboard/report-friendly):
            // - soft timeout: awaitSoft (opportunistic wait) + orchestration-side
            // budget_exhausted
            // - hard timeout: real awaitWithDeadline get(timeout) expiration
            boolean stageSoft = "soft".equalsIgnoreCase(String.valueOf(stage));
            boolean softTimeout = timeoutFlag
                    && (stageSoft || c.equals("budget_exhausted") || c.equals("timeout_soft"));
            boolean hardTimeout = timeoutFlag && !softTimeout;

            // Track skips even when we drop okish events in normal mode.
            // Also keep the last skip per-engine to make investigation easier.
            if (isSkip) {
                TraceStore.inc("web.await.skipped.count");
                TraceStore.put("web.await.skipped.last", SafeRedactor.traceLabelOrFallback(cause, c));
                if (SafeRedactor.traceLabel(engine) != null && !SafeRedactor.traceLabel(engine).isBlank()) {
                    TraceStore.inc("web.await.skipped." + SafeRedactor.traceLabel(engine) + ".count");
                    TraceStore.put("web.await.skipped." + SafeRedactor.traceLabel(engine) + ".last", SafeRedactor.traceLabelOrFallback(cause, c));
                }
                if (SafeRedactor.traceLabel(engine) != null && !SafeRedactor.traceLabel(engine).isBlank()) {
                    TraceStore.put("web.await.skipped.last.engine", SafeRedactor.traceLabel(engine));
                }
                if (SafeRedactor.traceLabel(step) != null && !SafeRedactor.traceLabel(step).isBlank()) {
                    TraceStore.put("web.await.skipped.last.step", SafeRedactor.traceLabel(step));
                }
            }

            // Noise control:
            // - Normal mode: keep only non-ok events (timeouts, budget_exhausted, cancel,
            // etc.).
            // - Manual debug: keep everything + detail.
            // - Boost debug: keep everything only for selected engines; other engines keep
            // non-ok only.
            boolean keepOkish = dbg && (!boostMode || detail);

            // Even in normal mode, keep at least one representative
            // "missing_future"/"skip_*"
            // event per engine so async wiring bugs do not become invisible.
            boolean forceKeep = false;
            if (!keepOkish && isSkip) {
                try {
                    String eng = SafeRedactor.traceLabelOrFallback(engine, "unknown");
                    String key = "web.await.keepOnce." + SafeRedactor.traceLabelOrFallback(cause, c) + "." + eng;
                    Object prev = TraceStore.putIfAbsent(key, Boolean.TRUE);
                    forceKeep = (prev == null);
                } catch (Throwable ignore) {
                    traceSuppressed("await.keepOnce.trace", ignore);
                }
            }

            if (okish && !keepOkish && !forceKeep) {
                return;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", TraceStore.inc("web.await.events.seq"));
            m.put("tsMs", System.currentTimeMillis());
            m.put("stage", SafeRedactor.traceLabel(stage));
            m.put("engine", SafeRedactor.traceLabel(engine));
            m.put("step", SafeRedactor.traceLabel(step));
            m.put("cause", SafeRedactor.traceLabel(cause));
            m.put("timeoutMs", timeoutMs);
            m.put("waitedMs", waitedMs);
            m.put("skip", isSkip);
            m.put("timeout", timeoutFlag);
            m.put("softTimeout", softTimeout);
            m.put("hardTimeout", hardTimeout);
            m.put("nonOk", !okish);
            m.put("detail", detail);

            // Correlation breadcrumbs for timeout/cancel debugging.
            // Keep it lightweight: only attach when the event is non-ok or when explicit
            // dbgSearch is enabled.
            if (!okish || dbg) {
                String rid = safeMdc("x-request-id");
                if (rid != null && !rid.isBlank()) {
                    m.put("rid", SafeRedactor.hashValue(rid));
                }
                String sid = safeMdc("sessionId");
                if (sid != null && !sid.isBlank()) {
                    m.put("sid", SafeRedactor.hashValue(sid));
                }
            }

            if (err != null) {
                // Prefer the root cause to keep ExecutionException wrappers out of the trace.
                Throwable root = err;
                if (root instanceof java.util.concurrent.ExecutionException ex && ex.getCause() != null) {
                    root = ex.getCause();
                }

                m.put("err", root.getClass().getSimpleName());

                if (dbg && (!boostMode || detail)) {
                    String msg = root.getMessage();
                    if (msg != null && !msg.isBlank()) {
                        m.put("errMsgHash", SafeRedactor.hashValue(msg));
                        m.put("errMsgLength", msg.length());
                    }
                }

                // Non-consuming error-body preview for WebClientResponseException.
                // (WebClientResponseException stores the body bytes in-memory.)
                if (root instanceof WebClientResponseException w) {
                    try {
                        m.put("httpStatus", w.getStatusCode().value());
                    } catch (Throwable ignore) {
                        traceSuppressed("await.httpStatus", ignore);
                    }
                    if (dbg && (!boostMode || detail)) {
                        try {
                            String body = w.getResponseBodyAsString();
                            if (body != null && !body.isBlank()) {
                                m.put("httpBodyHash", SafeRedactor.hashValue(body));
                                m.put("httpBodyLength", body.length());
                            }
                        } catch (Throwable ignore) {
                            traceSuppressed("await.httpBody", ignore);
                        }
                        try {
                            var req = w.getRequest();
                            if (req != null && req.getURI() != null) {
                                URI uri = req.getURI();
                                String rawQuery = uri.getRawQuery();
                                m.put("httpTarget", hostPath(uri));
                                m.put("httpHasQuery", rawQuery != null && !rawQuery.isBlank());
                                if (rawQuery != null && !rawQuery.isBlank()) {
                                    m.put("httpQueryHash", SafeRedactor.hashValue(rawQuery));
                                }
                            }
                        } catch (Throwable ignore) {
                            traceSuppressed("await.httpRequest", ignore);
                        }
                    }
                }
            }

            // Append as an ordered list, and also keep the latest event.
            TraceStore.append("web.await.events", m);
            TraceStore.put("web.await.last", m);

            // Lightweight counters for trace dashboards.
            TraceStore.inc("web.await.events.count");
            if (!okish) {
                TraceStore.inc("web.await.events.nonOk.count");
            }
            if (timeoutFlag) {
                TraceStore.inc("web.await.events.timeout.count");

                // Split counters so dashboards can compute percentages without soft-timeout
                // noise.
                if (softTimeout) {
                    TraceStore.inc("web.await.events.timeout.soft.count");
                } else if (hardTimeout) {
                    TraceStore.inc("web.await.events.timeout.hard.count");
                }
            }
            if ("soft".equalsIgnoreCase(String.valueOf(stage))) {
                TraceStore.inc("web.await.events.soft.count");
            } else if ("hard".equalsIgnoreCase(String.valueOf(stage))) {
                TraceStore.inc("web.await.events.hard.count");
            }
            if (waitedMs > 0) {
                TraceStore.maxLong("web.await.events.maxWaitedMs", waitedMs);
            }
        } catch (Throwable ignore) {
            traceSuppressed("await.event.record", ignore);
        }
    }

    private static String hostPath(URI uri) {
        if (uri == null) {
            return "";
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (host == null || host.isBlank()) {
            return path;
        }
        return host + path;
    }

    private <T> T awaitWithDeadline(
            Future<T> future,
            long deadlineNs,
            T fallback,
            String tag) {

        final String step = "awaitWithDeadline";

        if (future == null) {
            // missing_future is a scheduling/branching outcome, not a hard failure.
            recordAwaitEvent("soft", tag, step, "missing_future", 0L, 0L, null);
            return fallback;
        }

        // If the task has already completed, always collect it (even if budget is
        // exhausted).
        // This avoids provider starvation when the first await used up the shared
        // deadline.
        if (future.isDone()) {
            return safeGetNow(future, fallback, tag, "hard");
        }

        final TimeBudget waitBudget = TimeBudget.untilNanoDeadline(deadlineNs);
        final long rawTimeoutMs = waitBudget.remainingMillis();
        final long nearMs = Math.max(0L, awaitNearExhaustedThresholdMs);

        final boolean budgetExhausted = rawTimeoutMs <= 0L;

        // officialOnly floor: allow a slightly larger min-live-budget in officialOnly mode
        // (where evidence quality is prioritized and "tiny_budget/budget_exhausted" cascades are costly).
        boolean officialOnly = false;
        try {
            GuardContext gctx = GuardContextHolder.getOrDefault();
            officialOnly = gctx != null && gctx.isOfficialOnly();
        } catch (Throwable ignore) {
            traceSuppressed("await.officialOnly.context", ignore);
            officialOnly = false;
        }

        long floorMs = Math.max(0L, awaitMinLiveBudgetMs);
        if (officialOnly) {
            floorMs = Math.max(floorMs, Math.max(0L, awaitMinLiveBudgetMsOfficialOnly));
            try {
                TraceStore.put("web.await.minLiveBudget.officialOnly", true);
                TraceStore.put("web.await.minLiveBudget.officialOnly.floorMs", floorMs);
            } catch (Throwable suppressed) { traceSuppressed("await.minLiveBudget.officialOnly", suppressed); }
        }

        final boolean nearExhausted = rawTimeoutMs > 0L && nearMs > 0L && rawTimeoutMs <= nearMs;
        final boolean tinyBudget = rawTimeoutMs > 0L && floorMs > 0L && rawTimeoutMs < floorMs;

        final boolean floorApplied = !budgetExhausted && floorMs > 0L
                && (nearExhausted
                        || (awaitFloorTinyBudget && tinyBudget));
        final String floorCause = budgetExhausted ? "budget_exhausted"
                : (nearExhausted ? "near_exhausted" : (tinyBudget ? "tiny_budget" : "none"));

        long timeoutMs = waitBudget.capWaitMillis(floorApplied ? floorMs : rawTimeoutMs);
        String stepLabel = floorApplied ? (step + ".minLiveBudget") : step;

        // Provider-specific clamp: prevent Naver from consuming the whole deadline and
        // starving the follow-up branch (e.g., Brave -> tiny_budget/budget_exhausted).
        if (isNaverTag(tag)) {
            long cap = Math.max(0L, naverBlockTimeoutCapMs);
            if (cap > 0L && timeoutMs > cap) {
                try {
                    TraceStore.put("web.await.naver.capMs", cap);
                    TraceStore.put("web.await.naver.cappedFromMs", timeoutMs);
                    TraceStore.inc("web.await.naver.capped.count");
                } catch (Throwable ignore) {
                    traceSuppressed("await.naverCap.trace", ignore);
                }
                timeoutMs = cap;
            }
        }

        if (budgetExhausted) {
            // Re-check completion (race-safe) before giving up.
            if (future.isDone()) {
                return safeGetNow(future, fallback, tag, "hard");
            }

            TraceStore.inc("web.await.budgetExhausted");

            // An exhausted request cannot acquire another wait, including an official-only floor.
            // IMPORTANT: do NOT cancel here; cancellation can drop near-complete results
            // and amplifies starvation.
            TraceStore.inc("web.await.cancelSuppressed");
            try {
                TraceStore.put("web.await.cancelSuppressed.reason", "budget_exhausted");
            } catch (Exception ignore) {
                traceSuppressed("await.cancelSuppressed.budgetExhausted", ignore);
            }
            if (isTraceTag(tag)) {
                log.debug("[{}] Hard Timeout (budget exhausted) - no cancel{}", tag, LogCorrelation.suffix());
            } else {
                log.warn("[{}] Hard Timeout (budget exhausted) - no cancel{}", tag, LogCorrelation.suffix());
            }
            recordAwaitEvent("hard", tag, step, "budget_exhausted", 0L, 0L, null);
            return fallback;
        } else if (nearExhausted) {
            // Budget is technically positive but too small to be meaningful.
            // Keep the existing floor diagnostics, but cap any wait at the request deadline.
            try {
                TraceStore.inc("web.await.nearExhausted.count");
                TraceStore.put("web.await.nearExhausted.ms", rawTimeoutMs);
            } catch (Exception ignore) {
                traceSuppressed("await.nearExhausted.trace", ignore);
            }
        } else if (tinyBudget && awaitFloorTinyBudget) {
            // Tiny remaining budget (1..floorMs-1) can be a rounding artifact or a race
            // with nanoTime deadlines.
            // Apply a floor and optionally suppress cancellation so late results can be
            // harvested by downstream rescue/remerge logic.
            try {
                TraceStore.put("web.await.minLiveBudget.tinyBudget", true);
                TraceStore.put("web.await.minLiveBudget.tinyBudget.rawTimeoutMs", rawTimeoutMs);
            } catch (Exception ignore) {
                traceSuppressed("await.tinyBudget.trace", ignore);
            }
        }
        timeoutMs = waitBudget.capWaitMillis(timeoutMs);
        if (timeoutMs == 0L) {
            if (future.isDone()) {
                return safeGetNow(future, fallback, tag, "hard");
            }
            recordAwaitEvent("hard", tag, step, "budget_exhausted", 0L, 0L, null);
            return fallback;
        }
        long startNs = System.nanoTime();
        try {
            T v = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (floorApplied) {
                TraceStore.put("web.await.minLiveBudget.used", true);
                TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                TraceStore.put("web.await.minLiveBudget.cause", floorCause);
            }
            recordAwaitEvent("hard", tag, stepLabel, "ok", timeoutMs, waitedMs, null);
            return v;
        } catch (TimeoutException te) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);

            if (floorApplied && awaitCancelSuppressedWhenFloor) {
                // cancel_suppressed: allow late completion (cache/rescue merge can still pick
                // it up).
                TraceStore.inc("web.await.cancelSuppressed");
                try {
                    TraceStore.put("web.await.cancelSuppressed.reason", SafeRedactor.traceLabelOrFallback(floorCause, "unknown"));
                } catch (Exception ignore) {
                    traceSuppressed("await.cancelSuppressed.floorTimeout", ignore);
                }
                TraceStore.put("web.await.minLiveBudget.used", true);
                TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                TraceStore.put("web.await.minLiveBudget.cause", floorCause);

                if (isTraceTag(tag)) {
                    log.debug("[{}] Hard Timeout (cause={} raw={}ms -> floor={}ms) - cancel_suppressed{}",
                            tag, floorCause, rawTimeoutMs, timeoutMs, LogCorrelation.suffix());
                } else {
                    log.warn("[{}] Hard Timeout (cause={} raw={}ms -> floor={}ms) - cancel_suppressed{}",
                            tag, floorCause, rawTimeoutMs, timeoutMs, LogCorrelation.suffix());
                }

                // One-shot late harvest: if it completed right after timeout, collect now.
                if (future.isDone()) {
                    return safeGetNow(future, fallback, tag, "hard");
                }

                recordAwaitEvent("hard", tag, stepLabel, floorCause, timeoutMs, waitedMs, te);
                return fallback;
            }

            if (floorApplied) {
                // Floor was applied but cancel-suppression is disabled.
                // Still record the floor cause for diagnostics.
                try {
                    TraceStore.put("web.await.minLiveBudget.used", true);
                    TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                    TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                    TraceStore.put("web.await.minLiveBudget.cause", floorCause);
                } catch (Exception ignore) {
                    traceSuppressed("await.minLiveBudget.floorTimeout", ignore);
                }
            }

            try {
                // cancel(false): do not interrupt pool workers (interrupt can poison executors)
                future.cancel(false);
            } catch (Throwable ignore) {
                traceSuppressed("await.cancelFalse.timeout", ignore);
            }

            if (isTraceTag(tag)) {
                log.debug("[{}] Hard Timeout ({}ms) - cancel(false){}",
                        tag, timeoutMs, LogCorrelation.suffix());
            } else {
                log.warn("[{}] Hard Timeout ({}ms) - cancel(false){}",
                        tag, timeoutMs, LogCorrelation.suffix());
            }
            if (!floorApplied) {
                maybeInstallAwaitTimeoutBackoff(tag, timeoutMs, waitedMs);
            }
            recordAwaitEvent("hard", tag, stepLabel, floorApplied ? floorCause : "await_timeout", timeoutMs, waitedMs, te);
            return fallback;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);

            if (floorApplied && awaitCancelSuppressedWhenFloor) {
                TraceStore.inc("web.await.cancelSuppressed");
                try {
                    TraceStore.put("web.await.cancelSuppressed.reason", SafeRedactor.traceLabelOrFallback(floorCause, "unknown"));
                } catch (Exception ignore) {
                    traceSuppressed("await.cancelSuppressed.floorInterrupted", ignore);
                }
                TraceStore.put("web.await.minLiveBudget.used", true);
                TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                TraceStore.put("web.await.minLiveBudget.cause", floorCause);

                if (isTraceTag(tag)) {
                    log.debug("[{}] Interrupted - cancel_suppressed{}", tag, LogCorrelation.suffix());
                } else {
                    log.warn("[{}] Interrupted - cancel_suppressed{}", tag, LogCorrelation.suffix());
                }

                recordAwaitEvent("hard", tag, stepLabel, "interrupted", timeoutMs, waitedMs, ie);
                return fallback;
            }

            if (floorApplied) {
                try {
                    TraceStore.put("web.await.minLiveBudget.used", true);
                    TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                    TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                    TraceStore.put("web.await.minLiveBudget.cause", floorCause);
                } catch (Exception ignore) {
                    traceSuppressed("await.minLiveBudget.floorInterrupted", ignore);
                }
            }

            try {
                // cancel(false): do not interrupt pool workers (interrupt can poison executors)
                future.cancel(false);
            } catch (Throwable ignore) {
                traceSuppressed("await.cancelFalse.interrupted", ignore);
            }
            if (isTraceTag(tag)) {
                log.debug("[{}] Interrupted - cancel(false){}", tag, LogCorrelation.suffix());
            } else {
                log.warn("[{}] Interrupted - cancel(false){}", tag, LogCorrelation.suffix());
            }
            recordAwaitEvent("hard", tag, stepLabel, "interrupted", timeoutMs, waitedMs, ie);
            return fallback;
        } catch (Exception e) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);

            if (floorApplied && awaitCancelSuppressedWhenFloor) {
                TraceStore.inc("web.await.cancelSuppressed");
                try {
                    TraceStore.put("web.await.cancelSuppressed.reason", SafeRedactor.traceLabelOrFallback(floorCause, "unknown"));
                } catch (Exception ignore) {
                    traceSuppressed("await.cancelSuppressed.floorException", ignore);
                }
                TraceStore.put("web.await.minLiveBudget.used", true);
                TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                TraceStore.put("web.await.minLiveBudget.cause", floorCause);

                if (isTraceTag(tag)) {
                    log.debug("[{}] Hard await failed (floorApplied) - cancel_suppressed: errorHash={} errorLength={}{}",
                            tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix());
                } else {
                    log.warn("[{}] Hard await failed (floorApplied) - cancel_suppressed: errorHash={} errorLength={}{}",
                            tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix());
                }

                String cause = (e instanceof java.util.concurrent.ExecutionException) ? "execution" : "exception";
                recordAwaitEvent("hard", tag, stepLabel, cause, timeoutMs, waitedMs, e);
                return fallback;
            }

            if (floorApplied) {
                try {
                    TraceStore.put("web.await.minLiveBudget.used", true);
                    TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                    TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                    TraceStore.put("web.await.minLiveBudget.cause", floorCause);
                } catch (Exception ignore) {
                    traceSuppressed("await.minLiveBudget.floorException", ignore);
                }
            }

            try {
                // cancel(false): do not interrupt pool workers (interrupt can poison executors)
                future.cancel(false);
            } catch (Throwable ignore) {
                traceSuppressed("await.cancelFalse.exception", ignore);
            }
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed: errorHash={} errorLength={}", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            } else {
                log.warn("[{}] Failed: errorHash={} errorLength={}", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
            String cause = (e instanceof java.util.concurrent.ExecutionException) ? "execution" : "exception";
            recordAwaitEvent("hard", tag, stepLabel, cause, timeoutMs, waitedMs, e);
            return fallback;
        }
    }

    /**
     * Opportunistic wait: wait at most timeoutMs for the future, then return
     * fallback quietly.
     * Used when we already have enough results from another provider.
     */

    private long adjustSoftTimeoutMs(long baseMs, String tag) {
        if (tag == null)
            return baseMs;
        if (!isNaverTag(tag))
            return baseMs;
        long ewma = naverEwmaMs.get();
        int streak = naverSoftTimeoutStreak.get();
        // consecutive soft-timeout -> 250 ??400 ??550 ??... (cap 1500ms)
        return Math.max(baseMs, Math.min(1500L, ewma + (150L * Math.min(6, streak))));
    }

    private void onSoftTimeout(String tag, long waitedMs) {
        if (tag == null)
            return;
        if (!isNaverTag(tag))
            return;
        naverSoftTimeoutStreak.incrementAndGet();
        naverEwmaMs.updateAndGet(old -> Math.max(old, waitedMs));
    }

    private void onSoftSuccess(String tag, long waitedMs) {
        if (tag == null)
            return;
        if (!isNaverTag(tag))
            return;
        naverSoftTimeoutStreak.set(0);
        naverEwmaMs.updateAndGet(old -> (long) (old * 0.8 + waitedMs * 0.2));
    }
    private void maybeInstallAwaitTimeoutBackoff(String tag, long timeoutMs, long waitedMs) {
        if (rateLimitBackoffCoordinator == null) {
            return;
        }
        String providerKey = isNaverTag(tag) ? "naver" : "brave";
        String appliedKey = "web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutApplied";
        Object already = TraceStore.get(appliedKey);
        if (Boolean.TRUE.equals(already)) {
            return;
        }

        // Keep a per-request counter for diagnostics (the coordinator keeps global counters internally).
        String detectedKey = "web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutDetected";
        long detected = 0L;
        try {
            Object cur = TraceStore.get(detectedKey);
            if (cur instanceof Number) {
                detected = ((Number) cur).longValue();
            } else if (cur instanceof String) {
                detected = Long.parseLong((String) cur);
            }
        } catch (Throwable suppressed) { traceSuppressed("awaitTimeout.detectedCount", suppressed); }
        TraceStore.put(detectedKey, detected + 1);

        try {
            // AWAIT_TIMEOUT is a local join/await timebox signal. Treat it like a shallow Retry-After with jitter+cap.
            String reason = "hybrid_await_timeout";
            String detail = "timeoutMs=" + timeoutMs + " waitedMs=" + waitedMs;
            rateLimitBackoffCoordinator.recordFailure(providerKey,
                    RateLimitBackoffCoordinator.FailureKind.AWAIT_TIMEOUT,
                    reason,
                    detail);
            // Mark as applied so WebFailSoftSearchAspect's await-summary bridge does not double-apply.
            TraceStore.put(appliedKey, true);
            TraceStore.put("web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutApplied.source", "hybrid.awaitWithDeadline");
            TraceStore.put("web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutApplied.timeoutMs", timeoutMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutApplied.waitedMs", waitedMs);
            long appliedTs = System.currentTimeMillis();
            TraceStore.put("web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutApplied.tsMs", appliedTs);

            // For debug/soak: surface the immediately-installed cooldown window even if the next
            // provider call doesn't happen in this request.
            try {
                RateLimitBackoffCoordinator.Decision d = rateLimitBackoffCoordinator.shouldSkip(providerKey);
                if (d != null && d.shouldSkip()) {
                    TraceStore.put("web.failsoft.rateLimitBackoff." + providerKey + ".awaitTimeoutApplied.remainingMs", d.remainingMs());
                    TraceStore.putIfAbsent("web.failsoft.rateLimitBackoff." + providerKey + ".reason", SafeRedactor.traceLabel(d.reason()));
                    TraceStore.putIfAbsent("web.failsoft.rateLimitBackoff." + providerKey + ".remainingMs", d.remainingMs());
                }
            } catch (Throwable ignore) {
                traceSuppressed("rateLimitBackoff.awaitTimeoutState", ignore);
            }
        } catch (Throwable t) {
            log.debug("[WEB_AWAIT_TIMEOUT_BACKOFF] provider={} install failed errorHash={} errorLength={}", providerKey, SafeRedactor.hashValue(t.getMessage()), t.getMessage() == null ? 0 : t.getMessage().length());
        }
    }



    private <T> T awaitSoft(Future<T> future, long softTimeoutMs, T fallback, String tag) {
        final String step = "awaitSoft";
        softTimeoutMs = capRequestWait(softTimeoutMs);

        if (future == null) {
            recordAwaitEvent("soft", tag, step, "missing_future", softTimeoutMs, 0L, null);
            return fallback;
        }

        // Small optimization: if already completed, don't pay soft-timeout overhead.
        if (future.isDone()) {
            return safeGetNow(future, fallback, tag, "soft");
        }
        if (softTimeoutMs == 0L) {
            recordAwaitEvent("soft", tag, step, "budget_exhausted", 0L, 0L, null);
            return fallback;
        }

        long startNs = System.nanoTime();
        try {
            T v = future.get(softTimeoutMs, TimeUnit.MILLISECONDS);
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent("soft", tag, step, "ok", softTimeoutMs, waitedMs, null);
            return v;
        } catch (TimeoutException te) {
            // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Soft Timeout after {}ms", tag, softTimeoutMs);
            }
            recordAwaitEvent("soft", tag, step, "timeout", softTimeoutMs, waitedMs, te);
            return fallback;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Soft Interrupted", tag);
            }
            recordAwaitEvent("soft", tag, step, "interrupted", softTimeoutMs, waitedMs, ie);
            return fallback;
        } catch (Exception e) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Soft await failed errorHash={} errorLength={}", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
            String cause = (e instanceof java.util.concurrent.ExecutionException) ? "execution" : "exception";
            recordAwaitEvent("soft", tag, step, cause, softTimeoutMs, waitedMs, e);
            return fallback;
        }
    }

    private List<String> searchKoreanBraveAndNaver(String query, int topK) {

        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = requestDeadline(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        boolean skipNaver = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));

        // --- Brave-first hedged strategy ---
        Future<BraveSearchResult> braveFuture = null;
        final int braveK = Math.min(Math.max(topK, 5), 20);
        boolean braveLiveCall = false;
        boolean braveCacheOnly = false;
        boolean braveSkipRecorded = false;

        String braveSkipReason = null;
        long braveSkipExtraMs = 0L;

        if (braveService == null || !braveService.isEnabled()) {
            braveSkipReason = "disabled";
            // skip
        } else if (skipBrave) {
            braveSkipReason = "breaker_open";
            try {
                braveSkipExtraMs = nightmareBreaker == null ? 0L
                        : nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_BRAVE);
            } catch (Throwable ignore) {
                traceSuppressed("korean.braveFirst.remainingOpenMs", ignore);
            }
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave call");
        } else if (braveService.isCoolingDown()) {
            braveSkipReason = "cooldown";
            braveSkipExtraMs = braveService.cooldownRemainingMs();
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave call", braveSkipExtraMs);
        } else {
            try {
                braveFuture = submitSearchAttempt(() -> braveService.searchWithMeta(braveQuery, braveK));
                braveLiveCall = true;
            } catch (Exception submitEx) {
                braveSkipReason = "submit_failed";
                recordBraveSkipped(braveSkipReason, "korean.braveFirst", 0L, submitEx);
                braveSkipRecorded = true;
                log.warn("[Hybrid] Brave scheduling failed, skipping Brave call errorHash={} errorLength={}", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());
            }
        }

        if (!braveLiveCall && braveSkipReason != null && !braveSkipRecorded
                && !"submit_failed".equals(braveSkipReason)) {
            recordBraveSkipped(braveSkipReason, "korean.braveFirst", braveSkipExtraMs);
            braveSkipRecorded = true;
        }

        // Even when Brave is intentionally skipped, provide a cache-only Future so
        // await() does not
        // record missing_future (which looks like a wiring bug and inflates
        // web.await.skipped.Brave.count).
        if (braveFuture == null && braveSkipReason != null) {
            braveCacheOnly = true;
            braveFuture = java.util.concurrent.CompletableFuture.completedFuture(
                    braveCacheOnlyMeta(braveQuery, braveK, braveSkipReason));
        }

        BraveSearchResult braveMetaEarly = null;
        boolean braveEarlyEnoughToSkipNaver = false;
        if (braveLiveCall
                && braveFuture != null
                && !skipNaver
                && naverService != null
                && naverService.isEnabled()
                && koreanHedgeDelayMs > 0) {
            long waitMs = Math.min(koreanHedgeDelayMs, remainingMs(deadlineNs));
            if (waitMs > 0) {
                try {
                    braveMetaEarly = braveFuture.get(waitMs, TimeUnit.MILLISECONDS);
                    int braveSz = (braveMetaEarly == null || braveMetaEarly.snippets() == null)
                            ? 0
                            : braveMetaEarly.snippets().size();
                    braveEarlyEnoughToSkipNaver = braveMetaEarly != null
                            && braveMetaEarly.status() == BraveSearchResult.Status.OK
                            && braveSz >= Math.max(1, skipNaverIfBraveMinResults);
                } catch (TimeoutException ignore) {
                    traceSuppressed("korean.braveFirst.earlyTimeout", ignore);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    traceSuppressed("korean.braveFirst.earlyInterrupted", ie);
                    return Collections.emptyList();
                } catch (Exception ignore) {
                    traceSuppressed("korean.braveFirst.earlyFailure", ignore);
                }
            }
        }

        Future<List<String>> naverFuture = null;
        boolean naverLiveCall = false, naverCacheOnly = false, naverSkippedByHedge = false;
        String naverSkipReason = null; long naverSkipExtraMs = 0L;
        if (skipNaver) {
            naverSkipReason = "breaker_open";
            naverSkipExtraMs = nightmareBreaker == null ? 0L
                    : nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER);
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver call");
        } else if (naverService == null || !naverService.isEnabled()) {
            naverSkipReason = "disabled";
        } else if (!braveEarlyEnoughToSkipNaver || forceOpportunisticNaverEvenIfBraveFast) {
            final int callK = (braveEarlyEnoughToSkipNaver && forceOpportunisticNaverEvenIfBraveFast)
                    ? Math.min(Math.max(1, topK), 3)
                    : topK;
            if (braveEarlyEnoughToSkipNaver && forceOpportunisticNaverEvenIfBraveFast && log.isDebugEnabled()) {
                int braveSz = (braveMetaEarly == null || braveMetaEarly.snippets() == null) ? 0
                        : braveMetaEarly.snippets().size();
                log.debug(
                        "[Hybrid] Korean hedged: Brave {} results within {}ms, still calling Naver opportunistically (k={})",
                        braveSz, koreanHedgeDelayMs, callK);
            }
            final java.time.Duration naverBlockTimeout = java.time.Duration.ofMillis(
                    resolveNaverBlockTimeoutMs(deadlineNs, 0L, "korean.brave-first"));
            naverFuture = submitSearchAttempt(() -> {
                try {
                    return naverService.searchSnippetsSync(query, callK, naverBlockTimeout);
                } catch (Exception e) {
                    traceSuppressed("korean.braveFirst.naverSearchFailure", e);
                    log.warn("[Hybrid] Naver korean search failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                    return Collections.emptyList();
                }
            });
            naverLiveCall = true;
        } else {
            naverSkippedByHedge = true;
            recordNaverSkipped("brave_sufficient", "korean.braveFirst.hedge", 0L);
            if (log.isDebugEnabled()) {
                int braveSz = (braveMetaEarly == null || braveMetaEarly.snippets() == null) ? 0
                        : braveMetaEarly.snippets().size();
                log.debug("[Hybrid] Korean hedged: skipping Naver start (Brave {} results within {}ms)",
                        braveSz, koreanHedgeDelayMs);
            }
        }
        if (!naverLiveCall && naverSkipReason != null && !"submit_failed".equals(naverSkipReason))
            recordNaverSkipped(naverSkipReason, "korean.braveFirst", naverSkipExtraMs);
        if (naverFuture == null && naverSkipReason != null) {
            naverCacheOnly = true;
            naverFuture = java.util.concurrent.CompletableFuture.completedFuture(naverCacheOnlySnippets(query, topK, naverSkipReason));
        }
        if (!braveLiveCall && !naverLiveCall)
            recordWebHardDown("both_skipped", "korean.braveFirst");
        BraveSearchResult braveMeta = (braveMetaEarly != null)
                ? braveMetaEarly
                : awaitWithDeadline(
                        braveFuture,
                        deadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave");
        if (Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }
        List<String> brave = (braveMeta == null) ? Collections.emptyList() : braveMeta.snippets();
        boolean braveEnough = skipNaverIfBraveSufficient && brave != null && brave.size() >= topK;
        List<String> naver = (naverFuture == null)
                ? Collections.emptyList()
                : (braveEnough
                        ? awaitSoft(naverFuture, naverOpportunisticMs, Collections.emptyList(), "Naver")
                        : awaitWithDeadline(naverFuture, deadlineNs, Collections.emptyList(), "Naver"));
        if (Thread.currentThread().isInterrupted()) {
            return mergeAndLimit(brave, naver, topK);
        }

        if (braveMeta != null && braveMeta.status() != BraveSearchResult.Status.OK) {
            log.info("[Hybrid] Brave meta: status={} httpStatus={} cooldownMs={} msg={} elapsedMs={}",
                    braveMeta.status(), braveMeta.httpStatus(), braveMeta.cooldownMs(), braveMeta.message(),
                    braveMeta.elapsedMs());
        }

        if (brave != null && brave.size() == 1 && brave.get(0) != null) {
            String trimmed = brave.get(0).trim();
            if (trimmed.startsWith("{") && trimmed.contains("\"web\"") && trimmed.contains("\"results\"")) {
                log.warn("[Hybrid] Brave returned single JSON-like snippet ({} chars). " +
                        "BraveSearchService may not be parsing JSON properly.", trimmed.length());
            }
        }

        if (brave == null) brave = Collections.emptyList(); if (naver == null) naver = Collections.emptyList();

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if ((naver == null || naver.isEmpty()) && (brave == null || brave.isEmpty())
                && naverFuture != null && !naverFuture.isDone()) {
            try {
                naver = naverFuture.get(capRequestWait(200L), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignore) {
                traceSuppressed("korean.naverLateJoin.timeout", ignore);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                traceSuppressed("korean.naverLateJoin.interrupted", ie);
                return mergeAndLimit(brave, naver, topK);
            } catch (Exception ignore) {
                traceSuppressed("korean.naverLateJoin.failure", ignore);
            }
            if (naver == null)
                naver = Collections.emptyList();
        }

        // ??opportunistic join (deficit): when Naver hard-timeouts but Brave is partially filled,
        // try a small additional join window and/or cache-only to stabilize merge quality.
        if ((naver == null || naver.isEmpty())
                && brave != null && !brave.isEmpty()
                && brave.size() < topK
                && naverFuture != null) {
            final long joinMs = Math.min(200L, Math.max(50L, naverOpportunisticMs));
            if (!naverFuture.isDone()) {
                List<String> late = awaitSoft(naverFuture, joinMs, Collections.emptyList(), "Naver.lateJoinDeficit");
                if (Thread.currentThread().isInterrupted()) {
                    return mergeAndLimit(brave, naver, topK);
                }
                if (late != null && !late.isEmpty()) {
                    naver = late;
                    try {
                        TraceStore.put("web.naver.opportunisticJoin.deficit.used", true);
                        TraceStore.put("web.naver.opportunisticJoin.deficit.ms", joinMs);
                    } catch (Exception ignore) {
                        traceSuppressed("korean.naverOpportunisticJoin.trace", ignore);
                    }
                }
            }

            // If still empty, attempt cache-only rescue (if enabled).
            if ((naver == null || naver.isEmpty()) && naverCacheOnlyEscape) {
                List<String> cacheOnly = naverCacheOnlySnippets(query, topK, "timeout_rescue");
                if (cacheOnly != null && !cacheOnly.isEmpty()) {
                    naver = cacheOnly;
                    try {
                        TraceStore.put("web.naver.cacheOnly.timeoutRescue.used", true);
                    } catch (Exception ignore) {
                        traceSuppressed("korean.naverCacheOnlyTimeoutRescue.trace", ignore);
                    }
                }
            }
        }

        List<String> merged = mergeAndLimit(brave, naver, topK);
        Map<String, Object> mergeMeta = new LinkedHashMap<>();
        mergeMeta.put("skipBrave", skipBrave);
        mergeMeta.put("skipNaver", skipNaver);
        mergeMeta.put("naverSkippedByHedge", naverSkippedByHedge);
        mergeMeta.put("braveEnough", braveEnough);
        mergeMeta.put("braveEarlyEnoughToSkipNaver", braveEarlyEnoughToSkipNaver);
        mergeMeta.put("calledBrave", braveLiveCall);
        mergeMeta.put("braveCacheOnly", braveCacheOnly);
        if (braveSkipReason != null)
            mergeMeta.put("braveSkipReason", braveSkipReason);
        mergeMeta.put("calledNaver", naverLiveCall);
        mergeMeta.put("naverCacheOnly", naverCacheOnly);
        if (naverSkipReason != null)
            mergeMeta.put("naverSkipReason", naverSkipReason);
        mergeMeta.put("hedgeDelayMs", koreanHedgeDelayMs);
        mergeMeta.put("skipNaverIfBraveMinResults", skipNaverIfBraveMinResults);
        mergeMeta.put("skipNaverIfBraveSufficient", skipNaverIfBraveSufficient);
        mergeMeta.put("forceOpportunisticNaverEvenIfBraveFast", forceOpportunisticNaverEvenIfBraveFast);
        mergeMeta.put("naverOpportunisticMs", naverOpportunisticMs);
        emitMergeBoundaryEvent("korean.brave_then_naver", query, topK, brave, naver, merged, mergeMeta, null);

        log.info("[Hybrid] Korean search merged: brave={}, naver={}, merged={}",
                brave.size(), naver.size(), merged.size());

        recordSoakWebMetrics(naverLiveCall && !naverSkippedByHedge, merged, naver);

        return merged;
    }

    private List<String> searchKoreanNaverAndBrave(String query, int topK) {
        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = requestDeadline(timeoutMs);

        // Official-only mode prefers to keep OFFICIAL/DOCS diversity; do not hedge-skip
        // Brave
        // when Naver is fast, because it can shrink the citeable pool and amplify
        // starvation.
        boolean officialOnly = false;
        try {
            GuardContext ctx = GuardContextHolder.get();
            officialOnly = (ctx != null && ctx.isOfficialOnly());
        } catch (Throwable ignore) {
            traceSuppressed("korean.naverFirst.officialOnlyContext", ignore);
        }

        // Breaker: skip engines when OPEN
        boolean skipBrave = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        boolean skipNaver = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));

        // --- Naver-first hedged strategy ---
        Future<List<String>> naverFuture = null;
        boolean naverLiveCall = false;
        boolean naverCacheOnly = false;
        boolean naverSkipRecorded = false;

        String naverSkipReason = null;
        long naverSkipExtraMs = 0L;

        if (naverService == null || !naverService.isEnabled()) {
            naverSkipReason = "disabled";
        } else if (skipNaver) {
            naverSkipReason = "breaker_open";
            try {
                naverSkipExtraMs = nightmareBreaker == null ? 0L
                        : nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER);
            } catch (Throwable ignore) {
                traceSuppressed("korean.naverFirst.remainingOpenMs", ignore);
            }
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver call");
        } else {
            long naverReserveMs = 0L;
            try {
                boolean braveEligible = braveService != null
                        && braveService.isEnabled()
                        && !skipBrave
                        && !braveService.isCoolingDown();
                naverReserveMs = braveEligible ? braveMinReserveMs(officialOnly) : 0L;
            } catch (Throwable ignore) {
                traceSuppressed("korean.naverFirst.reserveMs", ignore);
                naverReserveMs = 0L;
            }

            final java.time.Duration naverBlockTimeout = java.time.Duration.ofMillis(
                    resolveNaverBlockTimeoutMs(deadlineNs, naverReserveMs, "korean.naver-first"));
            try {
                naverFuture = submitSearchAttempt(() -> {
                    try {
                        return naverService.searchSnippetsSync(query, topK, naverBlockTimeout);
                    } catch (Exception e) {
                        traceSuppressed("korean.naverFirst.naverSearchFailure", e);
                        log.warn("[Hybrid] Naver korean search failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                        return Collections.emptyList();
                    }
                });
                naverLiveCall = true;
            } catch (Exception submitEx) {
                naverSkipReason = "submit_failed";
                recordNaverSkipped(naverSkipReason, "korean.naverFirst", 0L, submitEx);
                naverSkipRecorded = true;
                log.warn("[Hybrid] Naver scheduling failed, skipping Naver call errorHash={} errorLength={}", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());
            }
        }

        if (!naverLiveCall && naverSkipReason != null && !naverSkipRecorded
                && !"submit_failed".equals(naverSkipReason)) {
            recordNaverSkipped(naverSkipReason, "korean.naverFirst", naverSkipExtraMs);
            naverSkipRecorded = true;
        }

        // Even when Naver is intentionally skipped, provide a cache-only Future so
        // await() does not
        // record missing_future (which should be reserved for wiring bug detection).
        if (naverFuture == null && naverSkipReason != null) {
            naverCacheOnly = true;
            naverFuture = java.util.concurrent.CompletableFuture.completedFuture(
                    naverCacheOnlySnippets(query, topK, naverSkipReason));
        }

        List<String> naverEarly = null;
        boolean naverEarlyEnoughToSkipBrave = false;
        if (naverLiveCall
                && naverFuture != null
                && !skipBrave
                && braveService != null
                && braveService.isEnabled()
                && koreanHedgeDelayMs > 0) {
            long waitMs = Math.min(koreanHedgeDelayMs, remainingMs(deadlineNs));
            if (waitMs > 0) {
                try {
                    naverEarly = naverFuture.get(waitMs, TimeUnit.MILLISECONDS);
                    int naverSz = (naverEarly == null) ? 0 : naverEarly.size();
                    naverEarlyEnoughToSkipBrave = naverSz >= Math.max(1, skipBraveIfNaverMinResults);
                } catch (TimeoutException ignore) {
                    traceSuppressed("korean.naverFirst.earlyTimeout", ignore);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    traceSuppressed("korean.naverFirst.earlyInterrupted", ie);
                    return Collections.emptyList();
                } catch (Exception ignore) {
                    traceSuppressed("korean.naverFirst.earlyFailure", ignore);
                }
            }
        }

        Future<BraveSearchResult> braveFuture = null;
        final int braveK = Math.min(Math.max(topK, 5), 20);
        boolean braveLiveCall = false;
        boolean braveCacheOnly = false;
        boolean braveSkipRecorded = false;

        String braveSkipReason = null;
        long braveSkipExtraMs = 0L;

        if (braveService == null || !braveService.isEnabled()) {
            braveSkipReason = "disabled";
            // skip
        } else if (skipBrave) {
            braveSkipReason = "breaker_open";
            try {
                braveSkipExtraMs = nightmareBreaker == null ? 0L
                        : nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_BRAVE);
            } catch (Throwable ignore) {
                traceSuppressed("korean.naverFirst.braveRemainingOpenMs", ignore);
            }
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave call");
        } else if (braveService.isCoolingDown()) {
            braveSkipReason = "cooldown";
            braveSkipExtraMs = braveService.cooldownRemainingMs();
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave call", braveSkipExtraMs);
        } else if (naverEarlyEnoughToSkipBrave && !officialOnly) {
            braveSkipReason = "hedge_skip";
            if (log.isDebugEnabled()) {
                int naverSz = (naverEarly == null) ? 0 : naverEarly.size();
                log.debug("[Hybrid] Korean hedged: skipping Brave start (Naver {} results within {}ms)",
                        naverSz, koreanHedgeDelayMs);
            }
        } else {
            if (naverEarlyEnoughToSkipBrave && officialOnly) {
                try {
                    TraceStore.put("web.brave.hedgeSkip.bypassed", true);
                    TraceStore.put("web.brave.hedgeSkip.bypassed.reason", "officialOnly");
                } catch (Exception ignore) {
                    traceSuppressed("korean.braveHedgeSkipBypass.trace", ignore);
                }
                if (log.isDebugEnabled()) {
                    int naverSz = (naverEarly == null) ? 0 : naverEarly.size();
                    log.debug(
                            "[Hybrid] Korean hedged: bypassing Brave hedge_skip (officialOnly, Naver {} results within {}ms)",
                            naverSz, koreanHedgeDelayMs);
                }
            }
            try {
                braveFuture = submitSearchAttempt(() -> braveService.searchWithMeta(braveQuery, braveK));
                braveLiveCall = true;
            } catch (Exception submitEx) {
                braveSkipReason = "submit_failed";
                recordBraveSkipped(braveSkipReason, "korean.naverFirst", 0L, submitEx);
                braveSkipRecorded = true;
                log.warn("[Hybrid] Brave scheduling failed, skipping Brave call errorHash={} errorLength={}", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());
            }
        }

        if (!braveLiveCall && braveSkipReason != null && !braveSkipRecorded
                && !"submit_failed".equals(braveSkipReason)) {
            recordBraveSkipped(braveSkipReason, "korean.naverFirst", braveSkipExtraMs);
            braveSkipRecorded = true;
        }

        // Even when Brave is intentionally skipped, provide a cache-only Future so
        // await() does not
        // record missing_future (inflates web.await.skipped.Brave.count).
        if (braveFuture == null && braveSkipReason != null) {
            braveCacheOnly = true;
            braveFuture = java.util.concurrent.CompletableFuture.completedFuture(
                    braveCacheOnlyMeta(braveQuery, braveK, braveSkipReason));
        }

        if (!naverLiveCall && !braveLiveCall) {
            recordWebHardDown("both_skipped", "korean.naverFirst");
        }

        List<String> naver = (naverEarly != null)
                ? naverEarly
                : awaitWithDeadline(naverFuture, deadlineNs, Collections.emptyList(), "Naver");
        if (Thread.currentThread().isInterrupted()) {
            return naver == null ? Collections.emptyList() : naver;
        }

        boolean naverEnough = skipNaverIfBraveSufficient && naver != null && naver.size() >= topK;

        // In officialOnly mode we need source diversity; do not soft-wait Brave (unless
        // explicitly capped).
        boolean braveSoftJoin = naverEnough && !officialOnly;

        long braveJoinDeadlineNs = deadlineNs;
        String braveJoinMode = braveSoftJoin ? "opportunistic" : "deadline";

        long braveFullJoinCapMs = Math.max(0L, officialOnlyBraveFullJoinMaxWaitMs);
        if (!braveSoftJoin && naverEnough && officialOnly && braveFullJoinCapMs > 0L) {
            long capDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(braveFullJoinCapMs);
            long capped = Math.min(deadlineNs, capDeadlineNs);
            if (capped < deadlineNs) {
                braveJoinDeadlineNs = capped;
                braveJoinMode = "deadline_capped";
            }
        }
        try {
            TraceStore.put("web.hybrid.braveJoin.mode", braveJoinMode);
            TraceStore.put("web.hybrid.braveJoin.soft", braveSoftJoin);
            TraceStore.put("web.hybrid.braveJoin.softMs", braveSoftJoin ? naverOpportunisticMs : 0L);
            TraceStore.put("web.hybrid.braveJoin.deadlineCapMs", braveFullJoinCapMs);
            if (naverEnough && officialOnly) {
                TraceStore.put("web.hybrid.braveJoin.policy",
                        braveJoinMode.equals("deadline_capped") ? "deadline_capped_officialOnly"
                                : "deadline_officialOnly");
            } else if (naverEnough) {
                TraceStore.put("web.hybrid.braveJoin.policy", "soft_opportunistic");
            } else {
                TraceStore.put("web.hybrid.braveJoin.policy", "deadline_default");
            }
        } catch (Throwable suppressed) { traceSuppressed("braveJoin.policy.direct", suppressed); }

        boolean braveCapIsSoft = !braveSoftJoin && "deadline_capped".equals(braveJoinMode);
        long braveCappedSoftMs = braveCapIsSoft ? Math.max(0L, remainingMs(braveJoinDeadlineNs)) : 0L;

        BraveSearchResult braveMeta = (braveSoftJoin || braveCapIsSoft)
                ? awaitSoft(braveFuture,
                        braveSoftJoin ? naverOpportunisticMs : braveCappedSoftMs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave")
                : awaitWithDeadline(braveFuture, braveJoinDeadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L), "Brave");
        if (Thread.currentThread().isInterrupted()) {
            List<String> braveInterrupted = braveMeta == null || braveMeta.snippets() == null
                    ? Collections.emptyList()
                    : braveMeta.snippets();
            return mergeAndLimit(naver, braveInterrupted, topK);
        }

        List<String> brave = (braveMeta == null) ? Collections.emptyList() : braveMeta.snippets();

        if (braveMeta != null && braveMeta.status() != BraveSearchResult.Status.OK) {
            log.info("[Hybrid] Brave meta: status={} httpStatus={} cooldownMs={} msg={} elapsedMs={}",
                    braveMeta.status(), braveMeta.httpStatus(), braveMeta.cooldownMs(), braveMeta.message(),
                    braveMeta.elapsedMs());
        }

        // Basic parsing sanity check (debug aid)
        if (brave != null && brave.size() == 1) {
            String only = brave.get(0);
            if (only != null) {
                String trimmed = only.trim();
                if (trimmed.startsWith("{") && trimmed.contains("\"web\"") && trimmed.contains("\"results\"")) {
                    log.warn("[Hybrid] Brave returned single JSON-like snippet ({} chars). " +
                            "BraveSearchService may not be parsing JSON properly.",
                            trimmed.length());
                }
            }
        }

        // Null-safe (downstream uses size() in logs)
        if (brave == null)
            brave = Collections.emptyList();
        if (naver == null)
            naver = Collections.emptyList();

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if ((naver == null || naver.isEmpty()) && (brave == null || brave.isEmpty())
                && naverFuture != null && !naverFuture.isDone()) {
            try {
                naver = naverFuture.get(capRequestWait(200L), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignore) {
                traceSuppressed("korean.naverFirst.lateJoin.timeout", ignore);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                traceSuppressed("korean.naverFirst.lateJoin.interrupted", ie);
                return mergeAndLimit(naver, brave, topK);
            } catch (Exception ignore) {
                traceSuppressed("korean.naverFirst.lateJoin.failure", ignore);
            }
            if (naver == null)
                naver = Collections.emptyList();
        }

        List<String> merged = mergeAndLimit(naver, brave, topK);
        Map<String, Object> mergeMeta = new LinkedHashMap<>();
        mergeMeta.put("skipBrave", skipBrave);
        mergeMeta.put("skipNaver", skipNaver);
        mergeMeta.put("naverEarlyEnoughToSkipBrave", naverEarlyEnoughToSkipBrave);
        mergeMeta.put("naverEnough", naverEnough);
        mergeMeta.put("calledBrave", braveLiveCall);
        mergeMeta.put("braveCacheOnly", braveCacheOnly);
        if (braveSkipReason != null)
            mergeMeta.put("braveSkipReason", braveSkipReason);
        mergeMeta.put("calledNaver", naverLiveCall);
        mergeMeta.put("naverCacheOnly", naverCacheOnly);
        if (naverSkipReason != null)
            mergeMeta.put("naverSkipReason", naverSkipReason);
        mergeMeta.put("hedgeDelayMs", koreanHedgeDelayMs);
        mergeMeta.put("skipBraveIfNaverMinResults", skipBraveIfNaverMinResults);
        mergeMeta.put("skipNaverIfBraveSufficient", skipNaverIfBraveSufficient);
        mergeMeta.put("naverOpportunisticMs", naverOpportunisticMs);
        emitMergeBoundaryEvent("korean.naver_then_brave", query, topK, brave, naver, merged, mergeMeta, null);

        log.info("[Hybrid] Korean search merged: brave={}, naver={}, merged={}",
                brave.size(), naver.size(), merged.size());

        recordSoakWebMetrics(naverLiveCall, merged, naver);
        return merged;
    }

    private void emitMergeBoundaryEvent(
            String mergeKind,
            String query,
            int topK,
            List<String> brave,
            List<String> naver,
            List<String> merged,
            Map<String, Object> extra,
            Throwable error) {

        try {
            // Never leak raw query; only emit a stable hash for correlation.
            String qKey = normalizeQueryKey(query);

            List<String> braveSafe = (brave == null) ? Collections.emptyList() : brave;
            List<String> naverSafe = (naver == null) ? Collections.emptyList() : naver;
            List<String> mergedSafe = (merged == null) ? Collections.emptyList() : merged;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mergeKind", mergeKind);
            data.put("topK", topK);
            data.put("primarySetting", primary);
            data.put("timeoutSec", timeoutSec);

            if (qKey != null) {
                data.put("qHash", OrchDigest.sha1Canonical(qKey));
                data.put("qLen", qKey.length());
            }

            data.put("brave.count", braveSafe.size());
            data.put("naver.count", naverSafe.size());
            data.put("merged.count", mergedSafe.size());
            data.putAll(TraceStore.searchCorrelation(TraceStore.context()));
            data.put("naver.retainedAfterDedup", countFromList(mergedSafe, naverSafe));

            // Digests (order-sensitive + order-insensitive) help detect merge ordering
            // issues.
            data.put("brave.sha1", OrchDigest.sha1Canonical(braveSafe));
            data.put("naver.sha1", OrchDigest.sha1Canonical(naverSafe));
            data.put("merged.sha1", OrchDigest.sha1Canonical(mergedSafe));
            data.put("merged.setSha1", OrchDigest.sha1Unordered(mergedSafe));

            if (extra != null && !extra.isEmpty()) {
                data.putAll(extra);
            }

            // (A) breadcrumb: TraceStore[orch.events.v1]
            // (B) debug JSON: DebugEventStore (dbgSearch only)
            OrchEventEmitter.breadcrumbAndDebug(
                    debugEventStore,
                    DebugProbeType.ORCHESTRATION,
                    (error == null) ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                    "orch.web.merge.boundary." + mergeKind,
                    "[nova][orch] Web merge boundary: " + mergeKind,
                    "HybridWebSearchProvider",
                    "web.merge",
                    mergeKind,
                    "boundary",
                    data,
                    error);

            boolean afterFilterStarvation = mergedSafe.isEmpty() && (!braveSafe.isEmpty() || !naverSafe.isEmpty());
            TraceStore.put("hybrid.web.outCount", mergedSafe.size());
            TraceStore.put("hybrid.web.starvation", afterFilterStarvation);
            TraceStore.put("outCount", mergedSafe.size());
            TraceStore.put("stageCountsSelectedFromOut", Map.of("brave", braveSafe.size(), "naver", naverSafe.size(), "merged", mergedSafe.size()));
            TraceStore.put("starvationFallback.poolSafeEmpty", mergedSafe.isEmpty());
            TraceStore.put("poolSafeEmpty", mergedSafe.isEmpty());
            TraceStore.put("web.fusion.selectedCount", mergedSafe.size());
            TraceStore.put("web.provider.resultCount", braveSafe.size() + naverSafe.size());
            if (qKey != null) {
                TraceStore.put("web.query.hash", SafeRedactor.hashValue(qKey));
                TraceStore.put("web.query.length", qKey.length());
            }
            if (afterFilterStarvation) {
                TraceStore.put("web.filter.starvationReason", "after-filter-starvation");
                TraceStore.put("web.failsoft.reason", "after-filter-starvation");
            } else {
                TraceStore.put("web.filter.starvationReason", null);
            }
            if (mergedSafe.isEmpty()) { TraceStore.put("starvationFallback.trigger", "all_providers_empty_or_down"); TraceStore.put("rescueMerge.used", false); } TraceStore.put("tracePool.size", TraceStore.getPoolItems().size());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("queryHash", qKey == null ? "" : SafeRedactor.hash12(qKey));
            input.put("queryLen", query == null ? 0 : query.length());
            input.put("requestedTopK", Math.max(0, topK));
            input.put("mode", "hybrid_web_merge");
            Map<String, Object> output = new LinkedHashMap<>();
            int returnedCount = braveSafe.size() + naverSafe.size();
            output.put("returnedCount", returnedCount);
            output.put("afterFilterCount", mergedSafe.size());
            output.put("selectedCount", mergedSafe.size());
            output.put("naverRetainedCount", countFromList(mergedSafe, naverSafe));
            Map<String, Object> failure = new LinkedHashMap<>();
            if (afterFilterStarvation) {
                failure.put("reasonCode", "after_filter_starvation");
                failure.put("failureClass", "after_filter_starvation");
                failure.put("exceptionType", "None");
            } else if (error != null) {
                failure.put("reasonCode", "web_merge_error");
                failure.put("failureClass", "web_merge_error");
                failure.put("exceptionType", error.getClass().getSimpleName());
            }
            Map<String, Object> control = new LinkedHashMap<>();
            if (afterFilterStarvation) {
                control.put("reasonCode", "after_filter_starvation");
                control.put("breadcrumbId", "orch.web.merge.boundary." + mergeKind);
            }
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    "fuse",
                    "web_merge",
                    "boundary",
                    "HybridWebSearchProvider",
                    error == null ? (afterFilterStarvation ? "blocked" : "ok") : "error",
                    input,
                    output,
                    failure,
                    control);

            // Invariant: if at least one engine had results, merged should not be empty.
            if (afterFilterStarvation) {
                Map<String, Object> inv = new LinkedHashMap<>();
                inv.put("mergeKind", mergeKind);
                inv.put("brave.count", braveSafe.size());
                inv.put("naver.count", naverSafe.size());
                inv.put("merged.count", 0);
                inv.put("reason", "non-empty inputs produced empty merged");
                OrchEventEmitter.breadcrumbAndDebug(
                        debugEventStore,
                        DebugProbeType.ORCHESTRATION,
                        DebugEventLevel.WARN,
                        "orch.web.merge.invariant." + mergeKind,
                        "[nova][orch] Web merge invariant violation",
                        "HybridWebSearchProvider",
                        "web.merge.invariant",
                        mergeKind,
                        "postMerge",
                        inv,
                        null);
            }
        } catch (Throwable ignore) {
            traceSuppressed("webMergeInvariant.emit", ignore);
        }
    }


    private void emitRemergeOnceEvent(
            String phase,
            String query,
            int topK,
            Map<String, Object> extra,
            Throwable error) {

        try {
            // Never leak raw query; only emit a stable hash for correlation.
            String qKey = normalizeQueryKey(query);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("phase", phase);
            data.put("topK", topK);

            if (qKey != null) {
                data.put("qHash", OrchDigest.sha1Canonical(qKey));
                data.put("qLen", qKey.length());
            }

            if (extra != null && !extra.isEmpty()) {
                data.putAll(extra);
            }

            OrchEventEmitter.breadcrumbAndDebug(
                    debugEventStore,
                    DebugProbeType.ORCHESTRATION,
                    (error == null) ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                    "orch.web.remergeOnce." + phase,
                    "[nova][orch] Web remergeOnce: " + phase,
                    "HybridWebSearchProvider",
                    "web.remergeOnce",
                    phase,
                    "cacheOnly",
                    data,
                    error);
        } catch (Throwable ignore) {
            traceSuppressed("remergeOnceEvent.emit", ignore);
        }
    }

    private static String normalizeQueryKey(String query) {
        if (query == null) {
            return null;
        }
        String s = query.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
        if (s.length() > 512) {
            s = s.substring(0, 512);
        }
        return s;
    }

    private void recordSoakWebMetrics(boolean calledNaver, List<String> merged, List<String> naver) {
        if (soakMetricRegistry == null)
            return;
        try {
            soakMetricRegistry.recordWebCall(calledNaver);

            int mergedTotal = (merged == null) ? 0 : merged.size();
            int fromNaver = countFromList(merged, naver);
            soakMetricRegistry.recordWebMerge(mergedTotal, fromNaver);
        } catch (Exception ignore) {
            traceSuppressed("soakWebMetrics.record", ignore);
        }
    }

    private static int countFromList(List<String> merged, List<String> from) {
        if (merged == null || merged.isEmpty() || from == null || from.isEmpty())
            return 0;

        java.util.HashSet<String> set = new java.util.HashSet<>();
        for (String s : from) {
            if (s != null && !s.isBlank())
                set.add(s);
        }

        int c = 0;
        for (String s : merged) {
            if (s != null && set.contains(s))
                c++;
        }
        return c;
    }

    /**
     * Merge primary/secondary snippet lists while keeping ordering stable.
     * To avoid over-aggressive truncation (e.g. only 1 snippet surviving),
     * we always keep at least 3 snippets when available.
     */
    private static List<String> mergeAndLimit(List<String> primary, List<String> secondary, int topK) {
        GuardContext ctx = GuardContextHolder.get();
        boolean officialOnly = ctx != null && ctx.isOfficialOnly();
        boolean restrictTrust = officialOnly || (ctx != null && ctx.isStrikeMode());
        List<String> merged = HybridSearchResultPolicy.mergeProviders(
                primary, secondary, topK, restrictTrust, officialOnly);
        if (merged.isEmpty()) {
            log.warn("[Hybrid] No merged results after filtering, returning empty list");
        }
        return merged;
    }

    public static boolean isLowTrustUrl(String lowerUrl) {
        return HybridSearchResultPolicy.isLowTrustUrl(lowerUrl);
    }

    @Override
    public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
        return withRequestBudget(() -> searchWithTraceWithinBudget(query, topK));
    }

    private NaverSearchService.SearchResult searchWithTraceWithinBudget(String query, int topK) {

        if (Thread.currentThread().isInterrupted()) {
            try {
                TraceStore.inc("web.interruptHygiene.preserved.traceEntry.count");
                TraceStore.put("web.interruptHygiene.preserved.traceEntry.where",
                        "HybridWebSearchProvider.searchWithTrace");
                TraceStore.put("interrupt.preserved", true);
            } catch (Exception suppressed) {
                traceSuppressed("traceEntry.interruptPreserved", suppressed);
            }
            return new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        if (boundedFallbackEnabled || Boolean.TRUE.equals(TraceStore.get("conversate.web.singleCycle"))) {
            long startedNanos = System.nanoTime();
            List<String> snippets = search(query, topK);
            NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
            trace.query = "[redacted]";
            trace.queryHash = SafeRedactor.hashValue(query);
            trace.queryLength = query == null ? 0 : query.length();
            trace.provider = getName() + ":BOUNDED";
            trace.totalMs = Math.max(0L,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            if (Boolean.TRUE.equals(TraceStore.get("web.boundedRoute.completed"))) {
                String terminalReason = SafeRedactor.traceLabelOrFallback(
                        TraceStore.get("web.boundedRoute.terminalReason"), "unknown");
                trace.steps.add(new NaverSearchService.SearchStep(
                        "BOUNDED:" + terminalReason, snippets.size(), snippets.size(), trace.totalMs));
            }
            return new NaverSearchService.SearchResult(snippets, trace);
        }

        var gctx = GuardContextHolder.get();
        boolean sensitive = gctx != null && gctx.isSensitiveTopic();
        boolean planBlockAll = gctx != null && gctx.planBool("privacy.boundary.block-web-search", false);
        boolean planBlockOnSensitive = gctx != null
                && gctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
        if (blockWebSearch || planBlockAll || (sensitive && (blockWebSearchOnSensitive || planBlockOnSensitive))) {
            try {
                com.example.lms.search.TraceStore.put("privacy.web.blocked", true);
            } catch (Exception suppressed) { traceSuppressed("privacy.webBlocked.trace", suppressed); }
            return new NaverSearchService.SearchResult(java.util.Collections.emptyList(), null);
        }

        // ?????????????????????????????????????????????????????????????
        // [DEBUG HARDENING]
        // - UI(TraceHtmlBuilder)??Summary ?쇱씤?
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        // "Search Trace - query: - provider: - ... - 0ms" 泥섎읆 蹂댁씠??臾몄젣媛 ?앷릿??
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        // ?????????????????????????????????????????????????????????????
        final long t0Ns = System.nanoTime();

        boolean isKorean = containsHangul(query);
        boolean bravePrimary = isBravePrimary();

        NaverSearchService.SearchResult r;
        if (!isKorean) {
            r = bravePrimary ? searchWithTraceBraveFirst(query, topK) : searchWithTraceNaverFirst(query, topK);
        } else {
            // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
            r = searchWithTraceKoreanSmartMerge(query, topK);
        }

        if (Thread.currentThread().isInterrupted()) {
            return r != null
                    ? r
                    : new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0Ns);

        List<String> snippets = (r != null && r.snippets() != null) ? r.snippets() : Collections.emptyList();
        NaverSearchService.SearchTrace trace = (r != null) ? r.trace() : null;

        // Always return a non-null trace so the UI/console can explain what happened.
        if (trace == null) {
            trace = new NaverSearchService.SearchTrace();
        }

        // Ensure query/provider are visible in the trace summary.
        if (trace.query == null || trace.query.isBlank()) {
            trace.query = query;
        }
        if (trace.provider == null || trace.provider.isBlank()) {
            String p = getName();
            p += isKorean ? ":KR" : ":EN";
            p += bravePrimary ? ":BRAVE_PRIMARY" : ":NAVER_PRIMARY";
            trace.provider = p;
        }

        // Record end-to-end elapsed time. Preserve larger value if a sub-trace already
        // set totalMs.
        trace.totalMs = Math.max(trace.totalMs, totalMs);

        return new NaverSearchService.SearchResult(snippets, trace);
    }

    private NaverSearchService.SearchResult searchWithTraceKoreanSmartMerge(String query, int topK) {
        boolean bravePrimary = isBravePrimary();
        NaverSearchService.SearchResult primary = bravePrimary
                ? searchWithTraceKoreanBraveAndNaver(query, topK)
                : searchWithTraceKoreanNaverAndBrave(query, topK);
        if (Thread.currentThread().isInterrupted()) {
            return primary != null
                    ? primary
                    : new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }
        if (!soakEnabled || primary == null || primary.snippets() == null || primary.snippets().size() >= 3) {
            return primary;
        }

        String extracted = extractKeywords(query);
        if (!StringUtils.hasText(extracted) || extracted.equals(query)) {
            return primary;
        }

        log.info("[Hybrid] Soak(trace) keyword retry queryHash={} queryLength={} extractedHash={} extractedLength={}",
                SafeRedactor.hash12(query),
                query == null ? 0 : query.length(),
                SafeRedactor.hash12(extracted),
                extracted == null ? 0 : extracted.length());

        NaverSearchService.SearchResult keywordResult = bravePrimary
                ? searchWithTraceKoreanBraveAndNaver(extracted, topK)
                : searchWithTraceKoreanNaverAndBrave(extracted, topK);
        if (keywordResult == null || keywordResult.snippets() == null || keywordResult.snippets().isEmpty()) {
            return primary;
        }

        java.util.List<String> mergedSnippets = mergeAndLimit(
                primary.snippets() != null ? primary.snippets() : java.util.Collections.emptyList(),
                keywordResult.snippets(),
                topK);

        NaverSearchService.SearchTrace mergedTrace = primary.trace();
        if (mergedTrace == null) {
            mergedTrace = new NaverSearchService.SearchTrace();
        }
        if (keywordResult.trace() != null && keywordResult.trace().steps != null) {
            mergedTrace.steps.add(new NaverSearchService.SearchStep(
                    "Soak keyword retry",
                    keywordResult.snippets().size(),
                    mergedSnippets.size(),
                    0));
        }

        return new NaverSearchService.SearchResult(mergedSnippets, mergedTrace);
    }

    private NaverSearchService.SearchResult searchWithTraceKoreanBraveAndNaver(String query, int topK) {

        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = requestDeadline(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        boolean skipNaver = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));

        // --- Brave-first hedged strategy: start Brave first; start Naver only if
        // needed ---
        Future<BraveSearchResult> braveFuture = null;
        boolean braveLiveCall = false;
        if (braveService == null || !braveService.isEnabled()) {
            // skip
        } else if (skipBrave) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave trace call");
        } else if (braveService.isCoolingDown()) {
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave trace call",
                    braveService.cooldownRemainingMs());
        } else {
            braveFuture = submitSearchAttempt(() -> {
                int braveK = Math.min(Math.max(topK, 5), 20);
                return braveService.searchWithMeta(braveQuery, braveK);
            });
            braveLiveCall = true;
        }

        BraveSearchResult braveMetaEarly = null;
        boolean braveEarlyEnoughToSkipNaver = false;
        if (braveLiveCall
                && braveFuture != null
                && !skipNaver
                && naverService != null
                && naverService.isEnabled()
                && koreanHedgeDelayMs > 0) {
            long waitMs = Math.min(koreanHedgeDelayMs, remainingMs(deadlineNs));
            if (waitMs > 0) {
                try {
                    braveMetaEarly = braveFuture.get(waitMs, TimeUnit.MILLISECONDS);
                    int braveSz = (braveMetaEarly == null || braveMetaEarly.snippets() == null)
                            ? 0
                            : braveMetaEarly.snippets().size();
                    braveEarlyEnoughToSkipNaver = braveMetaEarly != null
                            && braveMetaEarly.status() == BraveSearchResult.Status.OK
                            && braveSz >= Math.max(1, skipNaverIfBraveMinResults);
                } catch (TimeoutException ignore) {
                    traceSuppressed("trace.braveFirst.earlyTimeout", ignore);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    traceSuppressed("trace.braveFirst.earlyInterrupted", ie);
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                } catch (Exception ignore) {
                    traceSuppressed("trace.braveFirst.earlyFailure", ignore);
                }
            }
        }

        Future<NaverSearchService.SearchResult> naverFuture = null;
        boolean naverSkippedByHedge = false;
        if (skipNaver) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver trace call");
        } else if (naverService == null || !naverService.isEnabled()) {
            // skip
        } else if (!braveEarlyEnoughToSkipNaver || forceOpportunisticNaverEvenIfBraveFast) {

            final int callK = (braveEarlyEnoughToSkipNaver && forceOpportunisticNaverEvenIfBraveFast)
                    ? Math.min(Math.max(1, topK), 3)
                    : topK;

            if (braveEarlyEnoughToSkipNaver && forceOpportunisticNaverEvenIfBraveFast && log.isDebugEnabled()) {
                int braveSz = (braveMetaEarly == null || braveMetaEarly.snippets() == null)
                        ? 0
                        : braveMetaEarly.snippets().size();
                log.debug(
                        "[Hybrid] Korean-trace hedged: Brave {} results within {}ms, still calling Naver opportunistically (k={})",
                        braveSz, koreanHedgeDelayMs, callK);
            }

            final java.time.Duration naverBlockTimeout = java.time.Duration.ofMillis(
                    resolveNaverBlockTimeoutMs(deadlineNs, 0L, "korean-trace.brave-first"));

            naverFuture = submitSearchAttempt(() -> {
                try {
                    NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, callK,
                            naverBlockTimeout);
                    return (result != null) ? result
                            : new NaverSearchService.SearchResult(Collections.emptyList(), null);
                } catch (Exception e) {
                    // Trace is quality-aiding. Treat failures as debug noise.
                    log.debug("[Hybrid] Naver korean-trace search failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
            });
        } else {
            naverSkippedByHedge = true;
            recordNaverSkipped("brave_sufficient", "korean.braveFirst.trace.hedge", 0L);
        }

        BraveSearchResult braveMeta = (braveMetaEarly != null)
                ? braveMetaEarly
                : awaitWithDeadline(
                        braveFuture,
                        deadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave-Trace");
        if (Thread.currentThread().isInterrupted()) {
            return new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        List<String> brave = (braveMeta == null) ? Collections.emptyList() : braveMeta.snippets();
        boolean braveEnough = skipNaverIfBraveSufficient && brave != null && brave.size() >= topK;

        NaverSearchService.SearchResult naver;
        if (naverFuture == null) {
            if (naverSkippedByHedge) {
                NaverSearchService.SearchTrace t = new NaverSearchService.SearchTrace();
                t.steps.add(new NaverSearchService.SearchStep("NAVER:SKIPPED(brave_sufficient)", 0, 0, 0));
                naver = new NaverSearchService.SearchResult(Collections.emptyList(), t);
            } else {
                naver = new NaverSearchService.SearchResult(Collections.emptyList(), null);
            }
        } else {
            naver = braveEnough
                    ? awaitSoft(
                            naverFuture,
                            naverOpportunisticMs,
                            new NaverSearchService.SearchResult(Collections.emptyList(), null),
                            "Naver-Trace")
                    : awaitWithDeadline(
                            naverFuture,
                            deadlineNs,
                            new NaverSearchService.SearchResult(Collections.emptyList(), null),
                            "Naver-Trace");
        }
        if (Thread.currentThread().isInterrupted()) {
            return new NaverSearchService.SearchResult(
                    mergeAndLimit(brave, Collections.emptyList(), topK),
                    null);
        }

        // Basic parsing sanity check (debug aid)
        if (brave != null && brave.size() == 1) {
            String only = brave.get(0);
            if (only != null) {
                String trimmed = only.trim();
                if (trimmed.startsWith("{")
                        && trimmed.contains("\"web\"")
                        && trimmed.contains("\"results\"")) {
                    log.warn("[Hybrid] Brave returned single JSON-like snippet ({} chars). " +
                            "BraveSearchService may not be parsing JSON properly.",
                            trimmed.length());
                }
            }
        }

        // Null-safe (downstream uses size() in logs/trace)
        if (brave == null) {
            brave = Collections.emptyList();
        }
        if (naver == null) {
            naver = new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        int braveCount = brave.size();
        int naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;

        if (braveCount == 0 && naverCount == 0) {
            log.warn("[Hybrid] Both engines returned 0, expanding Brave topK");
            try {
                if (braveService != null && braveService.isEnabled()) {
                    int expandedK = Math.min(topK * 2, 20); // Brave 理쒕? 20
                    // Retry budget is intentionally small. Fail-fast.
                    long retryBudgetMs = Math.min(timeoutMs, 1200L);
                    long retryDeadlineNs = requestDeadline(retryBudgetMs);

                    Future<BraveSearchResult> retry = submitSearchAttempt(
                            () -> braveService.searchWithMeta(braveQuery, expandedK));
                    BraveSearchResult retryMeta = awaitWithDeadline(
                            retry,
                            retryDeadlineNs,
                            BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Expanded");
                    if (Thread.currentThread().isInterrupted()) {
                        return new NaverSearchService.SearchResult(
                                mergeAndLimit(
                                        brave,
                                        naver.snippets() == null ? Collections.emptyList() : naver.snippets(),
                                        topK),
                                naver.trace());
                    }
                    if (retryMeta != null) {
                        braveMeta = retryMeta;
                    }
                    brave = (retryMeta == null) ? Collections.emptyList() : retryMeta.snippets();
                    braveCount = brave.size();
                    log.info("[Hybrid] Brave expanded search returned {} snippets", braveCount);
                }
            } catch (Exception ex) {
                log.warn("[Hybrid] Brave expanded search failed errorHash={} errorLength={}", SafeRedactor.hashValue(ex.getMessage()), ex.getMessage() == null ? 0 : ex.getMessage().length());
            }
        } else if (braveCount == 0 && naverCount > 0) {
            log.info("[Hybrid] Brave returned 0, already have Naver={}", naverCount);
        } else if (naverCount == 0 && braveCount > 0) {
            log.info("[Hybrid] Naver returned 0, already have Brave={}", braveCount);
        }

        List<String> merged = mergeAndLimit(
                brave,
                naver.snippets() != null ? naver.snippets() : Collections.emptyList(),
                topK);

        NaverSearchService.SearchTrace trace = naver.trace() != null
                ? naver.trace()
                : new NaverSearchService.SearchTrace();

        String braveStep = "Parallel: Brave Search (Korean)";
        long braveTookMs = 0L;
        if (braveMeta != null) {
            braveStep += " status=" + braveMeta.status();
            if (braveMeta.httpStatus() != null) {
                braveStep += " http=" + braveMeta.httpStatus();
            }
            if (braveMeta.cooldownMs() > 0) {
                braveStep += " cooldownMs=" + braveMeta.cooldownMs();
            }
            braveTookMs = braveMeta.elapsedMs();
        }
        trace.steps.add(new NaverSearchService.SearchStep(braveStep, brave.size(), merged.size(), braveTookMs));

        naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;
        log.info("[Hybrid] Korean parallel trace merged: brave={}, naver={}, merged={}",
                brave.size(), naverCount, merged.size());

        recordSoakWebMetrics(naverFuture != null && !naverSkippedByHedge, merged, naver.snippets());

        return new NaverSearchService.SearchResult(merged, trace);
    }

    private NaverSearchService.SearchResult searchWithTraceKoreanNaverAndBrave(String query, int topK) {

        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = requestDeadline(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        boolean skipNaver = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));

        // --- Naver-first hedged strategy ---
        Future<NaverSearchService.SearchResult> naverFuture = null;
        boolean naverLiveCall = false;
        boolean naverCacheOnly = false;
        boolean naverSkipRecorded = false;

        String naverSkipReason = null;
        long naverSkipExtraMs = 0L;

        if (naverService == null || !naverService.isEnabled()) {
            naverSkipReason = "disabled";
        } else if (skipNaver) {
            naverSkipReason = "breaker_open";
            try {
                naverSkipExtraMs = nightmareBreaker == null ? 0L
                        : nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER);
            } catch (Throwable ignore) {
                traceSuppressed("koreanTrace.naverFirst.remainingOpenMs", ignore);
            }
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver trace call");
        } else {
            final java.time.Duration naverBlockTimeout = java.time.Duration.ofMillis(
                    resolveNaverBlockTimeoutMs(deadlineNs, 0L, "korean-trace.naver-first"));
            try {
                naverFuture = submitSearchAttempt(() -> {
                    try {
                        NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK,
                                naverBlockTimeout);
                        return (result != null) ? result
                                : new NaverSearchService.SearchResult(Collections.emptyList(), null);
                    } catch (Exception e) {
                        traceSuppressed("koreanTrace.naverFirst.naverSearchFailure", e);
                        // Trace is quality-aiding. Treat failures as debug noise.
                        log.debug("[Hybrid] Naver korean-trace search failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                    }
                });
                naverLiveCall = true;
            } catch (Exception submitEx) {
                naverSkipReason = "submit_failed";
                recordNaverSkipped(naverSkipReason, "korean.naverFirst.trace", 0L, submitEx);
                naverSkipRecorded = true;
                log.warn("[Hybrid] Naver scheduling failed, skipping Naver trace call errorHash={} errorLength={}", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());
            }
        }

        if (!naverLiveCall && naverSkipReason != null && !naverSkipRecorded
                && !"submit_failed".equals(naverSkipReason)) {
            recordNaverSkipped(naverSkipReason, "korean.naverFirst.trace", naverSkipExtraMs);
            naverSkipRecorded = true;
        }

        // Even when Naver is intentionally skipped, provide a cache-only Future so
        // await() does not
        // record missing_future (which should be reserved for wiring bug detection).
        if (naverFuture == null && naverSkipReason != null) {
            naverCacheOnly = true;
            naverFuture = java.util.concurrent.CompletableFuture.completedFuture(
                    naverCacheOnlyTraceResult(query, topK, naverSkipReason));
        }

        NaverSearchService.SearchResult naverEarly = null;
        boolean naverEarlyEnoughToSkipBrave = false;
        if (naverLiveCall
                && naverFuture != null
                && !skipBrave
                && braveService != null
                && braveService.isEnabled()
                && koreanHedgeDelayMs > 0) {
            long waitMs = Math.min(koreanHedgeDelayMs, remainingMs(deadlineNs));
            if (waitMs > 0) {
                try {
                    naverEarly = naverFuture.get(waitMs, TimeUnit.MILLISECONDS);
                    int naverSz = (naverEarly == null || naverEarly.snippets() == null) ? 0
                            : naverEarly.snippets().size();
                    naverEarlyEnoughToSkipBrave = naverSz >= Math.max(1, skipBraveIfNaverMinResults);
                } catch (TimeoutException ignore) {
                    traceSuppressed("koreanTrace.naverFirst.earlyTimeout", ignore);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    traceSuppressed("koreanTrace.naverFirst.earlyInterrupted", ie);
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                } catch (Exception ignore) {
                    traceSuppressed("koreanTrace.naverFirst.earlyFailure", ignore);
                }
            }
        }

        Future<BraveSearchResult> braveFuture = null;
        boolean braveLiveCall = false;
        boolean braveSkippedByHedge = false;
        if (braveService == null || !braveService.isEnabled()) {
            // skip
        } else if (skipBrave) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave trace call");
        } else if (braveService.isCoolingDown()) {
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave trace call",
                    braveService.cooldownRemainingMs());
        } else if (!naverEarlyEnoughToSkipBrave) {
            braveFuture = submitSearchAttempt(() -> {
                int braveK = Math.min(Math.max(topK, 5), 20);
                return braveService.searchWithMeta(braveQuery, braveK);
            });
            braveLiveCall = true;
        } else {
            braveSkippedByHedge = true;
        }

        NaverSearchService.SearchResult naver = (naverEarly != null)
                ? naverEarly
                : awaitWithDeadline(
                        naverFuture,
                        deadlineNs,
                        new NaverSearchService.SearchResult(Collections.emptyList(), null),
                        "Naver-Trace");
        if (Thread.currentThread().isInterrupted()) {
            return naver != null
                    ? naver
                    : new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        int naverCount = (naver != null && naver.snippets() != null) ? naver.snippets().size() : 0;
        boolean naverEnough = skipNaverIfBraveSufficient && naverCount >= topK;

        boolean officialOnly = false;
        try {
            GuardContext gctx = GuardContextHolder.get();
            officialOnly = gctx != null && gctx.isOfficialOnly();
        } catch (Throwable suppressed) { traceSuppressed("officialOnly.context.trace", suppressed); }

        // In officialOnly mode we need source diversity; do not soft-wait Brave (unless
        // explicitly capped).
        boolean braveSoftJoin = naverEnough && !officialOnly;

        long braveJoinDeadlineNs = deadlineNs;
        String braveJoinMode = braveSoftJoin ? "opportunistic" : "deadline";

        long braveFullJoinCapMs = Math.max(0L, officialOnlyBraveFullJoinMaxWaitMs);
        if (!braveSoftJoin && naverEnough && officialOnly && braveFullJoinCapMs > 0L) {
            long capDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(braveFullJoinCapMs);
            long capped = Math.min(deadlineNs, capDeadlineNs);
            if (capped < deadlineNs) {
                braveJoinDeadlineNs = capped;
                braveJoinMode = "deadline_capped";
            }
        }
        try {
            TraceStore.put("web.hybrid.braveJoin.mode", braveJoinMode);
            TraceStore.put("web.hybrid.braveJoin.soft", braveSoftJoin);
            TraceStore.put("web.hybrid.braveJoin.softMs", braveSoftJoin ? naverOpportunisticMs : 0L);
            TraceStore.put("web.hybrid.braveJoin.deadlineCapMs", braveFullJoinCapMs);
            if (naverEnough && officialOnly) {
                TraceStore.put("web.hybrid.braveJoin.policy",
                        braveJoinMode.equals("deadline_capped") ? "deadline_capped_officialOnly"
                                : "deadline_officialOnly");
            } else if (naverEnough) {
                TraceStore.put("web.hybrid.braveJoin.policy", "soft_opportunistic");
            } else {
                TraceStore.put("web.hybrid.braveJoin.policy", "deadline_default");
            }
        } catch (Throwable suppressed) { traceSuppressed("braveJoin.policy.trace", suppressed); }

        BraveSearchResult braveMeta = null;
        if (!braveSkippedByHedge) {
            boolean braveCapIsSoft = !braveSoftJoin && "deadline_capped".equals(braveJoinMode);
            long braveCappedSoftMs = braveCapIsSoft ? Math.max(0L, remainingMs(braveJoinDeadlineNs)) : 0L;

            braveMeta = (braveSoftJoin || braveCapIsSoft)
                    ? awaitSoft(braveFuture,
                            braveSoftJoin ? naverOpportunisticMs : braveCappedSoftMs,
                            BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Trace")
                    : awaitWithDeadline(braveFuture, braveJoinDeadlineNs,
                            BraveSearchResult.ok(Collections.emptyList(), 0L), "Brave-Trace");
        }
        if (Thread.currentThread().isInterrupted()) {
            List<String> naverSnippets = naver == null || naver.snippets() == null
                    ? Collections.emptyList()
                    : naver.snippets();
            return new NaverSearchService.SearchResult(naverSnippets, naver == null ? null : naver.trace());
        }

        List<String> brave = (braveMeta == null) ? Collections.emptyList() : braveMeta.snippets();

        // Null-safe
        if (brave == null)
            brave = Collections.emptyList();
        if (naver == null)
            naver = new NaverSearchService.SearchResult(Collections.emptyList(), null);

        int braveCount = brave.size();
        naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (braveCount == 0 && naverCount == 0 && naverFuture != null && !naverFuture.isDone()) {
            try {
                NaverSearchService.SearchResult late = naverFuture.get(capRequestWait(200L), TimeUnit.MILLISECONDS);
                if (late != null) {
                    naver = late;
                    naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;
                }
            } catch (TimeoutException ignore) {
                traceSuppressed("koreanTrace.naverFirst.lateJoin.timeout", ignore);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                traceSuppressed("koreanTrace.naverFirst.lateJoin.interrupted", ie);
                return new NaverSearchService.SearchResult(
                        mergeAndLimit(
                                naver == null || naver.snippets() == null
                                        ? Collections.emptyList()
                                        : naver.snippets(),
                                brave,
                                topK),
                        naver == null ? null : naver.trace());
            } catch (Exception ignore) {
                traceSuppressed("koreanTrace.naverFirst.lateJoin.failure", ignore);
            }
        }

        if (braveCount == 0 && naverCount == 0) {
            log.warn("[Hybrid] Both engines returned 0, expanding Brave topK");
            try {
                if (braveService != null && braveService.isEnabled()) {
                    int expandedK = Math.min(topK * 2, 20);
                    long retryBudgetMs = Math.min(timeoutMs, 1200L);
                    long retryDeadlineNs = requestDeadline(retryBudgetMs);

                    Future<BraveSearchResult> retry = submitSearchAttempt(
                            () -> braveService.searchWithMeta(braveQuery, expandedK));
                    BraveSearchResult retryMeta = awaitWithDeadline(
                            retry,
                            retryDeadlineNs,
                            BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Expanded");
                    if (Thread.currentThread().isInterrupted()) {
                        return new NaverSearchService.SearchResult(
                                mergeAndLimit(
                                        naver.snippets() == null ? Collections.emptyList() : naver.snippets(),
                                        brave,
                                        topK),
                                naver.trace());
                    }
                    if (retryMeta != null) {
                        braveMeta = retryMeta;
                    }
                    brave = (retryMeta == null) ? Collections.emptyList() : retryMeta.snippets();
                    braveCount = brave.size();
                    log.info("[Hybrid] Brave expanded search returned {} snippets", braveCount);
                }
            } catch (Exception ex) {
                traceSuppressed("koreanTrace.braveExpandedSearch", ex);
                log.warn("[Hybrid] Brave expanded search failed errorHash={} errorLength={}", SafeRedactor.hashValue(ex.getMessage()), ex.getMessage() == null ? 0 : ex.getMessage().length());
            }
        } else if (braveCount == 0 && naverCount > 0) {
            log.info("[Hybrid] Brave returned 0, already have Naver={}", naverCount);
        } else if (naverCount == 0 && braveCount > 0) {
            log.info("[Hybrid] Naver returned 0, already have Brave={}", braveCount);
        }

        List<String> merged = mergeAndLimit(
                naver.snippets() != null ? naver.snippets() : Collections.emptyList(),
                brave,
                topK);

        NaverSearchService.SearchTrace trace = (naver.trace() != null)
                ? naver.trace()
                : new NaverSearchService.SearchTrace();

        // Add Brave step (or skip marker)
        String braveStep = braveSkippedByHedge
                ? "BRAVE:SKIPPED(naver_sufficient)"
                : "Parallel: Brave Search (Korean)";
        long braveTookMs = 0L;
        if (!braveSkippedByHedge && braveMeta != null) {
            braveStep += " status=" + braveMeta.status();
            if (braveMeta.httpStatus() != null) {
                braveStep += " http=" + braveMeta.httpStatus();
            }
            if (braveMeta.cooldownMs() > 0) {
                braveStep += " cooldownMs=" + braveMeta.cooldownMs();
            }
            braveTookMs = braveMeta.elapsedMs();
        }
        trace.steps.add(new NaverSearchService.SearchStep(braveStep, brave.size(), merged.size(), braveTookMs));

        naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;
        log.info("[Hybrid] Korean trace merged: brave={}, naver={}, merged={}",
                brave.size(), naverCount, merged.size());

        recordSoakWebMetrics(naverLiveCall, merged, naver.snippets());

        return new NaverSearchService.SearchResult(merged, trace);
    }

    private NaverSearchService.SearchResult searchWithTraceBraveFirst(String query, int topK) {
        if (!canStartSearchAttempt()) {
            return new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE))
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            traceSuppressed("trace.braveUsable.primary", ignore);
        }

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
                if (!canStartSearchAttempt()) {
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
                List<String> brave = braveService.search(query, topK);
                if (brave != null && !brave.isEmpty()) {
                    NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
                    trace.steps.add(new NaverSearchService.SearchStep(
                            "Primary: Brave Search",
                            brave.size(),
                            brave.size(),
                            0));
                    log.info("[Hybrid] Brave primary (trace) returned {} snippets", brave.size());
                    return new NaverSearchService.SearchResult(brave, trace);
                }
                log.info("[Hybrid] Brave primary (trace) returned no results. Falling back to Naver.");
            } catch (Exception e) {
                traceSuppressed("trace.bravePrimary.search", e);
                log.warn("[Hybrid] Brave primary (trace) failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "trace", 0L);
            log.warn("[Hybrid] Brave primary (trace) skipped (breaker/cooldown). Falling back to Naver.");
        }

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception ignore) {
            traceSuppressed("trace.naverUsable.fallback", ignore);
        }

        // 2. Fallback: Naver (Trace ?ы븿)
        if (naverUsable) {
            try {
                if (!canStartSearchAttempt()) {
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
                NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                traceSuppressed("trace.naverFallback.search", e);
                log.warn("[Hybrid] Naver trace-search failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "trace");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception suppressed) { traceSuppressed("naverSkipped.traceFallback", suppressed); }
        }

        // 3. All failed
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    private NaverSearchService.SearchResult searchWithTraceNaverFirst(String query, int topK) {
        if (!canStartSearchAttempt()) {
            return new NaverSearchService.SearchResult(Collections.emptyList(), null);
        }

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception ignore) {
            traceSuppressed("trace.naverUsable.primary", ignore);
        }

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (naverUsable) {
            try {
                if (!canStartSearchAttempt()) {
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
                NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                    log.info("[Hybrid] Naver primary (trace) returned {} snippets", result.snippets().size());
                    return result;
                }
                log.info("[Hybrid] Naver primary (trace) returned no results. Falling back to Brave.");
            } catch (Exception e) {
                traceSuppressed("trace.naverPrimary.search", e);
                log.warn("[Hybrid] Naver primary (trace) failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "trace");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception suppressed) { traceSuppressed("naverSkipped.tracePrimary", suppressed); }
            log.warn("[Hybrid] Naver primary (trace) skipped (breaker OPEN/HALF_OPEN). Falling back to Brave.");
        }

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE))
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            traceSuppressed("trace.braveUsable.fallback", ignore);
        }

        // 2. Fallback: Brave (trace synthesized)
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
                if (!canStartSearchAttempt()) {
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
                List<String> brave = braveService.search(query, topK);
                if (brave != null && !brave.isEmpty()) {
                    NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
                    trace.steps.add(new NaverSearchService.SearchStep(
                            "Fallback: Brave Search",
                            brave.size(),
                            brave.size(),
                            0));
                    log.info("[Hybrid] Brave fallback (trace) returned {} snippets", brave.size());
                    return new NaverSearchService.SearchResult(brave, trace);
                }
            } catch (Exception e) {
                traceSuppressed("trace.braveFallback.search", e);
                log.warn("[Hybrid] Brave fallback (trace) failed errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "trace", 0L);
            log.warn("[Hybrid] Brave fallback (trace) skipped (breaker/cooldown).");
        }

        // 3. All failed
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    private void logSkipOnce(String tag, String message) {
        try {
            String k = "hybrid." + tag;
            if (TraceStore.get(k) != null)
                return;
            TraceStore.put(k, true);
        } catch (Exception suppressed) { traceSuppressed("logSkipOnce.dedupe", suppressed); }
        log.info("[{}] {}{}", tag, message, LogCorrelation.suffix());
    }

    private List<String> maybeBackupOnce(String originalQuery, int topK, List<String> primary) {
        if (!canStartSearchAttempt()) {
            return primary == null ? Collections.emptyList() : primary;
        }
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }

        // Fail-soft: before generating a backup query (which may trigger more network
        // calls),
        // attempt a single cache-only remerge after a short delay to capture late
        // caches from
        // timed-out/canceled futures and reduce QPS.
        List<String> remerged = maybeRemergeOnceCacheOnly(originalQuery, topK);
        if (remerged != null && !remerged.isEmpty()) {
            return remerged;
        }

        if (isCheapSearchModeActive()) {
            try {
                TraceStore.put("websearch.backup.skipped", true);
                TraceStore.put("websearch.backup.skipReason", "cheap-search-mode");
            } catch (Exception suppressed) {
                traceSuppressed("backup.cheapSearchModeTrace", suppressed);
            }
            return primary == null ? Collections.emptyList() : primary;
        }

        try {
            if (TraceStore.get("websearch.backup.used") != null) {
                return primary == null ? Collections.emptyList() : primary;
            }
        } catch (Exception suppressed) { traceSuppressed("backup.usedRead", suppressed); }

        String backup = buildBackupQuery(originalQuery);
        if (backup == null)
            backup = "";
        backup = backup.trim();
        if (backup.isBlank() || backup.equalsIgnoreCase(originalQuery)) {
            return primary == null ? Collections.emptyList() : primary;
        }

        try {
            TraceStore.put("websearch.backup.used", true);
        } catch (Exception suppressed) { traceSuppressed("backup.usedWrite", suppressed); }

        boolean braveOpen = false;
        boolean naverOpen = false;
        try {
            braveOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        } catch (Exception suppressed) { traceSuppressed("backup.braveBreakerRead", suppressed); }
        try {
            naverOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception suppressed) { traceSuppressed("backup.naverBreakerRead", suppressed); }

        log.warn("[WEBSEARCH_BACKUP_QUERY] merged=0 -> backupHash={} backupLength={} originalQueryHash={} originalQueryLength={} braveOpen={} naverOpen={}{}",
                SafeRedactor.hashValue(backup), backup.length(),
                SafeRedactor.hashValue(originalQuery), originalQuery == null ? 0 : originalQuery.length(),
                braveOpen, naverOpen,
                LogCorrelation.suffix());

        try {
            if (containsHangul(backup)) {
                return searchKoreanSmartMerge(backup, topK);
            }
            if (isBravePrimary()) {
                return searchBraveFirst(backup, topK);
            }
            return searchNaverFirst(backup, topK);
        } catch (Exception e) {
            log.warn("[WEBSEARCH_BACKUP_QUERY] backup search failed errorHash={} errorLength={}{}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix());
            return Collections.emptyList();
        }
    }

    private List<String> maybeRemergeOnceCacheOnly(String originalQuery, int topK) {
        if (!remergeOnEmptyEnabled) {
            return Collections.emptyList();
        }
        if (originalQuery == null || originalQuery.isBlank()) {
            return Collections.emptyList();
        }
        if (isCheapSearchModeActive()) {
            try {
                TraceStore.put("web.failsoft.remergeOnce.skipped", true);
                TraceStore.put("web.failsoft.remergeOnce.skipReason", "cheap-search-mode");
            } catch (Exception suppressed) {
                HybridTraceSuppressions.trace("remergeOnce.cheapSearchModeTrace", suppressed);
            }
            return Collections.emptyList();
        }

        try {
            if (TraceStore.get("websearch.remergeOnce.used") != null) {
                return Collections.emptyList();
            }
        } catch (Exception suppressed) { HybridTraceSuppressions.trace("remergeOnce.usedRead", suppressed); }

        // Trigger only when we have signals that the empty merge is likely due to
        // transient issues
        // (timeouts / non-OK / skipped futures / rate-limit) rather than a genuinely
        // empty SERP.
        long timeoutCount = TraceStore.getLong("web.await.events.timeout.count");
        long nonOkCount = TraceStore.getLong("web.await.events.nonOk.count");
        long skippedCount = TraceStore.getLong("web.await.skipped.count");

        boolean orchRateLimited = truthy(TraceStore.get("orch.webRateLimited"))
                || truthy(TraceStore.get("orch.webRateLimited.anyDown"))
                || truthy(TraceStore.get("orch.webPartialDown"));

        if (timeoutCount <= 0 && nonOkCount <= 0 && skippedCount <= 0 && !orchRateLimited) {
            return Collections.emptyList();
        }

        // If cache-only escape is disabled for both providers, there's nothing to
        // remerge.
        if (!braveCacheOnlyEscape && !naverCacheOnlyEscape) {
            return Collections.emptyList();
        }

        int polls = Math.max(1, Math.min(6, remergeOnEmptyMaxPolls));
        long maxTotalWaitMs = Math.max(0L, remergeOnEmptyMaxTotalWaitMs);
        long delayMs = Math.max(0L, remergeOnEmptyInitialDelayMs);

        // P0 risk-guard: keep remerge wait independent from await floors, but warn if
        // configured too low to be effective.
        // (We do NOT clamp: operators may intentionally set this to ~0ms for ultra-low latency.)
        boolean waitTooLow = maxTotalWaitMs > 0 && maxTotalWaitMs < 150;
        boolean waitTooHigh = maxTotalWaitMs > 1200; // user-visible latency
        if (waitTooLow) {
            TraceStore.put("web.failsoft.remergeOnce.config.tooLow", true);
            TraceStore.put("web.failsoft.remergeOnce.config.tooLow.recommended", "250-350");
        }
        if (waitTooHigh) {
            TraceStore.put("web.failsoft.remergeOnce.config.tooHigh", true);
        }

        if (waitTooLow) {
            log.warn("[WEBSEARCH_REMERGE_ONCE] config maxTotalWaitMs={}ms is very low (recommended: 250~350ms). ridHash={} sessionHash={} ",
                    maxTotalWaitMs, SafeRedactor.hashValue(LogCorrelation.requestId()), SafeRedactor.hashValue(LogCorrelation.sessionId()));
        } else if (waitTooHigh) {
            log.warn("[WEBSEARCH_REMERGE_ONCE] config maxTotalWaitMs={}ms is high (user-visible latency). ridHash={} sessionHash={}",
                    maxTotalWaitMs, SafeRedactor.hashValue(LogCorrelation.requestId()), SafeRedactor.hashValue(LogCorrelation.sessionId()));
        }

        // Debug: record configured knobs per request (avoid accidental coupling with await floors).
        TraceStore.put("web.failsoft.remergeOnce.config.maxTotalWaitMs", maxTotalWaitMs);
        TraceStore.put("web.failsoft.remergeOnce.config.initialDelayMs", delayMs);
        TraceStore.put("web.failsoft.remergeOnce.config.maxPolls", polls);

        boolean braveOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        boolean naverOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));

        TraceStore.put("websearch.remergeOnce.used", true);
        TraceStore.put("web.failsoft.remergeOnce.used", true);
        TraceStore.put("web.failsoft.remergeOnce.mode", "cacheOnly");
        TraceStore.put("web.failsoft.remergeOnce.trigger",
                "timeout=" + timeoutCount
                        + ", nonOk=" + nonOkCount
                        + ", skipped=" + skippedCount
                        + ", orchRateLimited=" + orchRateLimited
                        + ", braveOpen=" + braveOpen
                        + ", naverOpen=" + naverOpen);

        try {
            Map<String, Object> startMeta = new LinkedHashMap<>();
            startMeta.put("mode", "cacheOnly");
            startMeta.put("maxTotalWaitMs", maxTotalWaitMs);
            startMeta.put("initialDelayMs", delayMs);
            startMeta.put("maxPolls", polls);
            startMeta.put("braveCacheOnlyEscape", braveCacheOnlyEscape);
            startMeta.put("naverCacheOnlyEscape", naverCacheOnlyEscape);
            startMeta.put("trigger.timeoutCount", timeoutCount);
            startMeta.put("trigger.nonOkCount", nonOkCount);
            startMeta.put("trigger.skippedCount", skippedCount);
            startMeta.put("trigger.orchRateLimited", orchRateLimited);
            startMeta.put("breaker.braveOpen", braveOpen);
            startMeta.put("breaker.naverOpen", naverOpen);
            startMeta.put("awaitMinLiveBudgetMs", awaitMinLiveBudgetMs);
            startMeta.put("config.waitTooLow", waitTooLow);
            startMeta.put("config.waitTooHigh", waitTooHigh);

            // Helpful to distinguish "cache genuinely empty" vs "wait too short".
            startMeta.put("await.minLiveBudget.used", truthy(TraceStore.get("web.await.minLiveBudget.used")));
            startMeta.put("await.minLiveBudget.cause", SafeRedactor.traceLabel(TraceStore.get("web.await.minLiveBudget.cause")));
            startMeta.put("await.cancelSuppressed.count", TraceStore.getLong("web.await.cancelSuppressed"));
            startMeta.put("await.minLiveBudget.lastRawTimeoutMs", TraceStore.getLong("web.await.minLiveBudget.lastRawTimeoutMs"));
            emitRemergeOnceEvent("start", originalQuery, topK, startMeta, null);
        } catch (Throwable ignore) {
            traceSuppressed("remergeOnce.startEvent", ignore);
        }

        long waitedMs = 0L;
        int usedPolls = 0;
        List<String> merged = Collections.emptyList();

        for (int i = 0; i < polls; i++) {
            if (!canStartSearchAttempt()) break;
            long sleptMs = 0L;
            long delayMsUsed = delayMs;

            if (delayMs > 0 && waitedMs < maxTotalWaitMs) {
                long sleepMs = capRequestWait(Math.min(delayMs, maxTotalWaitMs - waitedMs));
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        TraceStore.put("web.failsoft.remergeOnce.interrupted", true);
                        break;
                    }
                    sleptMs = sleepMs;
                    waitedMs += sleepMs;
                }
            }

            usedPolls = i + 1;

            // Cache-only remerge: pick up any late cache fills from timed-out/canceled
            // tasks.
            String braveQuery = convertToEnglishSearchTerm(originalQuery);
            BraveSearchResult braveCached = braveCacheOnlyMeta(braveQuery, topK, "remergeOnce");
            List<String> naverCached = naverCacheOnlySnippets(originalQuery, topK, "remergeOnce");

            int braveCount = (braveCached == null || braveCached.snippets() == null) ? 0 : braveCached.snippets().size();
            int naverCount = (naverCached == null) ? 0 : naverCached.size();

            merged = mergeAndLimit(
                    braveCached == null ? Collections.emptyList() : braveCached.snippets(),
                    naverCached == null ? Collections.emptyList() : naverCached,
                    topK);

            TraceStore.put("web.failsoft.remergeOnce.braveCached", braveCount);
            TraceStore.put("web.failsoft.remergeOnce.naverCached", naverCount);

            Map<String, Object> pollMeta = new LinkedHashMap<>();
            pollMeta.put("poll", usedPolls);
            pollMeta.put("sleptMs", sleptMs);
            pollMeta.put("waitedMs", waitedMs);
            pollMeta.put("delayMs", delayMsUsed);
            pollMeta.put("braveCached", braveCount);
            pollMeta.put("naverCached", naverCount);
            pollMeta.put("mergedCount", merged == null ? 0 : merged.size());
            pollMeta.put("hit", merged != null && !merged.isEmpty());
            TraceStore.append("web.failsoft.remergeOnce.events", pollMeta);

            if (remergeDebugEmitPollEvents) {
                try {
                    Map<String, Object> pollEvt = new LinkedHashMap<>(pollMeta);
                    pollEvt.put("maxTotalWaitMs", maxTotalWaitMs);
                    pollEvt.put("maxPolls", polls);
                    emitRemergeOnceEvent("poll." + usedPolls, originalQuery, topK, pollEvt, null);
                } catch (Throwable ignore) {
                    traceSuppressed("remergeOnce.pollEvent", ignore);
                }
            }

            if (merged != null && !merged.isEmpty()) {
                break;
            }

            if (waitedMs >= maxTotalWaitMs) {
                break;
            }

            // Exponential backoff between polls (bounded).
            delayMs = Math.min(delayMs == 0 ? 50 : delayMs * 2, 250);
        }

        TraceStore.put("web.failsoft.remergeOnce.waitMs", waitedMs);
        TraceStore.put("web.failsoft.remergeOnce.polls", usedPolls);
        TraceStore.put("web.failsoft.remergeOnce.outCount", merged == null ? 0 : merged.size());
        TraceStore.put("web.failsoft.remergeOnce.hit", merged != null && !merged.isEmpty());


        try {
            Map<String, Object> endMeta = new LinkedHashMap<>();
            endMeta.put("mode", "cacheOnly");
            endMeta.put("waitedMs", waitedMs);
            endMeta.put("usedPolls", usedPolls);
            endMeta.put("maxTotalWaitMs", maxTotalWaitMs);
            endMeta.put("hit", merged != null && !merged.isEmpty());
            endMeta.put("outCount", merged == null ? 0 : merged.size());
            endMeta.put("braveCached", TraceStore.getLong("web.failsoft.remergeOnce.braveCached"));
            endMeta.put("naverCached", TraceStore.getLong("web.failsoft.remergeOnce.naverCached"));
            endMeta.put("interrupted", truthy(TraceStore.get("web.failsoft.remergeOnce.interrupted")));

            // missReason: make "cache empty" vs "wait too short" visible at a glance.
            String missReason = null;
            if (truthy(TraceStore.get("web.failsoft.remergeOnce.interrupted"))) {
                missReason = "interrupted";
            } else if (maxTotalWaitMs <= 0L) {
                missReason = "maxTotalWaitMs=0";
            } else {
                long bc = TraceStore.getLong("web.failsoft.remergeOnce.braveCached");
                long nc = TraceStore.getLong("web.failsoft.remergeOnce.naverCached");
                if (bc + nc <= 0L) {
                    boolean floorUsed = truthy(TraceStore.get("web.await.minLiveBudget.used"));
                    if (floorUsed && maxTotalWaitMs > 0 && awaitMinLiveBudgetMs > 0 && maxTotalWaitMs < awaitMinLiveBudgetMs) {
                        missReason = "cache_empty_or_wait_too_short_vs_minLiveBudget";
                    } else {
                        missReason = "cache_empty";
                    }
                } else {
                    // Should be rare: cache hit but merge output is empty.
                    missReason = "cache_hit_but_merged_empty";
                }
            }

            if (missReason != null && (merged == null || merged.isEmpty())) {
                TraceStore.put("web.failsoft.remergeOnce.missReason", SafeRedactor.traceLabelOrFallback(missReason, "unknown"));
                endMeta.put("missReason", SafeRedactor.traceLabelOrFallback(missReason, "unknown"));
            }
            emitRemergeOnceEvent((merged != null && !merged.isEmpty()) ? "hit" : "miss", originalQuery, topK, endMeta, null);
        } catch (Throwable ignore) {
            traceSuppressed("remergeOnce.endEvent", ignore);
        }

        if (merged != null && !merged.isEmpty()) {
            log.warn(
                    "[WEBSEARCH_REMERGE_ONCE] merged=0 -> cache-only remerge ok outCount={} waitMs={} polls={} ridHash={} sessionHash={}",
                    merged.size(), waitedMs, usedPolls, SafeRedactor.hashValue(LogCorrelation.requestId()), SafeRedactor.hashValue(LogCorrelation.sessionId()));
            return merged;
        }

        log.info("[WEBSEARCH_REMERGE_ONCE] merged=0 -> cache-only remerge miss waitMs={} polls={} ridHash={} sessionHash={}",
                waitedMs, usedPolls, SafeRedactor.hashValue(LogCorrelation.requestId()), SafeRedactor.hashValue(LogCorrelation.sessionId()));
        return Collections.emptyList();
    }

    private static boolean truthy(Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean b)
            return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static boolean isCheapSearchModeActive() {
        try {
            GuardContext ctx = GuardContextHolder.get();
            if (ctx != null && ctx.isCheapSearchMode()) {
                return true;
            }
        } catch (Exception suppressed) {
            traceSuppressed("cheapSearchMode.contextRead", suppressed);
        }
        try {
            return truthy(TraceStore.get("search.mode.lightAuxBypass"));
        } catch (Exception suppressed) {
            traceSuppressed("cheapSearchMode.traceRead", suppressed);
            return false;
        }
    }

    private String buildBackupQuery(String originalQuery) {
        if (originalQuery == null)
            return "";
        String q = originalQuery.trim();
        if (q.isBlank())
            return "";

        boolean braveUsable = false;
        boolean naverUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE));
        } catch (Exception suppressed) { traceSuppressed("backupQuery.braveUsable", suppressed); }
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !(nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER));
        } catch (Exception suppressed) { traceSuppressed("backupQuery.naverUsable", suppressed); }
        boolean wantBraveFriendly = braveUsable && !naverUsable;

        return HybridSearchQueryPolicy.buildBackupQuery(q, wantBraveFriendly);
    }

    @Override
    public String buildTraceHtml(Object traceObj, java.util.List<String> snippets) {
        try {
            if (traceObj instanceof NaverSearchService.SearchTrace trace) {
                // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
                return naverService.buildTraceHtml(trace, snippets);
            }
        } catch (Exception e) {
            // trace HTML ?앹꽦 ?ㅽ뙣媛 ?꾩껜 ?먮쫫??留됱? ?딅룄濡?fail-soft 泥섎━
            traceSuppressed("buildTraceHtml", e);
            log.error("[Hybrid] buildTraceHtml errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
        }
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        return WebSearchProvider.super.buildTraceHtml(traceObj, snippets);
    }

    @Override
    public boolean isEnabled() {
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        // ?ш린?쒕뒗 "?꾩슂 ???쒕룄 媛?????곹깭?쇨퀬 蹂닿퀬 true瑜?諛섑솚?쒕떎.
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        return true;
    }

    @Override
    public String getName() {
        return "Hybrid(Brave+Naver)";
    }

    private static Long parseRetryAfterMs(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String v = headers.getFirst("Retry-After");
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.isEmpty()) {
            return null;
        }

        // 1) delta-seconds
        boolean allDigits = true;
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                long seconds = Long.parseLong(v);
                if (seconds <= 0) {
                    return 0L;
                }
                // single-hint cap (breaker itself also caps)
                return Math.min(seconds * 1000L, 60_000L);
            } catch (NumberFormatException ignore) {
                traceSuppressed("retryAfter.seconds", ignore);
                return null;
            }
        }

        // 2) HTTP-date (RFC 1123)
        try {
            java.time.ZonedDateTime dt = java.time.ZonedDateTime.parse(
                    v,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = java.time.Duration.between(java.time.Instant.now(), dt.toInstant()).toMillis();
            return Math.max(0L, Math.min(ms, 60_000L));
        } catch (java.time.DateTimeException ignore) {
            traceSuppressed("retryAfter.httpDate", ignore);
            return null;
        }
    }

}
