package com.example.lms.search.provider;

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

    // Brave가 topK를 채우면 Naver를 끝까지 기다리지 않고(또는 짧게만) opportunistic하게 합친 뒤 반환
    @Value("${gpt-search.hybrid.skip-naver-if-brave-sufficient:true}")
    private boolean skipNaverIfBraveSufficient;

    @Value("${gpt-search.hybrid.naver-opportunistic-ms:250}")
    private long naverOpportunisticMs;

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

    private boolean isBravePrimary() {
        GuardContext ctx = GuardContextHolder.get();
        if (ctx != null && StringUtils.hasText(ctx.getWebPrimary())) {
            return "BRAVE".equalsIgnoreCase(ctx.getWebPrimary());
        }
        return "BRAVE".equalsIgnoreCase(primary);
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

        // 1. Primary: Brave 시도
        try {
            // Use BraveSearchService.search() to benefit from cache/single-flight.
            List<String> brave = braveService.search(query, topK);
            if (brave != null && !brave.isEmpty()) {
                log.info("[Hybrid] Brave primary returned {} snippets", brave.size());
                return brave;
            }
            log.info("[Hybrid] Brave primary returned no results. Falling back to Naver.");
        } catch (Exception e) {
            log.warn("[Hybrid] Brave primary failed: {}", e.getMessage());
        }

        // 2. Fallback: Naver 시도
        try {
            List<String> naver = naverService.searchSnippetsSync(query, topK);
            if (naver != null && !naver.isEmpty()) {
                log.debug("[Hybrid] Naver fallback returned {} snippets", naver.size());
                return naver;
            }
        } catch (Exception e) {
            log.warn("[Hybrid] Naver fallback failed: {}", e.getMessage());
        }

        // 3. All failed → 빈 리스트 반환 (시스템은 죽지 않음)
        log.info("[Hybrid] All search providers failed. Returning empty list.");
        return Collections.emptyList();
    }

    private List<String> searchNaverFirst(String query, int topK) {
        // 1. Primary: Naver first
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

        // 2. Fallback: Brave
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

        // 3. All failed → 빈 리스트 반환
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
        return TimeUnit.NANOSECONDS.toMillis(remainNs);
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
                log.debug("[{}] Interrupted while collecting result", tag);
            } else {
                log.warn("[{}] Interrupted while collecting result", tag);
            }
            return fallback;
        } catch (ExecutionException ee) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, "execution", 0L, waitedMs, ee);
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed while collecting result: {}", tag, ee.getMessage());
            } else {
                log.warn("[{}] Failed while collecting result: {}", tag, ee.getMessage());
            }
            return fallback;
        } catch (Exception e) {
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent(stage, tag, step, "exception", 0L, waitedMs, e);
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed while collecting result: {}", tag, e.getMessage());
            } else {
                log.warn("[{}] Failed while collecting result: {}", tag, e.getMessage());
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

            // Track skips even when we drop okish events in normal mode.
            if (isSkip) {
                TraceStore.inc("web.await.skipped.count");
                TraceStore.put("web.await.skipped.last", c);
                if (engine != null && !engine.isBlank()) {
                    TraceStore.inc("web.await.skipped." + engine + ".count");
                }
            }

            // Noise control:
            // - Normal mode: keep only non-ok events (timeouts, budget_exhausted, cancel,
            // etc.).
            // - Manual debug: keep everything + detail.
            // - Boost debug: keep everything only for selected engines; other engines keep
            // non-ok only.
            boolean keepOkish = dbg && (!boostMode || detail);
            if (okish && !keepOkish) {
                return;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", stage);
            m.put("engine", engine);
            m.put("step", step);
            m.put("cause", cause);
            m.put("timeoutMs", timeoutMs);
            m.put("waitedMs", waitedMs);
            m.put("skip", isSkip);
            m.put("timeout", timeoutFlag);
            m.put("nonOk", !okish);
            m.put("detail", detail);

            if (err != null) {
                m.put("err", err.getClass().getSimpleName());
                if (dbg && (!boostMode || detail)) {
                    String msg = err.getMessage();
                    if (msg != null && !msg.isBlank()) {
                        m.put("errMsg", SafeRedactor.redact(msg));
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

        long timeoutMs = remainingMs(deadlineNs);
        if (timeoutMs <= 0) {
            // Re-check completion (race-safe) before cancelling.
            if (future.isDone()) {
                return safeGetNow(future, fallback, tag, "hard");
            }
            future.cancel(true);
            if (isTraceTag(tag)) {
                log.debug("[{}] Hard Timeout (budget exhausted) - Task Cancelled", tag);
            } else {
                log.warn("[{}] Hard Timeout (budget exhausted) - Task Cancelled", tag);
            }
            recordAwaitEvent("hard", tag, step, "budget_exhausted", 0L, 0L, null);
            return fallback;
        }

        long startNs = System.nanoTime();
        try {
            T v = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            recordAwaitEvent("hard", tag, step, "ok", timeoutMs, waitedMs, null);
            return v;
        } catch (TimeoutException te) {
            future.cancel(true); // ★ 핵심: 실제 interrupt 시도
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Hard Timeout ({}ms) - Task Cancelled", tag, timeoutMs);
            } else {
                log.warn("[{}] Hard Timeout ({}ms) - Task Cancelled", tag, timeoutMs);
            }
            recordAwaitEvent("hard", tag, step, "timeout", timeoutMs, waitedMs, te);
            return fallback;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Interrupted - Task Cancelled", tag);
            } else {
                log.warn("[{}] Interrupted - Task Cancelled", tag);
            }
            recordAwaitEvent("hard", tag, step, "interrupted", timeoutMs, waitedMs, ie);
            return fallback;
        } catch (Exception e) {
            future.cancel(true);
            long waitedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            if (isTraceTag(tag)) {
                log.debug("[{}] Failed: {}", tag, e.toString());
            } else {
                log.warn("[{}] Failed: {}", tag, e.getMessage());
            }
            String cause = (e instanceof java.util.concurrent.ExecutionException) ? "execution" : "exception";
            recordAwaitEvent("hard", tag, step, cause, timeoutMs, waitedMs, e);
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
        if (braveService == null || !braveService.isEnabled()) {
            // skip
        } else if (skipBrave) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave call");
        } else if (braveService.isCoolingDown()) {
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave call",
                    braveService.cooldownRemainingMs());
        } else {
            braveFuture = searchIoExecutor.submit(() -> {
                int braveK = Math.min(Math.max(topK, 5), 20);
                return braveService.searchWithMeta(braveQuery, braveK);
            });
        }

        BraveSearchResult braveMetaEarly = null;
        boolean braveEarlyEnoughToSkipNaver = false;
        if (braveFuture != null
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
                    Thread.currentThread().interrupt();
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

            naverFuture = searchIoExecutor.submit(() -> {
                try {
                    return naverService.searchSnippetsSync(query, callK);
                } catch (Exception e) {
                    // Wire Naver signals to breaker
                    if (nightmareBreaker != null) {
                        if (e instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                            nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_NAVER, query, "HTTP 429");
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

        BraveSearchResult braveMeta = (braveMetaEarly != null)
                ? braveMetaEarly
                : awaitWithDeadline(
                        braveFuture,
                        deadlineNs,
                        BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave");
        // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
        if (nightmareBreaker != null && braveFuture != null && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message());
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
                Thread.currentThread().interrupt();
            } catch (Exception ignore) {
                // keep empty
            }
            if (naver == null)
                naver = Collections.emptyList();
        }

        List<String> merged = mergeAndLimit(naver, brave, topK);
        log.info("[Hybrid] Korean search merged: brave={}, naver={}, merged={}",
                brave.size(), naver.size(), merged.size());

        recordSoakWebMetrics(naverFuture != null && !naverSkippedByHedge, merged, naver);

        return merged;
    }

    private List<String> searchKoreanNaverAndBrave(String query, int topK) {
        final String braveQuery = convertToEnglishSearchTerm(query);
        final long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Breaker: skip engines when OPEN
        boolean skipBrave = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE);
        boolean skipNaver = nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER);

        // --- Naver-first hedged strategy ---
        Future<List<String>> naverFuture = null;
        if (skipNaver) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver call");
        } else if (naverService == null || !naverService.isEnabled()) {
            // skip
        } else {
            naverFuture = searchIoExecutor.submit(() -> {
                try {
                    return naverService.searchSnippetsSync(query, topK);
                } catch (Exception e) {
                    // Wire Naver signals to breaker
                    if (nightmareBreaker != null) {
                        if (e instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                            nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_NAVER, query, "HTTP 429");
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
        }

        List<String> naverEarly = null;
        boolean naverEarlyEnoughToSkipBrave = false;
        if (naverFuture != null
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
                    Thread.currentThread().interrupt();
                } catch (Exception ignore) {
                    // Naver errored early -> allow Brave to cover
                }
            }
        }

        Future<BraveSearchResult> braveFuture = null;
        if (braveService == null || !braveService.isEnabled()) {
            // skip
        } else if (skipBrave) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Brave, skipping Brave call");
        } else if (braveService.isCoolingDown()) {
            log.warn("[Hybrid] Brave is cooling down ({}ms remaining), skipping Brave call",
                    braveService.cooldownRemainingMs());
        } else if (!naverEarlyEnoughToSkipBrave) {
            braveFuture = searchIoExecutor.submit(() -> {
                int braveK = Math.min(Math.max(topK, 5), 20);
                return braveService.searchWithMeta(braveQuery, braveK);
            });
        } else if (log.isDebugEnabled()) {
            int naverSz = (naverEarly == null) ? 0 : naverEarly.size();
            log.debug("[Hybrid] Korean hedged: skipping Brave start (Naver {} results within {}ms)",
                    naverSz, koreanHedgeDelayMs);
        }

        List<String> naver = (naverEarly != null)
                ? naverEarly
                : awaitWithDeadline(naverFuture, deadlineNs, Collections.emptyList(), "Naver");

        boolean naverEnough = skipNaverIfBraveSufficient && naver != null && naver.size() >= topK;
        BraveSearchResult braveMeta = naverEnough
                ? awaitSoft(braveFuture, naverOpportunisticMs, BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave")
                : awaitWithDeadline(braveFuture, deadlineNs, BraveSearchResult.ok(Collections.emptyList(), 0L),
                        "Brave");

        // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
        if (nightmareBreaker != null && braveFuture != null && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message());
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
                Thread.currentThread().interrupt();
            } catch (Exception ignore) {
                // keep empty
            }
            if (naver == null)
                naver = Collections.emptyList();
        }

        List<String> merged = mergeAndLimit(naver, brave, topK);
        log.info("[Hybrid] Korean search merged: brave={}, naver={}, merged={}",
                brave.size(), naver.size(), merged.size());

        recordSoakWebMetrics(naverFuture != null, merged, naver);
        return merged;
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
        }

        BraveSearchResult braveMetaEarly = null;
        boolean braveEarlyEnoughToSkipNaver = false;
        if (braveFuture != null
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
                    Thread.currentThread().interrupt();
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

            naverFuture = searchIoExecutor.submit(() -> {
                try {
                    NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, callK);
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
        if (nightmareBreaker != null && braveFuture != null && braveMeta != null) {
            switch (braveMeta.status()) {
                case HTTP_429, RATE_LIMIT_LOCAL, COOLDOWN ->
                    nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message());
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
                naver.snippets() != null ? naver.snippets() : Collections.emptyList(),
                brave,
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
        if (skipNaver) {
            log.warn("[Hybrid] NightmareBreaker OPEN for Naver, skipping Naver trace call");
        } else if (naverService == null || !naverService.isEnabled()) {
            // skip
        } else {
            naverFuture = searchIoExecutor.submit(() -> {
                try {
                    NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                    return (result != null) ? result
                            : new NaverSearchService.SearchResult(Collections.emptyList(), null);
                } catch (Exception e) {
                    // Trace is quality-aiding. Treat failures as debug noise.
                    log.debug("[Hybrid] Naver korean-trace search failed: {}", e.toString());
                    return new NaverSearchService.SearchResult(Collections.emptyList(), null);
                }
            });
        }

        NaverSearchService.SearchResult naverEarly = null;
        boolean naverEarlyEnoughToSkipBrave = false;
        if (naverFuture != null
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
                    Thread.currentThread().interrupt();
                } catch (Exception ignore) {
                    // Naver errored early -> allow Brave to cover
                }
            }
        }

        Future<BraveSearchResult> braveFuture = null;
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

        BraveSearchResult braveMeta = null;
        if (!braveSkippedByHedge) {
            braveMeta = naverEnough
                    ? awaitSoft(braveFuture, naverOpportunisticMs, BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Trace")
                    : awaitWithDeadline(braveFuture, deadlineNs, BraveSearchResult.ok(Collections.emptyList(), 0L),
                            "Brave-Trace");

            // Wire Brave rate-limit signals to breaker (only if we actually called Brave)
            if (nightmareBreaker != null && braveFuture != null && braveMeta != null) {
                switch (braveMeta.status()) {
                    case HTTP_429, RATE_LIMIT_LOCAL, COOLDOWN ->
                        nightmareBreaker.recordRateLimit(NightmareKeys.WEBSEARCH_BRAVE, query, braveMeta.message());
                    case OK -> nightmareBreaker.recordSuccess(NightmareKeys.WEBSEARCH_BRAVE, braveMeta.elapsedMs());
                    default -> {
                    }
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
                Thread.currentThread().interrupt();
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

        recordSoakWebMetrics(naverFuture != null, merged, naver.snippets());

        return new NaverSearchService.SearchResult(merged, trace);
    }

    private NaverSearchService.SearchResult searchWithTraceBraveFirst(String query, int topK) {

        // 1. Primary: Brave (Trace는 래핑하여 제공)
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

        // 2. Fallback: Naver (Trace 포함)
        try {
            NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
            if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.warn("[Hybrid] Naver trace-search failed: {}", e.getMessage());
        }

        // 3. All failed
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    private NaverSearchService.SearchResult searchWithTraceNaverFirst(String query, int topK) {
        // 1. Primary: Naver with trace
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

        // 2. Fallback: Brave (trace synthesized)
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

        String latinOnly = q.replaceAll("[^A-Za-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();

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
            return sb.toString().trim();
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
}