package com.example.lms.search.provider;

import ai.abandonware.nova.orch.trace.OrchDigest;
import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
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
import java.util.LinkedHashSet;
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

/**
 * Brave → Naver 순서로 폴백하는 하이브리드 검색 공급자
 * (폴백 로직을 서비스 계층으로 캡슐화)
 */
@Service
@Primary // WebSearchProvider 타입 주입 시 기본 구현체
@RequiredArgsConstructor
public class HybridWebSearchProvider implements WebSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchProvider.class);

    // STRIKE/공식 우선 모드에서 낮은 신뢰도의 소스를 가볍게 제외 (fail-soft)
    private static final List<String> LOW_TRUST_URL_MARKERS = List.of(
            "namu.wiki",
            "tistory.com",
            "blog.naver.com",
            "cafe.naver.com",
            "dcinside.com",
            "ruliweb.com",
            "fmkorea.com",
            "theqoo.net",
            "ppomppu.co.kr",
            "youtube.com",
            "x.com",
            "twitter.com",
            "instagram.com");
    private final NaverSearchService naverService;
    private final BraveSearchService braveService;

    
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
    private int timeoutSec;

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
     * the outer await often times out first due to scheduler overhead → await_timeout=100%.
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

    // Brave가 topK를 채우면 Naver를 끝까지 기다리지 않고(또는 짧게만) opportunistic하게 합친 뒤 반환
    @Value("${gpt-search.hybrid.skip-naver-if-brave-sufficient:true}")
    private boolean skipNaverIfBraveSufficient;

    @Value("${gpt-search.hybrid.naver-opportunistic-ms:250}")
    private long naverOpportunisticMs;

    /**
     * Cap how long we allow Naver to consume the remaining time budget in a single Hybrid call.
     *
     * <p>
     * Motivation: prevent Naver hard-timeout(≈6~7s) from
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

    // Even if Brave returns enough results quickly, still call Naver
    // opportunistically (KR source diversity).
    @Value("${gpt-search.hybrid.korean.force-opportunistic-naver-even-if-brave-fast:true}")
    private boolean forceOpportunisticNaverEvenIfBraveFast;

    // Symmetric: If Naver returns enough results within hedge delay, skip starting
    // Brave.
    @Value("${gpt-search.hybrid.korean.skip-brave-if-naver-min-results:6}")
    private int skipBraveIfNaverMinResults;

    @Autowired
    @Qualifier("searchIoExecutor")
    private ExecutorService searchIoExecutor;

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
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE)
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            // fail-soft
        }

        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
            // fail-soft
        }

        // Auto-switch away from a down provider when the other is available
        // (fail-soft).
        if (wantBrave && !braveUsable && naverUsable) {
            try {
                TraceStore.put("webSearch.primary.autoSwitch", "BRAVE->NAVER");
            } catch (Exception ignore) {
            }
            return false;
        }

        if (!wantBrave && !naverUsable && braveUsable) {
            try {
                TraceStore.put("webSearch.primary.autoSwitch", "NAVER->BRAVE");
            } catch (Exception ignore) {
            }
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

    private static boolean containsHangul(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    private String extractKeywords(String query) {
        if (query == null) {
            return null;
        }
        // 조사/어미/불용어 제거 (간단 버전)
        String s = query;
        s = s.replaceAll(
                "(누구야|뭐야|무엇이야|무슨뜻|무슨의미|알려줘(?:봐|요)?|말해줘(?:봐|요)?|검색해(?:줘|봐|요)?|찾아줘(?:봐|요)?|설명해줘(?:봐|요)?|해줘(?:봐|요)?|어떤|사람|캐릭터|이야|인가요\\??|좀)",
                "");
        // 존칭 완화
        s = s.replaceAll("교수님", "교수")
                .replaceAll("선생님", "선생")
                .replaceAll("의사선생님", "의사");
        return s.trim();
    }

    @Override
    public List<String> search(String query, int topK) {

        var gctx = GuardContextHolder.get();
        boolean sensitive = gctx != null && gctx.isSensitiveTopic();
        boolean planBlockAll = gctx != null && gctx.planBool("privacy.boundary.block-web-search", false);
        boolean planBlockOnSensitive = gctx != null
                && gctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
        if (blockWebSearch || planBlockAll || (sensitive && (blockWebSearchOnSensitive || planBlockOnSensitive))) {
            try {
                com.example.lms.search.TraceStore.put("privacy.web.blocked", true);
            } catch (Exception ignore) {
            }
            return java.util.Collections.emptyList();
        }

        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) {
            logSkipOnce("SKIP_EMPTY_QUERY", "HybridWebSearchProvider skipped (blank query)");
            return java.util.Collections.emptyList();
        }
        query = safeQuery;

        boolean isKorean = containsHangul(query);

        if (!isKorean) {
            // 기존 동작 유지 (비한국어 쿼리)
            java.util.List<String> out = isBravePrimary() ? searchBraveFirst(query, topK)
                    : searchNaverFirst(query, topK);
            return maybeBackupOnce(query, topK, out);
        }

        // 한국어 쿼리일 때: Soak(수세식) 전략 적용
        java.util.List<String> out = searchKoreanSmartMerge(query, topK);
        return maybeBackupOnce(query, topK, out);
    }

    private List<String> searchKoreanSmartMerge(String query, int topK) {
        // 1차: 기존 Brave/Naver 병렬 검색
        // [PATCH] officialOnly(또는 plan override)에서는 Naver-first를 강제해
        // 영문/금융 스팸 드리프트 및 Brave 쿼터 소모 리스크를 낮춘다.
        GuardContext gctx = GuardContextHolder.get();
        boolean officialOnly = gctx != null && gctx.isOfficialOnly();
        boolean preferNaver = officialOnly ||
                (gctx != null && (gctx.planBool("search.web.preferNaver", false)
                        || gctx.planBool("web.preferNaver", false)));

        List<String> primary;
        if (preferNaver) {
            try {
                TraceStore.put("webSearch.providerPreference", "naver-first");
            } catch (Exception ignore) {
            }
            primary = searchKoreanNaverAndBrave(query, topK);
        } else if (isBravePrimary()) {
            try {
                TraceStore.put("webSearch.providerPreference", "brave-first");
            } catch (Exception ignore) {
            }
            primary = searchKoreanBraveAndNaver(query, topK);
        } else {
            try {
                TraceStore.put("webSearch.providerPreference", "naver-first(config)");
            } catch (Exception ignore) {
            }
            primary = searchKoreanNaverAndBrave(query, topK);
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

        log.info("[Hybrid] Soak 수세식 발동: '{}' -> '{}'", query, extracted);

        List<String> keywordResults;
        if (preferNaver) {
            keywordResults = searchKoreanNaverAndBrave(extracted, topK);
        } else if (isBravePrimary()) {
            keywordResults = searchKoreanBraveAndNaver(extracted, topK);
        } else {
            keywordResults = searchKoreanNaverAndBrave(extracted, topK);
        }

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (keywordResults != null) {
            merged.addAll(keywordResults);
        }

        if (merged.isEmpty()) {
            return Collections.emptyList();
        }
        return merged.stream().limit(topK).toList();
    }

    private List<String> searchBraveFirst(String query, int topK) {

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE)
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            // fail-soft
        }

        // 1. Primary: Brave 시도 (단, 브레이커/쿨다운이면 즉시 Naver로 강등)
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
                List<String> brave = braveService.search(query, topK);
                if (brave != null && !brave.isEmpty()) {
                    log.info("[Hybrid] Brave primary returned {} snippets", brave.size());
                    return brave;
                }
                log.info("[Hybrid] Brave primary returned empty list. Falling back to Naver.");
            } catch (Exception e) {
                log.warn("[Hybrid] Brave primary failed: {}", e.getMessage());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "direct", 0L);
            log.warn("[Hybrid] Brave primary skipped (breaker/cooldown). Falling back to Naver.");
        }

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
            // fail-soft
        }

        // 2. Fallback: Naver 시도
        if (naverUsable) {
            try {
                List<String> naver = naverService.searchSnippetsSync(query, topK);
                if (naver != null && !naver.isEmpty()) {
                    log.info("[Hybrid] Naver fallback returned {} snippets", naver.size());
                    return naver;
                }
            } catch (Exception e) {
                log.warn("[Hybrid] Naver fallback failed: {}", e.getMessage());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "direct");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception ignore) {
            }
            log.warn("[Hybrid] Naver fallback skipped (breaker OPEN/HALF_OPEN).");
        }

        // 3. All failed → 빈 리스트 반환 (시스템은 죽지 않음)
        if (!braveUsable && !naverUsable) {
            recordWebHardDown("both_skipped", "direct.braveFirst");
        }
        log.info("[Hybrid] All search providers failed. Returning empty list.");
        return Collections.emptyList();
    }

    private List<String> searchNaverFirst(String query, int topK) {

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
            // fail-soft
        }

        // 1. Primary: Naver first (단, 브레이커면 즉시 Brave로 강등)
        if (naverUsable) {
            try {
                List<String> naver = naverService.searchSnippetsSync(query, topK);
                if (naver != null && !naver.isEmpty()) {
                    log.info("[Hybrid] Naver primary returned {} snippets", naver.size());
                    return naver;
                }
                log.info("[Hybrid] Naver primary returned no results. Falling back to Brave.");
            } catch (Exception e) {
                log.warn("[Hybrid] Naver primary failed: {}", e.getMessage());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "direct");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception ignore) {
            }
            log.warn("[Hybrid] Naver primary skipped (breaker OPEN/HALF_OPEN). Falling back to Brave.");
        }

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE)
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            // fail-soft
        }

        // 2. Fallback: Brave
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
                List<String> brave = braveService.search(query, topK);
                if (brave != null && !brave.isEmpty()) {
                    log.debug("[Hybrid] Brave fallback returned {} snippets", brave.size());
                    return brave;
                }
            } catch (Exception e) {
                log.warn("[Hybrid] Brave fallback failed: {}", e.getMessage());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "direct", 0L);
            log.warn("[Hybrid] Brave fallback skipped (breaker/cooldown).");
        }

        // 3. All failed → 빈 리스트 반환
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
    private String convertToEnglishSearchTerm(String query) {
        if (!StringUtils.hasText(query))
            return query;
        String normalized = query.toLowerCase().replaceAll("\\s+", "");

        // 게임 도메인 감지 → tech suffix 적용 금지
        boolean isGameIntent = normalized.contains("원신")
                || normalized.contains("genshin")
                || normalized.contains("캐릭터")
                || normalized.contains("스킬")
                || normalized.contains("티어")
                || normalized.contains("빌드");
        if (isGameIntent) {
            return query; // 원본 그대로 반환
        }

        // 테크 제품 마커 확인 (없으면 suffix 적용 금지)
        boolean hasTechMarker = normalized
                .matches(".*(갤럭시|s\\d{2}|fold|flip|아이폰|iphone|pixel|snapdragon|exynos|rtx|cpu|gpu|노트북).*");

        boolean rumor = hasRumorIntent(normalized);

        // 폴드7 (기본은 공식/스펙 중심; 루머 의도일 때만 leaks)
        if (normalized.contains("폴드7") || normalized.contains("zfold7") || normalized.contains("fold7")) {
            return rumor
                    ? "Galaxy Z Fold 7 leak rumors renders"
                    : "Samsung Galaxy Z Fold7 official specs release date price";
        }

        // 루머 의도가 있을 때만 leaks
        if (rumor) {
            return query + " latest leaks rumors";
        }

        // 스펙/사양/출시/가격/리뷰/비교 등은 테크 마커가 있을 때만 확장
        if (hasTechMarker && normalized.matches(".*(스펙|사양|출시|가격|리뷰|비교).*")) {
            return query + " official specs release date price review";
        }

        return query;
    }

    private static boolean hasRumorIntent(String normalized) {
        if (normalized == null)
            return false;
        return normalized.contains("루머")
                || normalized.contains("유출")
                || normalized.contains("렌더")
                || normalized.contains("leak")
                || normalized.contains("rumor")
                || normalized.contains("renders");
    }

    private static long remainingMs(long deadlineNs) {
        long remainNs = deadlineNs - System.nanoTime();
        if (remainNs <= 0) {
            return 0L;
        }

        // Precision guard: TimeUnit.NANOSECONDS.toMillis(..) truncates sub-millisecond
        // remainders to 0. Keep a 1ms minimum so a positive remaining budget doesn't
        // get misclassified as 'budget exhausted'.
        long ms = TimeUnit.NANOSECONDS.toMillis(remainNs);
        return (ms <= 0L) ? 1L : ms;
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

        effective = Math.max(250L, effective);

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
            // best-effort
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
            // Avoid poisoning pooled request threads.
            Thread.interrupted();
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
                log.debug("[{}] Failed while collecting result: {}{}", tag, ee.getMessage(), LogCorrelation.suffix());
            } else {
                log.warn("[{}] Failed while collecting result: {}{}", tag, ee.getMessage(), LogCorrelation.suffix());
            }
            return fallback;
        } catch (Exception e) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, "exception", 0L, waitedMs, e);
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed while collecting result: {}{}", tag, e.getMessage(), LogCorrelation.suffix());
            } else {
                log.warn("[{}] Failed while collecting result: {}{}", tag, e.getMessage(), LogCorrelation.suffix());
            }
            return fallback;
        }
    }

    private static boolean isDbgSearch() {
        try {
            String v = MDC.get("dbgSearch");
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String safeMdc(String key) {
        try {
            return MDC.get(key);
        } catch (Throwable ignore) {
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

    // ─────────────────────────────────────────────────────────────
    // Fail-soft checkpoint: record *why* Brave was not scheduled/awaited.
    // This is distinct from web.await.skipped.* (which only sees missing_future).
    // Expected values (ops/CI): breaker_open | cooldown | hedge_skip
    // ─────────────────────────────────────────────────────────────
    private static void recordBraveSkipped(String reason, String stage, long extraMs, Throwable err) {
        try {
            TraceStore.put("web.brave.skipped", true);
            if (reason != null && !reason.isBlank())
                TraceStore.put("web.brave.skipped.reason", reason);
            if (stage != null && !stage.isBlank())
                TraceStore.put("web.brave.skipped.stage", stage);
            if (extraMs > 0)
                TraceStore.put("web.brave.skipped.extraMs", extraMs);
            if (err != null) {
                TraceStore.put("web.brave.skipped.err", err.getClass().getSimpleName());
            }
            TraceStore.put("web.brave.skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web.brave.skipped.count");
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static void recordBraveSkipped(String reason, String stage, long extraMs) {
        recordBraveSkipped(reason, stage, extraMs, null);
    }

    // ─────────────────────────────────────────────────────────────
    // Fail-soft checkpoint: record *why* Naver was not scheduled/awaited.
    // This is distinct from web.await.skipped.* (which only sees missing_future).
    // Expected values (ops/CI): breaker_open | disabled | submit_failed
    // ─────────────────────────────────────────────────────────────
    private static void recordNaverSkipped(String reason, String stage, long extraMs, Throwable err) {
        try {
            TraceStore.put("web.naver.skipped", true);
            if (reason != null && !reason.isBlank())
                TraceStore.put("web.naver.skipped.reason", reason);
            if (stage != null && !stage.isBlank())
                TraceStore.put("web.naver.skipped.stage", stage);
            if (extraMs > 0)
                TraceStore.put("web.naver.skipped.extraMs", extraMs);
            if (err != null) {
                TraceStore.put("web.naver.skipped.err", err.getClass().getSimpleName());
            }
            TraceStore.put("web.naver.skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web.naver.skipped.count");
        } catch (Throwable ignore) {
            // best-effort
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
                    TraceStore.put("web.naver.cacheOnlyFuture.reason", reason.trim());
                }
                TraceStore.put("web.naver.cacheOnlyFuture.count", cached.size());
            } catch (Throwable ignore) {
            }
            return cached;
        } catch (Throwable t) {
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
                step += "(" + reason.trim() + ")";
            }
            trace.steps.add(new NaverSearchService.SearchStep(step, cached.size(), cached.size(), 0L));
            return new NaverSearchService.SearchResult(cached, trace);
        } catch (Throwable t) {
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
                msg = msg + ":" + reason.trim();
            }
            // elapsedMs is unknown for cache-only reads; keep 0.
            return new BraveSearchResult(cached, BraveSearchResult.Status.OK, 200, 0L, msg, 0L);
        } catch (Throwable t) {
            return BraveSearchResult.ok(Collections.emptyList(), 0L);
        }
    }

    private static void recordWebHardDown(String reason, String stage) {
        try {
            TraceStore.put("web.hardDown", true);
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("web.hardDown.reason", reason);
            }
            if (stage != null && !stage.isBlank()) {
                TraceStore.put("web.hardDown.stage", stage);
            }
            TraceStore.put("web.hardDown.tsMs", System.currentTimeMillis());
            TraceStore.inc("web.hardDown.count");
        } catch (Throwable ignore) {
            // best-effort
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
                TraceStore.put("web.await.skipped.last", c);
                if (engine != null && !engine.isBlank()) {
                    TraceStore.inc("web.await.skipped." + engine + ".count");
                    TraceStore.put("web.await.skipped." + engine + ".last", c);
                }
                if (engine != null && !engine.isBlank()) {
                    TraceStore.put("web.await.skipped.last.engine", engine);
                }
                if (step != null && !step.isBlank()) {
                    TraceStore.put("web.await.skipped.last.step", step);
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
                    String eng = (engine == null || engine.isBlank()) ? "unknown" : engine;
                    String key = "web.await.keepOnce." + c + "." + eng;
                    Object prev = TraceStore.putIfAbsent(key, Boolean.TRUE);
                    forceKeep = (prev == null);
                } catch (Throwable ignore) {
                    // fail-soft
                }
            }

            if (okish && !keepOkish && !forceKeep) {
                return;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", TraceStore.inc("web.await.events.seq"));
            m.put("tsMs", System.currentTimeMillis());
            m.put("stage", stage);
            m.put("engine", engine);
            m.put("step", step);
            m.put("cause", cause);
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
                    m.put("rid", rid);
                }
                String sid = safeMdc("sessionId");
                if (sid != null && !sid.isBlank()) {
                    m.put("sid", sid);
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
                        m.put("errMsg", SafeRedactor.redact(msg));
                    }
                }

                // Non-consuming error-body preview for WebClientResponseException.
                // (WebClientResponseException stores the body bytes in-memory.)
                if (root instanceof WebClientResponseException w) {
                    try {
                        m.put("httpStatus", w.getStatusCode().value());
                    } catch (Throwable ignore) {
                        // ignore
                    }
                    if (dbg && (!boostMode || detail)) {
                        try {
                            String body = w.getResponseBodyAsString();
                            if (body != null && !body.isBlank()) {
                                body = SafeRedactor.redact(body);
                                if (body.length() > 260) {
                                    body = body.substring(0, 260) + "…";
                                }
                                m.put("httpBody", body);
                            }
                        } catch (Throwable ignore) {
                            // ignore
                        }
                        try {
                            var req = w.getRequest();
                            if (req != null && req.getURI() != null) {
                                m.put("httpUri", String.valueOf(req.getURI()));
                            }
                        } catch (Throwable ignore) {
                            // ignore
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
            // ignore
        }
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

        final long rawTimeoutMs = remainingMs(deadlineNs);
        final long nearMs = Math.max(0L, awaitNearExhaustedThresholdMs);

        final boolean budgetExhausted = rawTimeoutMs <= 0L;

        // officialOnly floor: allow a slightly larger min-live-budget in officialOnly mode
        // (where evidence quality is prioritized and "tiny_budget/budget_exhausted" cascades are costly).
        boolean officialOnly = false;
        try {
            GuardContext gctx = GuardContextHolder.getOrDefault();
            officialOnly = gctx != null && gctx.isOfficialOnly();
        } catch (Throwable ignore) {
            officialOnly = false;
        }

        long floorMs = Math.max(0L, awaitMinLiveBudgetMs);
        if (officialOnly) {
            floorMs = Math.max(floorMs, Math.max(0L, awaitMinLiveBudgetMsOfficialOnly));
            try {
                TraceStore.put("web.await.minLiveBudget.officialOnly", true);
                TraceStore.put("web.await.minLiveBudget.officialOnly.floorMs", floorMs);
            } catch (Throwable ignore) {
            }
        }

        final boolean nearExhausted = rawTimeoutMs > 0L && nearMs > 0L && rawTimeoutMs <= nearMs;
        final boolean tinyBudget = rawTimeoutMs > 0L && floorMs > 0L && rawTimeoutMs < floorMs;

        final boolean floorApplied = floorMs > 0L
                && (nearExhausted
                        || (awaitFloorTinyBudget && tinyBudget)
                        || (budgetExhausted && officialOnly && awaitFloorBudgetExhaustedOfficialOnly));
        final String floorCause = budgetExhausted ? "budget_exhausted"
                : (nearExhausted ? "near_exhausted" : (tinyBudget ? "tiny_budget" : "none"));

        long timeoutMs = floorApplied ? floorMs : rawTimeoutMs;
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
                    // best-effort
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

            if (floorApplied) {
                try {
                    TraceStore.put("web.await.minLiveBudget.budgetExhaustedFloorApplied", true);
                } catch (Throwable ignore) {
                    // fail-soft
                }
            }

            // Budget exhausted and no floor -> immediate fallback.
            // IMPORTANT: do NOT cancel here; cancellation can drop near-complete results
            // and amplifies starvation.
            if (!floorApplied) {
                TraceStore.inc("web.await.cancelSuppressed");
                try {
                    TraceStore.put("web.await.cancelSuppressed.reason", "budget_exhausted");
                } catch (Exception ignore) {
                    // fail-soft
                }
                if (isTraceTag(tag)) {
                    log.debug("[{}] Hard Timeout (budget exhausted) - no cancel{}", tag, LogCorrelation.suffix());
                } else {
                    log.warn("[{}] Hard Timeout (budget exhausted) - no cancel{}", tag, LogCorrelation.suffix());
                }
                recordAwaitEvent("hard", tag, step, "budget_exhausted", 0L, 0L, null);
                return fallback;
            }
        } else if (nearExhausted) {
            // Budget is technically positive but too small to be meaningful.
            // Treat it like (near) budget exhaustion and allow a grace window.
            try {
                TraceStore.inc("web.await.nearExhausted.count");
                TraceStore.put("web.await.nearExhausted.ms", rawTimeoutMs);
            } catch (Exception ignore) {
                // fail-soft
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
                // fail-soft
            }
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
                    TraceStore.put("web.await.cancelSuppressed.reason", floorCause);
                } catch (Exception ignore) {
                    // fail-soft
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
                    // fail-soft
                }
            }

            try {
                // cancel(false): do not interrupt pool workers (interrupt can poison executors)
                future.cancel(false);
            } catch (Throwable ignore) {
                // fail-soft
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
            // Avoid poisoning pooled request threads.
            Thread.interrupted();
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);

            if (floorApplied && awaitCancelSuppressedWhenFloor) {
                TraceStore.inc("web.await.cancelSuppressed");
                try {
                    TraceStore.put("web.await.cancelSuppressed.reason", floorCause);
                } catch (Exception ignore) {
                    // fail-soft
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
                    // fail-soft
                }
            }

            try {
                // cancel(false): do not interrupt pool workers (interrupt can poison executors)
                future.cancel(false);
            } catch (Throwable ignore) {
                // fail-soft
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
                    TraceStore.put("web.await.cancelSuppressed.reason", floorCause);
                } catch (Exception ignore) {
                    // fail-soft
                }
                TraceStore.put("web.await.minLiveBudget.used", true);
                TraceStore.putIfAbsent("web.await.minLiveBudget.ms", timeoutMs);
                TraceStore.put("web.await.minLiveBudget.lastRawTimeoutMs", rawTimeoutMs);
                TraceStore.put("web.await.minLiveBudget.cause", floorCause);

                if (isTraceTag(tag)) {
                    log.debug("[{}] Hard await failed (floorApplied) - cancel_suppressed: {}{}",
                            tag, e.toString(), LogCorrelation.suffix());
                } else {
                    log.warn("[{}] Hard await failed (floorApplied) - cancel_suppressed: {}{}",
                            tag, e.toString(), LogCorrelation.suffix());
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
                    // fail-soft
                }
            }

            try {
                // cancel(false): do not interrupt pool workers (interrupt can poison executors)
                future.cancel(false);
            } catch (Throwable ignore) {
                // fail-soft
            }
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed: {}", tag, e.toString());
            } else {
                log.warn("[{}] Failed: {}", tag, e.getMessage());
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
        // consecutive soft-timeout -> 250 → 400 → 550 → ... (cap 1500ms)
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
        } catch (Throwable ignore) {
        }
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
                    TraceStore.putIfAbsent("web.failsoft.rateLimitBackoff." + providerKey + ".reason", d.reason());
                    TraceStore.putIfAbsent("web.failsoft.rateLimitBackoff." + providerKey + ".remainingMs", d.remainingMs());
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        } catch (Throwable t) {
            log.debug("[WEB_AWAIT_TIMEOUT_BACKOFF] provider={} install failed: {}", providerKey, t.toString());
        }
    }



    private <T> T awaitSoft(Future<T> future, long softTimeoutMs, T fallback, String tag) {
        final String step = "awaitSoft";

        if (future == null) {
            recordAwaitEvent("soft", tag, step, "missing_future", softTimeoutMs, 0L, null);
            return fallback;
        }

        // Small optimization: if already completed, don't pay soft-timeout overhead.
        if (future.isDone()) {
            return safeGetNow(future, fallback, tag, "soft");
        }

        long startNs = System.nanoTime();
        try {
            T v = future.get(softTimeoutMs, TimeUnit.MILLISECONDS);
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent("soft", tag, step, "ok", softTimeoutMs, waitedMs, null);
            return v;
        } catch (TimeoutException te) {
            // [Soft Wait] timeout이면 그냥 fallback으로 넘어감 (cancel X)
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Soft Timeout after {}ms", tag, softTimeoutMs);
            }
            recordAwaitEvent("soft", tag, step, "timeout", softTimeoutMs, waitedMs, te);
            return fallback;
        } catch (InterruptedException ie) {
            // 깨끗한 interrupt hygiene
            Thread.interrupted();
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Soft Interrupted", tag);
            }
            recordAwaitEvent("soft", tag, step, "interrupted", softTimeoutMs, waitedMs, ie);
            return fallback;
        } catch (Exception e) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Soft await failed: {}", tag, e.toString());
            }
            String cause = (e instanceof java.util.concurrent.ExecutionException) ? "execution" : "exception";
            recordAwaitEvent("soft", tag, step, cause, softTimeoutMs, waitedMs, e);
            return fallback;
        }
    }

    private List<String> searchKoreanBraveAndNaver(String query, int topK) {

        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE);
        boolean skipNaver = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER);

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
                braveSkipExtraMs = (nightmareBreaker != null)
                        ? nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_BRAVE)
                        : 0L;
            } catch (Throwable ignore) {
                // best-effort
            }
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave call");
        } else if (braveService.isCoolingDown()) {
            braveSkipReason = "cooldown";
            braveSkipExtraMs = braveService.cooldownRemainingMs();
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave call", braveSkipExtraMs);
        } else {
            try {
                braveFuture = searchIoExecutor.submit(() -> braveService.searchWithMeta(braveQuery, braveK));
                braveLiveCall = true;
            } catch (Exception submitEx) {
                braveSkipReason = "submit_failed";
                recordBraveSkipped(braveSkipReason, "korean.braveFirst", 0L, submitEx);
                braveSkipRecorded = true;
                log.warn("[Hybrid] Brave scheduling failed, skipping Brave call: {}", submitEx.toString());
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
                    // Brave is slow -> hedge by starting Naver below
                } catch (InterruptedException ie) {
                    // Avoid poisoning pooled request threads.
                    Thread.interrupted();
                } catch (Exception ignore) {
                    // Brave errored early -> allow Naver to cover
                }
            }
        }

        Future<List<String>> naverFuture = null;
        boolean naverSkippedByHedge = false;

        if (skipNaver) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver call");
        } else if (naverService == null || !naverService.isEnabled()) {
            // skip
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

            naverFuture = searchIoExecutor.submit(() -> {
                try {
                    return naverService.searchSnippetsSync(query, callK, naverBlockTimeout);
                } catch (Exception e) {
                    // Wire Naver signals to breaker
                    if (nightmareBreaker != null) {
                        if (e instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                            Long retryAfterMs = parseRetryAfterMs(w.getHeaders());
                            nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_NAVER, query, w, "HTTP 429",
                                    retryAfterMs);
                        } else if (e instanceof WebClientResponseException w && w.getStatusCode().value() == 403) {
                            // Some providers return 403 for bot detection.
                            nightmareBreaker.recordRejected(NightmareKeys.WEBSEARCH_NAVER, query, "HTTP 403");
                        } else if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                            nightmareBreaker.recordTimeout(NightmareKeys.WEBSEARCH_NAVER, query, "timeout");
                        } else {
                            nightmareBreaker.recordFailure(NightmareKeys.WEBSEARCH_NAVER,
                                    NightmareBreaker.FailureKind.UNKNOWN, e, query);
                        }
                    }
                    log.warn("[Hybrid] Naver korean search failed: {}", e.getMessage());
                    return Collections.emptyList();
                }
            });
        } else {
            naverSkippedByHedge = true;
            if (log.isDebugEnabled()) {
                int braveSz = (braveMetaEarly == null || braveMetaEarly.snippets() == null) ? 0
                        : braveMetaEarly.snippets().size();
                log.debug("[Hybrid] Korean hedged: skipping Naver start (Brave {} results within {}ms)",
                        braveSz, koreanHedgeDelayMs);
            }
        }

        if (!braveLiveCall && naverFuture == null) {
            recordWebHardDown("both_skipped", "korean.braveFirst");
        }

        BraveSearchResult braveMeta = (braveMetaEarly != null)
                ? braveMetaEarly
                : awaitWithDeadline(
                        braveFuture,
                        deadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave");
        // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
        if (nightmareBreaker != null && braveLiveCall && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, HTTP_503, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message(),
                            braveMeta.cooldownMs());
                case OK -> nightmareBreaker.recordSuccess(NightmareKeys.WEBSEARCH_BRAVE, braveMeta.elapsedMs());
                default -> {
                }
            }
        }
        List<String> brave = (braveMeta == null) ? Collections.emptyList() : braveMeta.snippets();
        boolean braveEnough = skipNaverIfBraveSufficient && brave != null && brave.size() >= topK;
        List<String> naver = (naverFuture == null)
                ? Collections.emptyList()
                : (braveEnough
                        ? awaitSoft(naverFuture, naverOpportunisticMs, Collections.emptyList(), "Naver")
                        : awaitWithDeadline(naverFuture, deadlineNs, Collections.emptyList(), "Naver"));

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

        // ✅ late-join grace: Naver가 데드라인 직후 도착하는 케이스를 완화
        // (두 엔진 모두 비어있을 때만 200ms 추가 합류를 시도한다.)
        if ((naver == null || naver.isEmpty()) && (brave == null || brave.isEmpty())
                && naverFuture != null && !naverFuture.isDone()) {
            try {
                naver = naverFuture.get(200L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignore) {
                // keep empty
            } catch (InterruptedException ie) {
                // Avoid poisoning pooled request threads.
                Thread.interrupted();
            } catch (Exception ignore) {
                // keep empty
            }
            if (naver == null)
                naver = Collections.emptyList();
        }

        // ✅ opportunistic join (deficit): when Naver hard-timeouts but Brave is partially filled,
        // try a small additional join window and/or cache-only to stabilize merge quality.
        if ((naver == null || naver.isEmpty())
                && brave != null && !brave.isEmpty()
                && brave.size() < topK
                && naverFuture != null) {
            final long joinMs = Math.min(200L, Math.max(50L, naverOpportunisticMs));
            if (!naverFuture.isDone()) {
                List<String> late = awaitSoft(naverFuture, joinMs, Collections.emptyList(), "Naver.lateJoinDeficit");
                if (late != null && !late.isEmpty()) {
                    naver = late;
                    try {
                        TraceStore.put("web.naver.opportunisticJoin.deficit.used", true);
                        TraceStore.put("web.naver.opportunisticJoin.deficit.ms", joinMs);
                    } catch (Exception ignore) {
                        // fail-soft
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
                        // fail-soft
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
        mergeMeta.put("calledNaver", naverFuture != null);
        mergeMeta.put("hedgeDelayMs", koreanHedgeDelayMs);
        mergeMeta.put("skipNaverIfBraveMinResults", skipNaverIfBraveMinResults);
        mergeMeta.put("skipNaverIfBraveSufficient", skipNaverIfBraveSufficient);
        mergeMeta.put("forceOpportunisticNaverEvenIfBraveFast", forceOpportunisticNaverEvenIfBraveFast);
        mergeMeta.put("naverOpportunisticMs", naverOpportunisticMs);
        emitMergeBoundaryEvent("korean.brave_then_naver", query, topK, brave, naver, merged, mergeMeta, null);

        log.info("[Hybrid] Korean search merged: brave={}, naver={}, merged={}",
                brave.size(), naver.size(), merged.size());

        recordSoakWebMetrics(naverFuture != null && !naverSkippedByHedge, merged, naver);

        return merged;
    }

    private List<String> searchKoreanNaverAndBrave(String query, int topK) {
        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Official-only mode prefers to keep OFFICIAL/DOCS diversity; do not hedge-skip
        // Brave
        // when Naver is fast, because it can shrink the citeable pool and amplify
        // starvation.
        boolean officialOnly = false;
        try {
            GuardContext ctx = GuardContextHolder.get();
            officialOnly = (ctx != null && ctx.isOfficialOnly());
        } catch (Throwable ignore) {
            // fail-soft
        }

        // Breaker: skip engines when OPEN
        boolean skipBrave = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE);
        boolean skipNaver = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER);

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
                naverSkipExtraMs = (nightmareBreaker != null)
                        ? nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER)
                        : 0L;
            } catch (Throwable ignore) {
                // best-effort
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
                naverReserveMs = 0L;
            }

            final java.time.Duration naverBlockTimeout = java.time.Duration.ofMillis(
                    resolveNaverBlockTimeoutMs(deadlineNs, naverReserveMs, "korean.naver-first"));
            try {
                naverFuture = searchIoExecutor.submit(() -> {
                    try {
                        return naverService.searchSnippetsSync(query, topK, naverBlockTimeout);
                    } catch (Exception e) {
                        // Wire Naver signals to breaker
                        if (nightmareBreaker != null) {
                            if (e instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                                Long retryAfterMs = parseRetryAfterMs(w.getHeaders());
                                nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_NAVER, query, w, "HTTP 429",
                                        retryAfterMs);
                            } else if (e instanceof WebClientResponseException w && w.getStatusCode().value() == 403) {
                                nightmareBreaker.recordRejected(NightmareKeys.WEBSEARCH_NAVER, query, "HTTP 403");
                            } else if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                                nightmareBreaker.recordTimeout(NightmareKeys.WEBSEARCH_NAVER, query, "timeout");
                            } else {
                                nightmareBreaker.recordFailure(NightmareKeys.WEBSEARCH_NAVER,
                                        NightmareBreaker.FailureKind.UNKNOWN, e, query);
                            }
                        }
                        log.warn("[Hybrid] Naver korean search failed: {}", e.getMessage());
                        return Collections.emptyList();
                    }
                });
                naverLiveCall = true;
            } catch (Exception submitEx) {
                naverSkipReason = "submit_failed";
                recordNaverSkipped(naverSkipReason, "korean.naverFirst", 0L, submitEx);
                naverSkipRecorded = true;
                log.warn("[Hybrid] Naver scheduling failed, skipping Naver call: {}", submitEx.toString());
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
                    // Naver is slow -> hedge by starting Brave below
                } catch (InterruptedException ie) {
                    // Avoid poisoning pooled request threads.
                    Thread.interrupted();
                } catch (Exception ignore) {
                    // Naver errored early -> allow Brave to cover
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
                braveSkipExtraMs = (nightmareBreaker != null)
                        ? nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_BRAVE)
                        : 0L;
            } catch (Throwable ignore) {
                // best-effort
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
                    // fail-soft
                }
                if (log.isDebugEnabled()) {
                    int naverSz = (naverEarly == null) ? 0 : naverEarly.size();
                    log.debug(
                            "[Hybrid] Korean hedged: bypassing Brave hedge_skip (officialOnly, Naver {} results within {}ms)",
                            naverSz, koreanHedgeDelayMs);
                }
            }
            try {
                braveFuture = searchIoExecutor.submit(() -> braveService.searchWithMeta(braveQuery, braveK));
                braveLiveCall = true;
            } catch (Exception submitEx) {
                braveSkipReason = "submit_failed";
                recordBraveSkipped(braveSkipReason, "korean.naverFirst", 0L, submitEx);
                braveSkipRecorded = true;
                log.warn("[Hybrid] Brave scheduling failed, skipping Brave call: {}", submitEx.toString());
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
        } catch (Throwable ignore) {
        }

        boolean braveCapIsSoft = !braveSoftJoin && "deadline_capped".equals(braveJoinMode);
        long braveCappedSoftMs = braveCapIsSoft ? Math.max(0L, remainingMs(braveJoinDeadlineNs)) : 0L;

        BraveSearchResult braveMeta = (braveSoftJoin || braveCapIsSoft)
                ? awaitSoft(braveFuture,
                        braveSoftJoin ? naverOpportunisticMs : braveCappedSoftMs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave")
                : awaitWithDeadline(braveFuture, braveJoinDeadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L), "Brave");

        // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
        if (nightmareBreaker != null && braveLiveCall && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, HTTP_503, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message(),
                            braveMeta.cooldownMs());
                case OK -> nightmareBreaker.recordSuccess(NightmareKeys.WEBSEARCH_BRAVE, braveMeta.elapsedMs());
                default -> {
                }
            }
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

        // ✅ late-join grace: Naver가 데드라인 직후 도착하는 케이스를 완화
        // (두 엔진 모두 비어있을 때만 200ms 추가 합류를 시도한다.)
        if ((naver == null || naver.isEmpty()) && (brave == null || brave.isEmpty())
                && naverFuture != null && !naverFuture.isDone()) {
            try {
                naver = naverFuture.get(200L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignore) {
                // keep empty
            } catch (InterruptedException ie) {
                // Avoid poisoning pooled request threads.
                Thread.interrupted();
            } catch (Exception ignore) {
                // keep empty
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

            // Invariant: if at least one engine had results, merged should not be empty.
            if (mergedSafe.isEmpty() && (!braveSafe.isEmpty() || !naverSafe.isEmpty())) {
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
            // fail-soft
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
            // fail-soft
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
            // fail-soft
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
        if (primary == null) {
            primary = Collections.emptyList();
        }
        if (secondary == null) {
            secondary = Collections.emptyList();
        }

        // [FIX-A5] 병합 결과 검증 + null/blank 필터링
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        for (String s : primary) {
            if (s != null && !s.isBlank()) {
                merged.add(s);
            }
        }
        for (String s : secondary) {
            if (s != null && !s.isBlank()) {
                merged.add(s);
            }
        }

        if (merged.isEmpty()) {
            log.warn("[Hybrid] No merged results after filtering, returning empty list");
        }

        int effectiveTopK = topK <= 0 ? 3 : Math.max(3, topK);

        List<String> out = merged.stream()
                .limit(effectiveTopK)
                .toList();

        // STRIKE/공식 우선 모드에서는 저신뢰 소스를 가볍게 필터 (전부 제거되면 원본 유지)
        return applyStrikeFilterIfNeeded(out);
    }

    private static List<String> applyStrikeFilterIfNeeded(List<String> in) {
        if (in == null || in.isEmpty()) {
            return in;
        }
        GuardContext ctx = GuardContextHolder.get();
        if (ctx == null) {
            return in;
        }
        if (!(ctx.isStrikeMode() || ctx.isOfficialOnly())) {
            return in;
        }
        ArrayList<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null || s.isBlank()) {
                continue;
            }
            if (looksLowTrustSnippet(s)) {
                continue;
            }
            out.add(s);
        }
        return out.isEmpty() ? in : out;
    }

    private static boolean looksLowTrustSnippet(String snippet) {
        String lower = snippet.toLowerCase();
        for (String marker : LOW_TRUST_URL_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {

        var gctx = GuardContextHolder.get();
        boolean sensitive = gctx != null && gctx.isSensitiveTopic();
        boolean planBlockAll = gctx != null && gctx.planBool("privacy.boundary.block-web-search", false);
        boolean planBlockOnSensitive = gctx != null
                && gctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
        if (blockWebSearch || planBlockAll || (sensitive && (blockWebSearchOnSensitive || planBlockOnSensitive))) {
            try {
                com.example.lms.search.TraceStore.put("privacy.web.blocked", true);
            } catch (Exception ignore) {
            }
            return new NaverSearchService.SearchResult(java.util.Collections.emptyList(), null);
        }

        // ─────────────────────────────────────────────────────────────
        // [DEBUG HARDENING]
        // - UI(TraceHtmlBuilder)의 Summary 라인은
        // rawTrace.query/provider/elapsedMs(totalMs)에 의존한다.
        // - Hybrid 경로에서 SearchTrace가 null로 떨어지면 query/provider/totalMs가 비어
        // "Search Trace - query: - provider: - ... - 0ms" 처럼 보이는 문제가 생긴다.
        // - 여기서 "end-to-end" 값을 강제로 채워서, 콘솔/패널/저장 trace 모두에서
        // 디버깅이 가능하도록 한다.
        // ─────────────────────────────────────────────────────────────
        final long t0Ns = System.nanoTime();

        boolean isKorean = containsHangul(query);
        boolean bravePrimary = isBravePrimary();

        NaverSearchService.SearchResult r;
        if (!isKorean) {
            r = bravePrimary ? searchWithTraceBraveFirst(query, topK) : searchWithTraceNaverFirst(query, topK);
        } else {
            // 한국어 쿼리: Soak(수세식) Trace 전략 적용
            r = searchWithTraceKoreanSmartMerge(query, topK);
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
        if (!soakEnabled || primary == null || primary.snippets() == null || primary.snippets().size() >= 3) {
            return primary;
        }

        String extracted = extractKeywords(query);
        if (!StringUtils.hasText(extracted) || extracted.equals(query)) {
            return primary;
        }

        log.info("[Hybrid] Soak(trace) 수세식 발동: '{}' -> '{}'", query, extracted);

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
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE);
        boolean skipNaver = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER);

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
            braveFuture = searchIoExecutor.submit(() -> {
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
                    // Brave is slow -> start Naver below
                } catch (InterruptedException ie) {
                    // Avoid poisoning pooled request threads.
                    Thread.interrupted();
                } catch (Exception ignore) {
                    // Brave errored early -> allow Naver to cover
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

            naverFuture = searchIoExecutor.submit(() -> {
                try {
                    NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, callK,
                            naverBlockTimeout);
                    return (result != null) ? result
                            : new NaverSearchService.SearchResult(Collections.emptyList(), null);
                } catch (Exception e) {
                    // Trace is quality-aiding. Treat failures as debug noise.
                    log.debug("[Hybrid] Naver korean-trace search failed: {}", e.toString());
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
            });
        } else {
            naverSkippedByHedge = true;
        }

        BraveSearchResult braveMeta = (braveMetaEarly != null)
                ? braveMetaEarly
                : awaitWithDeadline(
                        braveFuture,
                        deadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave-Trace");

        // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
        if (nightmareBreaker != null && braveLiveCall && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, HTTP_503, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message(),
                            braveMeta.cooldownMs());
                case OK -> nightmareBreaker.recordSuccess(NightmareKeys.WEBSEARCH_BRAVE, braveMeta.elapsedMs());
                default -> {
                }
            }
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
                    int expandedK = Math.min(topK * 2, 20); // Brave 최대 20
                    // Retry budget is intentionally small. Fail-fast.
                    long retryBudgetMs = Math.min(timeoutMs, 1200L);
                    long retryDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryBudgetMs);

                    Future<BraveSearchResult> retry = searchIoExecutor
                            .submit(() -> braveService.searchWithMeta(braveQuery, expandedK));
                    BraveSearchResult retryMeta = awaitWithDeadline(
                            retry,
                            retryDeadlineNs,
                            BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Expanded");
                    if (retryMeta != null) {
                        braveMeta = retryMeta;
                    }
                    brave = (retryMeta == null) ? Collections.emptyList() : retryMeta.snippets();
                    braveCount = brave.size();
                    log.info("[Hybrid] Brave expanded search returned {} snippets", braveCount);
                }
            } catch (Exception ex) {
                log.warn("[Hybrid] Brave expanded search failed: {}", ex.getMessage());
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
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE);
        boolean skipNaver = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER);

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
                naverSkipExtraMs = (nightmareBreaker != null)
                        ? nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER)
                        : 0L;
            } catch (Throwable ignore) {
                // best-effort
            }
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver trace call");
        } else {
            final java.time.Duration naverBlockTimeout = java.time.Duration.ofMillis(
                    resolveNaverBlockTimeoutMs(deadlineNs, 0L, "korean-trace.naver-first"));
            try {
                naverFuture = searchIoExecutor.submit(() -> {
                    try {
                        NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK,
                                naverBlockTimeout);
                        return (result != null) ? result
                                : new NaverSearchService.SearchResult(Collections.emptyList(), null);
                    } catch (Exception e) {
                        // Trace is quality-aiding. Treat failures as debug noise.
                        log.debug("[Hybrid] Naver korean-trace search failed: {}", e.toString());
                        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                    }
                });
                naverLiveCall = true;
            } catch (Exception submitEx) {
                naverSkipReason = "submit_failed";
                recordNaverSkipped(naverSkipReason, "korean.naverFirst.trace", 0L, submitEx);
                naverSkipRecorded = true;
                log.warn("[Hybrid] Naver scheduling failed, skipping Naver trace call: {}", submitEx.toString());
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
                    // Naver is slow -> start Brave below
                } catch (InterruptedException ie) {
                    // Avoid poisoning pooled request threads.
                    Thread.interrupted();
                } catch (Exception ignore) {
                    // Naver errored early -> allow Brave to cover
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
            braveFuture = searchIoExecutor.submit(() -> {
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

        int naverCount = (naver != null && naver.snippets() != null) ? naver.snippets().size() : 0;
        boolean naverEnough = skipNaverIfBraveSufficient && naverCount >= topK;

        boolean officialOnly = false;
        try {
            GuardContext gctx = GuardContextHolder.get();
            officialOnly = gctx != null && gctx.isOfficialOnly();
        } catch (Throwable ignore) {
        }

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
        } catch (Throwable ignore) {
        }

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

        // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
        if (nightmareBreaker != null && braveLiveCall && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, HTTP_503, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message(),
                            braveMeta.cooldownMs());
                case OK -> nightmareBreaker.recordSuccess(NightmareKeys.WEBSEARCH_BRAVE, braveMeta.elapsedMs());
                default -> {
                }
            }
        }

        List<String> brave = (braveMeta == null) ? Collections.emptyList() : braveMeta.snippets();

        // Null-safe
        if (brave == null)
            brave = Collections.emptyList();
        if (naver == null)
            naver = new NaverSearchService.SearchResult(Collections.emptyList(), null);

        int braveCount = brave.size();
        naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;

        // ✅ late-join grace: Naver trace 결과가 데드라인 직후 도착하는 케이스를 완화
        if (braveCount == 0 && naverCount == 0 && naverFuture != null && !naverFuture.isDone()) {
            try {
                NaverSearchService.SearchResult late = naverFuture.get(200L, TimeUnit.MILLISECONDS);
                if (late != null) {
                    naver = late;
                    naverCount = (naver.snippets() != null) ? naver.snippets().size() : 0;
                }
            } catch (TimeoutException ignore) {
                // keep empty
            } catch (InterruptedException ie) {
                // Avoid poisoning pooled request threads.
                Thread.interrupted();
            } catch (Exception ignore) {
                // keep empty
            }
        }

        if (braveCount == 0 && naverCount == 0) {
            log.warn("[Hybrid] Both engines returned 0, expanding Brave topK");
            try {
                if (braveService != null && braveService.isEnabled()) {
                    int expandedK = Math.min(topK * 2, 20);
                    long retryBudgetMs = Math.min(timeoutMs, 1200L);
                    long retryDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryBudgetMs);

                    Future<BraveSearchResult> retry = searchIoExecutor
                            .submit(() -> braveService.searchWithMeta(braveQuery, expandedK));
                    BraveSearchResult retryMeta = awaitWithDeadline(
                            retry,
                            retryDeadlineNs,
                            BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Expanded");
                    if (retryMeta != null) {
                        braveMeta = retryMeta;
                    }
                    brave = (retryMeta == null) ? Collections.emptyList() : retryMeta.snippets();
                    braveCount = brave.size();
                    log.info("[Hybrid] Brave expanded search returned {} snippets", braveCount);
                }
            } catch (Exception ex) {
                log.warn("[Hybrid] Brave expanded search failed: {}", ex.getMessage());
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

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE)
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            // fail-soft
        }

        // 1. Primary: Brave (Trace는 래핑하여 제공) — 단, 브레이커/쿨다운이면 즉시 Naver로 강등
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
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
                log.warn("[Hybrid] Brave primary (trace) failed: {}", e.getMessage());
            }
        } else {
            recordBraveSkipped("breaker_or_cooldown", "trace", 0L);
            log.warn("[Hybrid] Brave primary (trace) skipped (breaker/cooldown). Falling back to Naver.");
        }

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
            // fail-soft
        }

        // 2. Fallback: Naver (Trace 포함)
        if (naverUsable) {
            try {
                NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("[Hybrid] Naver trace-search failed: {}", e.getMessage());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "trace");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception ignore) {
            }
        }

        // 3. All failed
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    private NaverSearchService.SearchResult searchWithTraceNaverFirst(String query, int topK) {

        boolean naverUsable = false;
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
            // fail-soft
        }

        // 1. Primary: Naver with trace (단, 브레이커면 즉시 Brave로 강등)
        if (naverUsable) {
            try {
                NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                    log.info("[Hybrid] Naver primary (trace) returned {} snippets", result.snippets().size());
                    return result;
                }
                log.info("[Hybrid] Naver primary (trace) returned no results. Falling back to Brave.");
            } catch (Exception e) {
                log.warn("[Hybrid] Naver primary (trace) failed: {}", e.getMessage());
            }
        } else {
            try {
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
                TraceStore.put("web.naver.skipped.stage", "trace");
                TraceStore.inc("web.naver.skipped.count");
            } catch (Exception ignore) {
            }
            log.warn("[Hybrid] Naver primary (trace) skipped (breaker OPEN/HALF_OPEN). Falling back to Brave.");
        }

        boolean braveUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE)
                    && !braveService.isCoolingDown();
        } catch (Exception ignore) {
            // fail-soft
        }

        // 2. Fallback: Brave (trace synthesized)
        if (braveUsable) {
            try {
                // Use BraveSearchService.search() to benefit from cache/single-flight.
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
                log.warn("[Hybrid] Brave fallback (trace) failed: {}", e.getMessage());
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
        } catch (Exception ignore) {
        }
        log.info("[{}] {}{}", tag, message, LogCorrelation.suffix());
    }

    private List<String> maybeBackupOnce(String originalQuery, int topK, List<String> primary) {
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

        try {
            if (TraceStore.get("websearch.backup.used") != null) {
                return primary == null ? Collections.emptyList() : primary;
            }
        } catch (Exception ignore) {
        }

        String backup = buildBackupQuery(originalQuery);
        if (backup == null)
            backup = "";
        backup = backup.trim();
        if (backup.isBlank() || backup.equalsIgnoreCase(originalQuery)) {
            return primary == null ? Collections.emptyList() : primary;
        }

        try {
            TraceStore.put("websearch.backup.used", true);
        } catch (Exception ignore) {
        }

        boolean braveOpen = false;
        boolean naverOpen = false;
        try {
            braveOpen = nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE);
        } catch (Exception ignore) {
        }
        try {
            naverOpen = nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
        }

        log.warn("[WEBSEARCH_BACKUP_QUERY] merged=0 -> backup='{}' from='{}' braveOpen={} naverOpen={}{}",
                SafeRedactor.redact(backup), SafeRedactor.redact(originalQuery), braveOpen, naverOpen,
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
            log.warn("[WEBSEARCH_BACKUP_QUERY] backup search failed: {}{}", e.getMessage(), LogCorrelation.suffix());
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

        try {
            if (TraceStore.get("websearch.remergeOnce.used") != null) {
                return Collections.emptyList();
            }
        } catch (Exception ignore) {
        }

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
            log.warn("[WEBSEARCH_REMERGE_ONCE] config maxTotalWaitMs={}ms is very low (recommended: 250~350ms). rid={} sessionId={} ",
                    maxTotalWaitMs, LogCorrelation.requestId(), LogCorrelation.sessionId());
        } else if (waitTooHigh) {
            log.warn("[WEBSEARCH_REMERGE_ONCE] config maxTotalWaitMs={}ms is high (user-visible latency). rid={} sessionId={}",
                    maxTotalWaitMs, LogCorrelation.requestId(), LogCorrelation.sessionId());
        }

        // Debug: record configured knobs per request (avoid accidental coupling with await floors).
        TraceStore.put("web.failsoft.remergeOnce.config.maxTotalWaitMs", maxTotalWaitMs);
        TraceStore.put("web.failsoft.remergeOnce.config.initialDelayMs", delayMs);
        TraceStore.put("web.failsoft.remergeOnce.config.maxPolls", polls);

        boolean braveOpen = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE);
        boolean naverOpen = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER);

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
            startMeta.put("await.minLiveBudget.cause", String.valueOf(TraceStore.get("web.await.minLiveBudget.cause")));
            startMeta.put("await.cancelSuppressed.count", TraceStore.getLong("web.await.cancelSuppressed"));
            startMeta.put("await.minLiveBudget.lastRawTimeoutMs", TraceStore.getLong("web.await.minLiveBudget.lastRawTimeoutMs"));
            emitRemergeOnceEvent("start", originalQuery, topK, startMeta, null);
        } catch (Throwable ignore) {
            // fail-soft
        }

        long waitedMs = 0L;
        int usedPolls = 0;
        List<String> merged = Collections.emptyList();

        for (int i = 0; i < polls; i++) {
            long sleptMs = 0L;
            long delayMsUsed = delayMs;

            if (delayMs > 0 && waitedMs < maxTotalWaitMs) {
                long sleepMs = Math.min(delayMs, maxTotalWaitMs - waitedMs);
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        // Don't leak interruption/cancellation into the rest of the request.
                        Thread.interrupted();
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
                    // fail-soft
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
                TraceStore.put("web.failsoft.remergeOnce.missReason", missReason);
                endMeta.put("missReason", missReason);
            }
            emitRemergeOnceEvent((merged != null && !merged.isEmpty()) ? "hit" : "miss", originalQuery, topK, endMeta, null);
        } catch (Throwable ignore) {
            // fail-soft
        }

        if (merged != null && !merged.isEmpty()) {
            log.warn(
                    "[WEBSEARCH_REMERGE_ONCE] merged=0 -> cache-only remerge ok outCount={} waitMs={} polls={} rid={} sessionId={}",
                    merged.size(), waitedMs, usedPolls, LogCorrelation.requestId(), LogCorrelation.sessionId());
            return merged;
        }

        log.info("[WEBSEARCH_REMERGE_ONCE] merged=0 -> cache-only remerge miss waitMs={} polls={} rid={} sessionId={}",
                waitedMs, usedPolls, LogCorrelation.requestId(), LogCorrelation.sessionId());
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

    // Preserve advanced search operators (site:/inurl:/intitle:/filetype:/ext:) in
    // backup queries.
    // Normalisation like latinOnly must not destroy these operators.
    private static boolean containsAdvancedSearchOperators(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        String s = q.toLowerCase(Locale.ROOT);
        return s.contains("site:")
                || s.contains("inurl:")
                || s.contains("intitle:")
                || s.contains("filetype:")
                || s.contains("ext:");
    }

    private static boolean isAdvancedSearchOperatorToken(String token) {
        if (token == null) {
            return false;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return false;
        }
        int idx = t.indexOf(':');
        if (idx <= 0 || idx >= t.length() - 1) {
            return false;
        }
        String op = t.substring(0, idx).toLowerCase(Locale.ROOT);
        return op.equals("site")
                || op.equals("inurl")
                || op.equals("intitle")
                || op.equals("filetype")
                || op.equals("ext");
    }

    private static String trimEdgePunct(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        // Strip wrapping quotes/brackets often attached in user input.
        while (!t.isEmpty()) {
            char c = t.charAt(0);
            if (c == '"' || c == '`' || c == '(' || c == '[' || c == '{' || c == '<') {
                t = t.substring(1).trim();
                continue;
            }
            break;
        }
        while (!t.isEmpty()) {
            char c = t.charAt(t.length() - 1);
            if (c == '"' || c == '`' || c == ')' || c == ']' || c == '}' || c == '>'
                    || c == ',' || c == ';' || c == '.') {
                t = t.substring(0, t.length() - 1).trim();
                continue;
            }
            break;
        }
        return t;
    }

    private String buildOperatorPreservedBackupQuery(String q) {
        if (q == null) {
            return "";
        }
        String s = q.trim();
        if (s.isBlank()) {
            return "";
        }

        String[] toks = s.split("\\s+");
        LinkedHashSet<String> ops = new LinkedHashSet<>();
        List<String> rest = new ArrayList<>();

        for (String tok : toks) {
            if (tok == null) {
                continue;
            }
            String cleaned = trimEdgePunct(tok);
            if (isAdvancedSearchOperatorToken(cleaned)) {
                ops.add(cleaned);
            } else {
                rest.add(tok);
            }
        }

        if (ops.isEmpty()) {
            return "";
        }

        // Keep the operator tokens verbatim, but dedupe and shorten the rest.
        String restJoined = String.join(" ", rest).trim();
        String restKeywords = extractKeywords(restJoined);
        if (restKeywords == null) {
            restKeywords = "";
        }
        restKeywords = restKeywords.replaceAll("\\s+", " ").trim();

        LinkedHashSet<String> restUniq = new LinkedHashSet<>();
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();
        if (!restKeywords.isBlank()) {
            for (String tok : restKeywords.split("\\s+")) {
                if (tok == null) {
                    continue;
                }
                String t = tok.trim();
                if (t.isBlank()) {
                    continue;
                }
                String key = t.toLowerCase(Locale.ROOT);
                if (seenLower.contains(key)) {
                    continue;
                }
                seenLower.add(key);
                restUniq.add(t);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String op : ops) {
            if (op == null || op.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(op.trim());
        }

        int restLimit = 4;
        int used = 0;
        for (String t : restUniq) {
            if (used >= restLimit) {
                break;
            }
            if (t == null || t.isBlank()) {
                continue;
            }
            sb.append(' ').append(t.trim());
            used++;
        }

        return sb.toString().trim();
    }

    private String buildBackupQuery(String originalQuery) {
        if (originalQuery == null)
            return "";
        String q = originalQuery.trim();
        if (q.isBlank())
            return "";

        final boolean hasOps = containsAdvancedSearchOperators(q);
        final String operatorPreserved = hasOps ? buildOperatorPreservedBackupQuery(q) : "";

        boolean braveUsable = false;
        boolean naverUsable = false;
        try {
            braveUsable = braveService != null && braveService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE);
        } catch (Exception ignore) {
        }
        try {
            naverUsable = naverService != null && naverService.isEnabled()
                    && !nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);
        } catch (Exception ignore) {
        }
        boolean wantBraveFriendly = braveUsable && !naverUsable;

        String keywords = extractKeywords(q);
        String english = convertToEnglishSearchTerm(q);

        String latinOnly = hasOps ? "" : q.replaceAll("[^A-Za-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();

        // Avoid degenerate backup queries (e.g., year-only like "2026") that tend to
        // produce spammy or irrelevant results.
        if (keywords != null && keywords.trim().matches("^\\d+$")) {
            keywords = "";
        }
        if (english != null && english.trim().matches("^\\d+$")) {
            english = "";
        }
        if (!latinOnly.isBlank() && latinOnly.trim().matches("^\\d+$")) {
            latinOnly = "";
        }

        if (hasOps && StringUtils.hasText(operatorPreserved)
                && !operatorPreserved.equalsIgnoreCase(q)) {
            return operatorPreserved;
        }
        if (wantBraveFriendly) {
            if (!latinOnly.isBlank() && !latinOnly.equalsIgnoreCase(q))
                return latinOnly;
            if (english != null && !english.isBlank() && !english.equalsIgnoreCase(q))
                return english;
            if (keywords != null && !keywords.isBlank() && !keywords.equalsIgnoreCase(q))
                return keywords;
        } else {
            if (keywords != null && !keywords.isBlank() && !keywords.equalsIgnoreCase(q))
                return keywords;
            if (english != null && !english.isBlank() && !english.equalsIgnoreCase(q))
                return english;
            if (!latinOnly.isBlank() && !latinOnly.equalsIgnoreCase(q))
                return latinOnly;
        }

        // Last resort: shorten overly long queries.
        String[] toks = q.replaceAll("\\s+", " ").trim().split(" ");
        if (toks.length > 6) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0)
                    sb.append(' ');
                sb.append(toks[i]);
            }
            String shortened = sb.toString().trim();
            if (shortened.matches("^\\d+$")) {
                return "";
            }
            return shortened;
        }
        return "";
    }

    @Override
    public String buildTraceHtml(Object traceObj, java.util.List<String> snippets) {
        try {
            if (traceObj instanceof NaverSearchService.SearchTrace trace) {
                // NaverSearchService가 제공하는 HTML 생성기를 그대로 재사용한다.
                return naverService.buildTraceHtml(trace, snippets);
            }
        } catch (Exception e) {
            // trace HTML 생성 실패가 전체 흐름을 막지 않도록 fail-soft 처리
            log.error("[Hybrid] buildTraceHtml error: {}", e.getMessage());
        }
        // 알 수 없는 trace 타입이거나 오류 발생 시 기본 구현으로 폴백
        return WebSearchProvider.super.buildTraceHtml(traceObj, snippets);
    }

    @Override
    public boolean isEnabled() {
        // NaverSearchService 내부에서 키/설정 여부를 판단하므로,
        // 여기서는 "필요 시 시도 가능"한 상태라고 보고 true를 반환한다.
        // BraveSearchService 역시 자체 enabled 플래그를 가지고 있어
        // search(...) 호출 시 내부에서 안전하게 처리된다.
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
        } catch (Throwable ignore) {
            return null;
        }
    }

}