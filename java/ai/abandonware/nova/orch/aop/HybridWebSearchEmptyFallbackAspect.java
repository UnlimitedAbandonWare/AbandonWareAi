package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fail-soft bypass for Hybrid(Brave+Naver) web search when the primary
 * concurrent join
 * returns empty due to timeout/cancellation.
 *
 * <p>
 * Motivation: 운영 환경에서 timeout/budget 경계에서 Future cancel/interrupt 전염이 발생하면
 * (Brave breaker-open + Naver cancelled 조합 등) 결과가 0개가 되는 starvation이 반복될 수 있다.
 *
 * <p>
 * This aspect executes a last-resort, <b>budgeted partial-collect</b> fallback
 * <b>only when</b>:
 * <ul>
 * <li>HybridWebSearchProvider.search 결과가 empty</li>
 * <li>TraceStore 에 timeout/non-ok await 이벤트가 기록됨 (즉, cancellation 흔적이 있음)</li>
 * <li>privacy/web guard에 의해 웹 호출이 차단되지 않음</li>
 * </ul>
 *
 * <p>
 * Implementation:
 * <ul>
 * <li>Naver + Brave 를 동시에 submit</li>
 * <li>{@link ExecutorCompletionService#poll(long, TimeUnit)} 로 deadline 내 완료된
 * 것만 회수</li>
 * <li>하나라도 오면 merge + dedupe + limit 후 반환</li>
 * <li>미완료 Future 는 <b>cancel(false)</b>만 best-effort (절대 cancel(true) 호출
 * 금지)</li>
 * </ul>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class HybridWebSearchEmptyFallbackAspect {

    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchEmptyFallbackAspect.class);

    /**
     * Best-effort parsing for URL host extraction in fallback snippets.
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\s)]+", Pattern.CASE_INSENSITIVE);

    /**
     * Conservative low-trust host markers used only for NOFILTER_SAFE outputs.
     *
     * <p>
     * Important: we only drop low-trust entries when we still have at least one
     * other entry
     * to avoid reintroducing the empty-result starvation.
     */
    private static final List<String> NOFILTER_SAFE_LOW_TRUST_HOST_MARKERS = List.of(
            "blog.naver.com",
            "cafe.naver.com",
            "tistory.com",
            "brunch.co.kr",
            "namu.wiki",
            "dcinside.com",
            "ruliweb.com",
            "inven.co.kr",
            "fmkorea.com",
            "theqoo.net",
            "instiz.net",
            "ppomppu.co.kr",
            "clien.net",
            "reddit.com",
            "quora.com",
            "youtube.com",
            "youtu.be",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com");

    /**
     * Cold-start bootstrap epoch.
     *
     * <p>
     * GLGO(서비스 기동 직후) 구간에서는 budget_exhausted(raw=0ms) / 429 / COOLDOWN 전환이
     * 짧은 시간에 몰릴 수 있어, merged=0이 일시적으로 연속 발생할 수 있습니다.
     * 이 값을 기반으로 "초반 구간"에서만 더 공격적인 fail-soft ladder를 적용합니다.
     */
    private static final long BOOT_EPOCH_MS = System.currentTimeMillis();

    private final Environment env;
    private final ObjectProvider<NaverSearchService> naverSearchServiceProvider;
    private final ObjectProvider<BraveSearchService> braveSearchServiceProvider;
    private final ObjectProvider<ExecutorService> searchIoExecutorProvider;
    private final ObjectProvider<NightmareBreaker> nightmareBreakerProvider;
    private final ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider;

    /**
     * Extra grace window used when the calculated deadline is already exhausted,
     * but in-flight tasks may still complete imminently.
     *
     * Mirrors the HybridWebSearchProvider "min-live-budget" idea to reduce
     * starvation (merged=0) caused by late completions.
     */
    private final long completionPollMinLiveBudgetMs;

    /**
     * Cold-start window in which we prefer faster step-down (cache-only first) and
     * avoid repeating live network calls when the initial join already hit a
     * budget_exhausted(raw=0ms) floor.
     */
    private final long coldStartWindowMs;

    public HybridWebSearchEmptyFallbackAspect(
            Environment env,
            ObjectProvider<NaverSearchService> naverSearchServiceProvider,
            ObjectProvider<BraveSearchService> braveSearchServiceProvider,
            ObjectProvider<ExecutorService> searchIoExecutorProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider) {
        this.env = env;
        this.completionPollMinLiveBudgetMs = getLong(600,
                "gpt-search.hybrid.await.min-live-budget-ms",
                "nova.orch.web.failsoft.hybrid-empty-fallback.min-live-budget-ms");

        this.coldStartWindowMs = clampMs(getLong(180_000L,
                "nova.orch.web.failsoft.hybrid-empty-fallback.coldstart.window-ms",
                "nova.orch.web.failsoft.hybridEmptyFallback.coldstart.windowMs"), 0L, 10 * 60_000L);
        this.naverSearchServiceProvider = naverSearchServiceProvider;
        this.braveSearchServiceProvider = braveSearchServiceProvider;
        this.searchIoExecutorProvider = searchIoExecutorProvider;
        this.nightmareBreakerProvider = nightmareBreakerProvider;
        this.faultMaskMonitorProvider = faultMaskMonitorProvider;
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.searchWithTrace(..))")
    public Object aroundHybridSearchWithTrace(ProceedingJoinPoint pjp) throws Throwable {
        Object outObj = pjp.proceed();
        if (!(outObj instanceof NaverSearchService.SearchResult sr)) {
            return outObj;
        }

        List<String> out = (sr.snippets() == null) ? List.of() : sr.snippets();
        if (!out.isEmpty()) {
            return outObj;
        }

        // Feature flag (same as search(..) path)
        boolean enabled = getBoolean(true, "nova.orch.failsoft.hybrid-empty-fallback.enabled",
                "nova.orch.failsoft.hybridEmptyFallback.enabled",
                "nova.orch.failsoft.hybrid.web.empty-fallback.enabled",
                "nova.orch.failsoft.hybridWebEmptyFallback.enabled");
        if (!enabled) {
            return outObj;
        }

        // NOTE: HybridWebSearchProvider records await KPIs under web.await.events.*
        // (not web.await.*).
        // Keep backward-compatible fallbacks for older keys.
        long timeoutCount = Math.max(
                TraceStore.getLong("web.await.events.timeout.count"),
                TraceStore.getLong("web.await.timeout.count"));
        long timeoutHardCount = Math.max(
                TraceStore.getLong("web.await.events.timeout.hard.count"),
                TraceStore.getLong("web.await.timeout.hard.count"));
        long timeoutSoftCount = Math.max(
                TraceStore.getLong("web.await.events.timeout.soft.count"),
                TraceStore.getLong("web.await.timeout.soft.count"));
        long nonOkCount = Math.max(
                TraceStore.getLong("web.await.events.nonOk.count"),
                TraceStore.getLong("web.await.nonok.count"));
        long skippedCount = TraceStore.getLong("web.await.skipped.count");

        boolean braveSkippedSignal = isTrueish(TraceStore.get("web.brave.skipped"));
        boolean naverSkippedSignal = isTrueish(TraceStore.get("web.naver.skipped"));
        boolean partialDownSignal = isTrueish(TraceStore.get("web.down.partial"));
        boolean starvationSignal = isTrueish(TraceStore.get("web.starved")) || (TraceStore.getLong("outCount") == 0L);

        boolean strictDomainRequired = isTrueish(TraceStore.get("web.naver.strictDomainRequired"))
                || isTrueish(TraceStore.get("web.stage.strictDomainRequired"))
                || isTrueish(TraceStore.get("web.filter.strictDomainRequired"));

        boolean auxDegraded = isTrueish(TraceStore.get("aux.degraded"))
                || isTrueish(TraceStore.get("aux.down.partial"));
        boolean auxHardDown = isTrueish(TraceStore.get("aux.hardDown"))
                || isTrueish(TraceStore.get("aux.down.hard"));

        if (isTrueish(TraceStore.get("privacy.web.blocked"))) {
            if (TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.privacyBlocked.logged.searchWithTrace",
                    Boolean.TRUE) == null) {
                log.warn(
                        "[HybridEmptyFallback] privacy.web.blocked=true; skip empty-fallback (callSite=searchWithTrace)");
            }
            return outObj;
        }

        GuardContext ctxEarly = GuardContextHolder.get();
        boolean strictPlanHintEarly = (ctxEarly != null) && (ctxEarly.isOfficialOnly()
                || (ctxEarly.getDomainProfile() != null && !ctxEarly.getDomainProfile().isBlank()));

        boolean coldStartAggressive = isColdStart()
                && getBoolean(true,
                        "nova.orch.web.failsoft.hybrid-empty-fallback.coldstart.aggressive",
                        "nova.orch.web.failsoft.hybridEmptyFallback.coldstart.aggressive");

        if (!coldStartAggressive && (timeoutCount == 0 && nonOkCount == 0 && skippedCount == 0)
                && !braveSkippedSignal
                && !naverSkippedSignal
                && !partialDownSignal
                && !starvationSignal
                && !strictPlanHintEarly
                && !strictDomainRequired) {
            return outObj;
        }

        Object lastAwait = TraceStore.get("web.await.last");
        String lastStage = (lastAwait != null) ? String.valueOf(lastAwait) : "";
        String lastEngine = "";
        String lastCause = "";
        if (lastStage.contains("|")) {
            String[] parts = lastStage.split("\\|", 3);
            lastStage = parts.length > 0 ? parts[0] : "";
            lastEngine = parts.length > 1 ? parts[1] : "";
            lastCause = parts.length > 2 ? parts[2] : "";
        }

        Object[] args = pjp.getArgs();
        String query = (args.length > 0 && args[0] instanceof String s) ? s : null;
        int topK = (args.length > 1 && args[1] instanceof Integer i) ? i : 0;
        if (query == null || query.isBlank() || topK <= 0) {
            if (TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.argsInvalid.logged.searchWithTrace",
                    Boolean.TRUE) == null) {
                log.warn("[HybridEmptyFallback] searchWithTrace called but args invalid: query='{}', topK={}", query,
                        topK);
            }
            return outObj;
        }

        List<String> rescued = rescueEmptyHybridSearch(
                out,
                query,
                topK,
                ctxEarly,
                strictPlanHintEarly,
                timeoutCount,
                timeoutHardCount,
                timeoutSoftCount,
                nonOkCount,
                skippedCount,
                braveSkippedSignal,
                naverSkippedSignal,
                partialDownSignal,
                starvationSignal,
                strictDomainRequired,
                auxDegraded,
                auxHardDown,
                lastStage,
                lastEngine,
                lastCause,
                "searchWithTrace");

        if (rescued != null && !rescued.isEmpty()) {
            return new NaverSearchService.SearchResult(rescued, sr.trace());
        }
        return outObj;
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.search(..))")
    public Object aroundHybridSearch(ProceedingJoinPoint pjp) throws Throwable {
        Object out = pjp.proceed();
        if (!(out instanceof List<?> list)) {
            return out;
        }
        if (!list.isEmpty()) {
            return out;
        }

        boolean enabled = getBoolean(true,
                "nova.orch.web.failsoft.hybrid-empty-fallback.enabled",
                "nova.orch.web-failsoft.hybrid-empty-fallback.enabled",
                "nova.orch.web-failsoft.hybrid-empty-fallback-enabled");
        if (!enabled) {
            return out;
        }

        long timeoutCount = TraceStore.getLong("web.await.events.timeout.count");
        long timeoutHardCount = TraceStore.getLong("web.await.events.timeout.hard.count");
        long timeoutSoftCount = TraceStore.getLong("web.await.events.timeout.soft.count");
        long nonOkCount = TraceStore.getLong("web.await.events.nonOk.count");

        // Also treat provider "skip" signals (breaker open, cooldown, hedge-skip) as a
        // trigger for this fail-soft fallback.
        // Otherwise, simultaneous breaker/cooldown can cause empty-merge without any
        // timeout/nonOk counters.
        long skippedCount = TraceStore.getLong("web.await.skipped.count");
        boolean braveSkippedSignal = isTrueish(TraceStore.get("web.brave.skipped"));
        boolean naverSkippedSignal = isTrueish(TraceStore.get("web.naver.skipped"));

        // strictDomainRequired can starve results even without explicit timeout/nonOk
        // counters.
        // (e.g., strict domain + officialOnly + limited candidate pool)
        boolean strictDomainRequired = isTrueish(TraceStore.get("web.naver.strictDomainRequired"));

        boolean auxDegraded = isTrueish(TraceStore.get("aux.degraded"));
        boolean auxHardDown = isTrueish(TraceStore.get("aux.hardDown"));

        if (isTrueish(TraceStore.get("privacy.web.blocked"))) {
            logOnce("web.failsoft.hybridEmptyFallback.branchLog.privacyBlocked.once",
                    "[Nova] Hybrid websearch empty -> fallback skipped (privacy.web.blocked) (timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, auxDegraded={}, auxHardDown={}){}",
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                    auxDegraded, auxHardDown, LogCorrelation.suffix());
            return out;
        }

        // Only attempt if we have some evidence that the "empty" was caused by
        // timeout/cancellation/budget/breaker/cooldown.

        // Signals that "something went wrong" (timeouts / cancellations / skip) OR that
        // strict filters likely starved.
        boolean partialDownSignal = Boolean.TRUE.equals(TraceStore.get("orch.webPartialDown"))
                || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.anyDown"));

        boolean starvationSignal = isTrueish(TraceStore.get("web.domainFilter.starved"))
                || isTrueish(TraceStore.get("web.naver.filter.starvedByStrictDomain"));

        GuardContext ctxEarly = null;
        boolean strictPlanHintEarly = false;
        try {
            ctxEarly = GuardContextHolder.get();
            strictPlanHintEarly = ctxEarly != null && (ctxEarly.isOfficialOnly()
                    || (ctxEarly.getDomainProfile() != null && !ctxEarly.getDomainProfile().isBlank()));
        } catch (Throwable ignore) {
            // best-effort
        }

        boolean coldStartAggressive = isColdStart()
                && getBoolean(true,
                        "nova.orch.web.failsoft.hybrid-empty-fallback.coldstart.aggressive",
                        "nova.orch.web.failsoft.hybridEmptyFallback.coldstart.aggressive");

        if (!coldStartAggressive
                && timeoutCount <= 0 && nonOkCount <= 0 && skippedCount <= 0
                && !braveSkippedSignal
                && !naverSkippedSignal
                && !partialDownSignal
                && !starvationSignal
                && !strictPlanHintEarly
                && !strictDomainRequired) {
            return out;
        }

        Object[] args = pjp.getArgs();
        String query = (args != null && args.length > 0 && args[0] != null) ? String.valueOf(args[0]) : "";
        int topK = (args != null && args.length > 1 && args[1] instanceof Integer i) ? i : 5;
        if (query.isBlank() || topK <= 0) {
            return out;
        }

        // Extract lastStage/lastEngine/lastCause from TraceStore
        Object lastAwaitSearch = TraceStore.get("web.await.last");
        String lastStage = (lastAwaitSearch != null) ? String.valueOf(lastAwaitSearch) : "";
        String lastEngine = "";
        String lastCause = "";
        if (lastStage.contains("|")) {
            String[] parts = lastStage.split("\\|", 3);
            lastStage = parts.length > 0 ? parts[0] : "";
            lastEngine = parts.length > 1 ? parts[1] : "";
            lastCause = parts.length > 2 ? parts[2] : "";
        }

        // Cast list (already verified via instanceof List<?> list at line 213) to
        // List<String>
        @SuppressWarnings("unchecked")
        List<String> emptyList = (List<String>) list;

        return rescueEmptyHybridSearch(
                emptyList,
                query,
                topK,
                ctxEarly,
                strictPlanHintEarly,
                timeoutCount,
                timeoutHardCount,
                timeoutSoftCount,
                nonOkCount,
                skippedCount,
                braveSkippedSignal,
                naverSkippedSignal,
                partialDownSignal,
                starvationSignal,
                strictDomainRequired,
                auxDegraded,
                auxHardDown,
                lastStage,
                lastEngine,
                lastCause,
                "search");
    }

    private List<String> rescueEmptyHybridSearch(
            List<String> out,
            String query,
            int topK,
            GuardContext ctxEarly,
            boolean strictPlanHintEarly,
            long timeoutCount,
            long timeoutHardCount,
            long timeoutSoftCount,
            long nonOkCount,
            long skippedCount,
            boolean braveSkippedSignal,
            boolean naverSkippedSignal,
            boolean partialDownSignal,
            boolean starvationSignal,
            boolean strictDomainRequired,
            boolean auxDegraded,
            boolean auxHardDown,
            String lastStage,
            String lastEngine,
            String lastCause,
            String callSite) {

        TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.callSite", callSite);

        // Avoid repeated re-entry within same request (query-scoped).
        // If we've already rescued this query once in this request, reuse the cached
        // rescue payload
        // instead of returning empty again (prevents "merged=0" starvation loops).
        String qHash = queryHashForGate(query);
        String onceKey = "web.failsoft.hybridEmptyFallback.once." + qHash;
        String resultKey = "web.failsoft.hybridEmptyFallback.result." + qHash;

        Object prev = TraceStore.putIfAbsent(onceKey, Boolean.TRUE);
        if (prev != null) {
            Object cached = null;
            try {
                cached = TraceStore.get(resultKey);
            } catch (Throwable ignore) {
                cached = null;
            }
            if (cached instanceof List<?> cachedList && !cachedList.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> typedCachedList = (List<String>) cachedList;
                if (topK > 0 && typedCachedList.size() > topK) {
                    try {
                        return new ArrayList<>(typedCachedList.subList(0, topK));
                    } catch (Throwable ignore) {
                        return typedCachedList;
                    }
                }
                return typedCachedList;
            }
            return out;
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.queryHash", qHash);
            TraceStore.put("web.failsoft.hybridEmptyFallback.onceKey", onceKey);
            TraceStore.put("web.failsoft.hybridEmptyFallback.resultKey", resultKey);
        } catch (Throwable ignore) {
            // best-effort
        }

        // Note: lastStage/lastEngine/lastCause are passed as parameters from the
        // caller,
        // so we don't re-declare them here. The caller extracts them from TraceStore.
        // (Duplicate variable declarations removed to fix compile error)

        // Root-cause breadcrumb (best-effort): store which trigger(s) led us here.
        // This is extremely helpful when merged=0 happens because the upstream calls
        // were skipped (breaker_open) vs. timed out vs. strict-domain starvation.
        try {
            List<String> triggeredBy = new ArrayList<>();
            if (timeoutCount > 0) {
                triggeredBy.add("timeout");
            }
            if (nonOkCount > 0) {
                triggeredBy.add("non_ok");
            }
            if (skippedCount > 0) {
                triggeredBy.add("await_skipped");
            }
            if (braveSkippedSignal) {
                triggeredBy.add("brave_skipped");
            }
            if (naverSkippedSignal) {
                triggeredBy.add("naver_skipped");
            }
            if (partialDownSignal) {
                triggeredBy.add("partial_down");
            }
            if (starvationSignal) {
                triggeredBy.add("starvation_signal");
            }
            if (strictPlanHintEarly) {
                triggeredBy.add("plan_hint_strict");
            }
            if (strictDomainRequired) {
                triggeredBy.add("strict_domain_required");
            }
            if (auxHardDown) {
                triggeredBy.add("aux_hard_down");
            } else if (auxDegraded) {
                triggeredBy.add("aux_degraded");
            }
            if (lastCause != null && !lastCause.isBlank()) {
                triggeredBy.add("cause:" + safe(lastCause));
            }
            TraceStore.put("web.failsoft.hybridEmptyFallback.triggeredBy", triggeredBy);

            String root;
            if (braveSkippedSignal || naverSkippedSignal || skippedCount > 0) {
                root = "breaker_or_skip";
            } else if (timeoutCount > 0) {
                root = "timeout";
            } else if (nonOkCount > 0) {
                root = "non_ok";
            } else if (starvationSignal || strictDomainRequired || strictPlanHintEarly) {
                root = "strict_filter_starvation";
            } else if (partialDownSignal) {
                root = "partial_down";
            } else {
                root = "unknown";
            }

            TraceStore.put("web.failsoft.hybridEmptyFallback.rootCause", root);
            TraceStore.append("web.failsoft.hybridEmptyFallback.rootCause.history", root);
        } catch (Throwable ignore) {
            // best-effort
        }

        // Resolve dependencies (best-effort / fail-soft).
        NaverSearchService naver = naverSearchServiceProvider.getIfAvailable();
        BraveSearchService brave = braveSearchServiceProvider.getIfAvailable();
        ExecutorService exec = searchIoExecutorProvider.getIfAvailable();
        NightmareBreaker breaker = nightmareBreakerProvider.getIfAvailable();

        boolean braveFallbackEnabled = getBoolean(true,
                "nova.orch.web.failsoft.hybrid-empty-fallback.brave.enabled",
                "nova.orch.web-failsoft.hybrid-empty-fallback.brave.enabled");

        boolean relaxOfficialOnlyEnabled = getBoolean(true,
                "nova.orch.web.failsoft.hybrid-empty-fallback.relax-official-only",
                "nova.orch.web-failsoft.hybrid-empty-fallback.relax-official-only");

        long deadlineMs = clampMs(getLong(4500L,
                "nova.orch.web.failsoft.hybrid-empty-fallback.timeout-ms",
                "nova.orch.web.failsoft.hybrid-empty-fallback.deadline-ms",
                "nova.orch.web-failsoft.hybrid-empty-fallback.timeout-ms",
                "nova.orch.web-failsoft.hybrid-empty-fallback.deadline-ms"), 250L, 20000L);

        // Use an absolute deadline so a "relaxed officialOnly" second pass can't exceed
        // the same total budget.
        long globalDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);

        // Plan-hint strictness: officialOnly / domainProfile can starve Naver's
        // strictDomainRequired mode.
        GuardContext ctx = ctxEarly;
        boolean strictPlanHint = strictPlanHintEarly;

        boolean highRiskOrSensitive = false;
        try {
            highRiskOrSensitive = ctx != null && (ctx.isHighRiskQuery() || ctx.isSensitiveTopic());
        } catch (Throwable ignore) {
            highRiskOrSensitive = false;
        }

        // Basic trace markers for ops/debug.
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.mode", "completion-poll");
            TraceStore.put("web.failsoft.hybridEmptyFallback.deadlineMs", deadlineMs);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.stage", lastStage);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.engine", lastEngine);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.cause", lastCause);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.timeoutCount", timeoutCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.nonOkCount", nonOkCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.skippedCount", skippedCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.braveSkipped", braveSkippedSignal);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.naverSkipped", naverSkippedSignal);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.strictDomainRequired", strictDomainRequired);
            TraceStore.put("web.failsoft.hybridEmptyFallback.auxDegraded", auxDegraded);
            TraceStore.put("web.failsoft.hybridEmptyFallback.auxHardDown", auxHardDown);
            TraceStore.put("web.failsoft.hybridEmptyFallback.brave.enabled", braveFallbackEnabled);
            TraceStore.put("web.failsoft.hybridEmptyFallback.relaxOfficialOnly.enabled", relaxOfficialOnlyEnabled);
            TraceStore.put("web.failsoft.hybridEmptyFallback.relaxOfficialOnly.strictPlanHint", strictPlanHint);
            TraceStore.put("web.failsoft.hybridEmptyFallback.relaxOfficialOnly.highRiskOrSensitive",
                    highRiskOrSensitive);
        } catch (Throwable ignore) {
            // best-effort
        }

        // ---- Cold-start fail-soft 강화 (GLGO 초반 구간) ----
        // 목표:
        // - budget_exhausted(raw=0ms) -> hard timeout -> merged=0 체인을
        // budget_exhausted -> (soft-skip) -> cache-only / poll ladder로 빠르게 전환
        // - COOLDOWN 전환 직후(backup-query/soak) 중복 fallback 루프를 줄이기 위해
        // cache-only-first 및 queryHash 게이트 정규화 강화
        boolean coldStart = isColdStart();
        boolean budgetExhaustedRaw0 = isBudgetExhaustedRaw0();
        boolean forceSkipLiveProviders = false;
        if (coldStart) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart", true);
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.ageMs", coldStartAgeMs());
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.windowMs", coldStartWindowMs);
            } catch (Throwable ignore) {
            }
        }
        if (budgetExhaustedRaw0) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.budgetExhaustedRaw0", true);
            } catch (Throwable ignore) {
            }
        }

        // 브레이커가 닫혀있더라도 merged=0이 반복되는 케이스(예: strictDomain starvation, coldstart
        // timeout)
        // 에서 cache-only rescue(트레이스 풀 포함)를 더 먼저 시도.
        boolean coldStartCacheOnlyFirst = coldStart
                && getBoolean(true,
                        "nova.orch.web.failsoft.hybrid-empty-fallback.coldstart.cache-only-first.enabled",
                        "nova.orch.web.failsoft.hybridEmptyFallback.coldstart.cacheOnlyFirst.enabled")
                && (budgetExhaustedRaw0 || timeoutHardCount > 0 || nonOkCount > 0 || strictDomainRequired
                        || partialDownSignal);

        // budget_exhausted(raw=0ms) 상황에서는 completion-poll(라이브 네트워크) 반복보다
        // cache-only stepdown을 우선.
        boolean coldStartBudgetExhaustedSkipLive = coldStart
                && budgetExhaustedRaw0
                && getBoolean(true,
                        "nova.orch.web.failsoft.hybrid-empty-fallback.coldstart.budget-exhausted.skip-live",
                        "nova.orch.web.failsoft.hybridEmptyFallback.coldstart.budgetExhausted.skipLive");

        if (coldStartCacheOnlyFirst) {
            boolean braveOpen = isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_BRAVE);
            boolean naverOpen = isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_NAVER);
            boolean braveCooldown = brave != null && brave.isCoolingDown();

            boolean skipNaverNetwork = (naver == null) || naverOpen;
            boolean skipBraveNetwork = (brave == null) || !braveFallbackEnabled || braveOpen || braveCooldown;
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.cacheOnlyFirst", true);
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.braveOpen", braveOpen);
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.naverOpen", naverOpen);
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.braveCooldown", braveCooldown);
            } catch (Throwable ignore) {
            }

            FallbackAttempt cacheFirst = cacheOnlyRescue("coldstart_cache_first",
                    skipNaverNetwork, skipBraveNetwork,
                    naver, brave, query, topK);

            if (cacheFirst != null && cacheFirst.merged != null && !cacheFirst.merged.isEmpty()) {
                cacheRescuePayload(resultKey, cacheFirst.merged);
                return cacheFirst.merged;
            }
        }

        if (coldStartBudgetExhaustedSkipLive) {
            // Cold-start + raw=0ms budget exhaustion:
            // - Do NOT resubmit live network calls.
            // - Soft-skip and immediately stepdown to offline ladders (cache-only /
            // trace-pool).
            forceSkipLiveProviders = true;
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.budgetExhausted.skipLive", true);
                TraceStore.put("web.failsoft.hybridEmptyFallback.coldstart.budgetExhausted.skipLive.force", true);
            } catch (Throwable ignore) {
            }
            // Canonical skip markers (so merged=0 postmortem can distinguish budget
            // exhaustion
            // even when breakers are closed).
            markProviderSkippedBudgetExhaustedRaw0("brave");
            markProviderSkippedBudgetExhaustedRaw0("naver");
        }

        // 1) strict pass (current GuardContext)
        FallbackAttempt strict = attemptFallback("strict",
                naver, brave, exec, breaker,
                query, topK, globalDeadlineNs,
                braveFallbackEnabled,
                forceSkipLiveProviders,
                timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                lastStage, lastEngine, lastCause);

        if (strict != null && strict.merged != null && !strict.merged.isEmpty()) {
            cacheRescuePayload(resultKey, strict.merged);
            return strict.merged;
        } // 2) Demotion ladder when plan-hint strictness likely starved results.
          // strict -> relaxed (drop domainProfile only) -> nofilter_safe (drop
          // officialOnly + domainProfile)
        boolean haveTime = (globalDeadlineNs - System.nanoTime()) > 0L;
        boolean mayBenefit = strictPlanHint && ctx != null;

        // We never drop officialOnly for high-risk/sensitive topics.
        // Additionally, avoid dropping officialOnly when strictness was forced by
        // an upstream plan (i.e., the system asked for strict official sources),
        // or for medical "official info" queries.
        boolean strictForcedByPlan = isTrueish(TraceStore.get("web.naver.strictForcedByPlan"))
                || isTrueish(TraceStore.get("plan.web.strictForced"))
                || isTrueish(TraceStore.get("web.plan.strictForced"))
                || isTrueish(TraceStore.get("web.guard.strictForced"));
        boolean medicalOfficialInfoQuery = isTrueish(TraceStore.get("web.naver.isMedicalOfficialInfoQuery"))
                || isTrueish(TraceStore.get("web.query.medicalOfficialInfo"))
                || isTrueish(TraceStore.get("web.guard.medicalOfficialInfo"));

        // NOTE: strictDomainRequired alone should not permanently block NOFILTER_SAFE
        // rescue. It is frequently a plan hint for "official homepage" style queries
        // and can starve to merged=0.
        boolean allowDropOfficialOnly = !highRiskOrSensitive && !strictForcedByPlan && !medicalOfficialInfoQuery;

        if (relaxOfficialOnlyEnabled && haveTime && mayBenefit) {
            GuardContext original = ctx;
            try {
                // 2-a) relaxed: keep officialOnly, drop domainProfile (helps
                // strictDomainRequired starvation)
                boolean profilePresent = original.getDomainProfile() != null && !original.getDomainProfile().isBlank();
                if (profilePresent && (globalDeadlineNs - System.nanoTime()) > 0L) {
                    GuardContext relaxedProfile = original.copy();
                    relaxedProfile.setDomainProfile(null);
                    GuardContextHolder.set(relaxedProfile);
                    try {
                        TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.relaxed.attempted", true);
                    } catch (Throwable ignore) {
                    }

                    FallbackAttempt relaxedAttempt = attemptFallback("relaxed",
                            naver, brave, exec, breaker,
                            query, topK, globalDeadlineNs,
                            braveFallbackEnabled,
                            forceSkipLiveProviders,
                            timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount,
                            braveSkippedSignal,
                            lastStage, lastEngine, lastCause);

                    if (relaxedAttempt != null && relaxedAttempt.merged != null && !relaxedAttempt.merged.isEmpty()) {
                        try {
                            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.relaxed.used", true);
                        } catch (Throwable ignore) {
                        }
                        cacheRescuePayload(resultKey, relaxedAttempt.merged);
                        return relaxedAttempt.merged;
                    }
                }

                // 2-b) nofilter_safe: drop officialOnly + domainProfile (last-resort, but keeps
                // the system alive)
                if (allowDropOfficialOnly && (globalDeadlineNs - System.nanoTime()) > 0L) {
                    GuardContext nofilter = original.copy();
                    nofilter.setOfficialOnly(false);
                    nofilter.setDomainProfile(null);
                    GuardContextHolder.set(nofilter);
                    try {
                        TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.attempted", true);
                    } catch (Throwable ignore) {
                    }

                    FallbackAttempt nofilterAttempt = attemptFallback("nofilter_safe",
                            naver, brave, exec, breaker,
                            query, topK, globalDeadlineNs,
                            braveFallbackEnabled,
                            forceSkipLiveProviders,
                            timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount,
                            braveSkippedSignal,
                            lastStage, lastEngine, lastCause);

                    if (nofilterAttempt != null && nofilterAttempt.merged != null
                            && !nofilterAttempt.merged.isEmpty()) {
                        try {
                            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.used", true);
                        } catch (Throwable ignore) {
                        }

                        List<String> merged = maybeFilterLowTrustForNofilterSafe(nofilterAttempt.merged, topK);
                        cacheRescuePayload(resultKey, merged);
                        return merged;
                    }
                } else {
                    try {
                        TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.skipped", true);
                        String blockedReason = highRiskOrSensitive ? "highRiskOrSensitive"
                                : (strictForcedByPlan ? "strictForcedByPlan"
                                        : (medicalOfficialInfoQuery ? "medicalOfficialInfoQuery"
                                                : (strictDomainRequired ? "strictDomainRequired" : "unknown")));
                        TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.skipped.reason",
                                blockedReason);
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                }
            } catch (Throwable ignore) {
                // fail-soft
            } finally {
                try {
                    GuardContextHolder.set(original);
                } catch (Throwable ignore) {
                    // best-effort
                }
            }
        }

        // 3) Final safety net: cache-only probe ladder + rescueMerge.
        // This is intentionally attempted even when providers were "available" but
        // yielded empty due to
        // timeouts/budget/filters; it breaks the "merged=0" starvation loop without
        // adding network load.
        try {
            boolean skipNaverNetwork = (naver == null) || !naver.isEnabled()
                    || isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_NAVER);
            boolean skipBraveNetwork = (brave == null) || !brave.isEnabled()
                    || isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_BRAVE)
                    || (brave != null && brave.isCoolingDown());

            FallbackAttempt cacheOnlyFinal = cacheOnlyRescue("final", skipNaverNetwork, skipBraveNetwork,
                    naver, brave, query, topK);
            if (cacheOnlyFinal != null && cacheOnlyFinal.merged != null && !cacheOnlyFinal.merged.isEmpty()) {
                try {
                    TraceStore.put("web.failsoft.hybridEmptyFallback.finalCacheOnly.used", true);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.finalCacheOnly.skipNaverNetwork",
                            skipNaverNetwork);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.finalCacheOnly.skipBraveNetwork",
                            skipBraveNetwork);
                } catch (Throwable ignore) {
                    // best-effort
                }
                cacheRescuePayload(resultKey, cacheOnlyFinal.merged);
                return cacheOnlyFinal.merged;
            }

            // Optional: if safe to drop officialOnly, retry cache-only once under a
            // nofilter_safe context.
            // This increases trace-pool rescue hit rate (stage filter is relaxed) while
            // still staying offline.
            if (allowDropOfficialOnly && ctx != null) {
                GuardContext original = ctx;
                try {
                    GuardContext nofilter = original.copy();
                    nofilter.setOfficialOnly(false);
                    nofilter.setDomainProfile(null);
                    GuardContextHolder.set(nofilter);
                    FallbackAttempt cacheOnlyNofilter = cacheOnlyRescue("final_nofilter_safe",
                            true, true, naver, brave, query, topK);
                    if (cacheOnlyNofilter != null && cacheOnlyNofilter.merged != null
                            && !cacheOnlyNofilter.merged.isEmpty()) {
                        try {
                            TraceStore.put("web.failsoft.hybridEmptyFallback.finalCacheOnly.nofilterSafe.used", true);
                        } catch (Throwable ignore) {
                            // best-effort
                        }
                        cacheRescuePayload(resultKey, cacheOnlyNofilter.merged);
                        return cacheOnlyNofilter.merged;
                    }
                } catch (Throwable ignore) {
                    // best-effort
                } finally {
                    try {
                        GuardContextHolder.set(original);
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                }
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        // No rescue.
        recordNoRescueBreadcrumbs(callSite, query, topK,
                timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount,
                braveSkippedSignal, naverSkippedSignal, partialDownSignal, starvationSignal,
                strictPlanHint, strictDomainRequired, lastStage, lastEngine, lastCause);
        return out;

    }

    private void recordNoRescueBreadcrumbs(
            String callSite,
            String query,
            int topK,
            long timeoutCount,
            long timeoutHardCount,
            long timeoutSoftCount,
            long nonOkCount,
            long skippedCount,
            boolean braveSkippedSignal,
            boolean naverSkippedSignal,
            boolean partialDownSignal,
            boolean starvationSignal,
            boolean strictPlanHint,
            boolean strictDomainRequired,
            String lastStage,
            String lastEngine,
            String lastCause) {

        boolean budgetExhaustedRaw0 = false;
        boolean rateLimitOrCooldown = false;
        try {
            budgetExhaustedRaw0 = isBudgetExhaustedRaw0();
            rateLimitOrCooldown = isRateLimitOrCooldownSignal("brave") || isRateLimitOrCooldownSignal("naver");
        } catch (Throwable ignore) {
            budgetExhaustedRaw0 = false;
            rateLimitOrCooldown = false;
        }

        String clazz = classifyNoRescue(timeoutCount, timeoutHardCount, timeoutSoftCount,
                nonOkCount, skippedCount,
                braveSkippedSignal, naverSkippedSignal, partialDownSignal, starvationSignal,
                strictPlanHint, strictDomainRequired,
                budgetExhaustedRaw0, rateLimitOrCooldown);

        // Best-effort trace breadcrumbs (for GLGO style postmortem).
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.class", clazz);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.callSite", safe(callSite));
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.topK", topK);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.timeoutAll", timeoutCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.timeoutHard", timeoutHardCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.timeoutSoft", timeoutSoftCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.nonOk", nonOkCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.skipped", skippedCount);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.braveSkippedSignal", braveSkippedSignal);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.naverSkippedSignal", naverSkippedSignal);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.partialDown", partialDownSignal);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.starvation", starvationSignal);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.strictPlanHint", strictPlanHint);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.strictDomainRequired", strictDomainRequired);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.budgetExhaustedRaw0", budgetExhaustedRaw0);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.rateLimitOrCooldown", rateLimitOrCooldown);
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.lastStage", safe(lastStage));
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.lastEngine", safe(lastEngine));
            TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.lastCause", safe(lastCause));

            // Final classification marker for postmortem scripts (extract true terminal
            // merged=0).
            TraceStore.put("end.classification", "merged0." + clazz);
            TraceStore.put("end.classification.callSite", safe(callSite));
            TraceStore.put("end.classification.merged", 0);
            TraceStore.put("end.classification.queryHash", queryHashForGate(query));

            // Coldstart markers (if enabled).
            if (isColdStart()) {
                TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.coldStart", true);
                TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.coldStartAgeMs", coldStartAgeMs());
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        logOnce("web.failsoft.hybridEmptyFallback.branchLog.noRescue."
                + safe(callSite) + "." + safe(clazz) + ".once",
                "[Nova] Hybrid websearch empty -> no rescue (class={}, callSite={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkippedSignal={}, naverSkippedSignal={}, partialDown={}, starvation={}, strictPlanHint={}, strictDomainRequired={}, lastStage={}, lastEngine={}, lastCause={}, topK={}, q={}){}",
                clazz,
                safe(callSite),
                timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount,
                braveSkippedSignal, naverSkippedSignal, partialDownSignal, starvationSignal,
                strictPlanHint, strictDomainRequired,
                safe(lastStage), safe(lastEngine), safe(lastCause),
                topK, SafeRedactor.redact(query), LogCorrelation.suffix());
    }

    private static String classifyNoRescue(
            long timeoutCount,
            long timeoutHardCount,
            long timeoutSoftCount,
            long nonOkCount,
            long skippedCount,
            boolean braveSkippedSignal,
            boolean naverSkippedSignal,
            boolean partialDownSignal,
            boolean starvationSignal,
            boolean strictPlanHint,
            boolean strictDomainRequired,
            boolean budgetExhaustedRaw0,
            boolean rateLimitOrCooldown) {

        if (budgetExhaustedRaw0) {
            return "budget_exhausted_raw0";
        }

        if (rateLimitOrCooldown) {
            return "rate_limit_or_cooldown";
        }

        if (strictDomainRequired || strictPlanHint) {
            return "strict_filter_starve";
        }

        if (timeoutHardCount > 0L || timeoutSoftCount > 0L || timeoutCount > 0L || nonOkCount > 0L) {
            return "timeouts_or_nonok";
        }

        if (skippedCount > 0L || braveSkippedSignal || naverSkippedSignal || partialDownSignal) {
            return "skipped_or_down";
        }

        if (starvationSignal) {
            return "starvation";
        }

        return "zero_hit";
    }

    private FallbackAttempt attemptFallback(
            String pass,
            NaverSearchService naver,
            BraveSearchService brave,
            ExecutorService exec,
            NightmareBreaker breaker,
            String query,
            int topK,
            long deadlineNs,
            boolean braveFallbackEnabled,
            boolean forceSkipLiveProviders,
            long timeoutCount,
            long timeoutHardCount,
            long timeoutSoftCount,
            long nonOkCount,
            long skippedCount,
            boolean braveSkippedSignal,
            String lastStage,
            String lastEngine,
            String lastCause) {

        // If the call stack already observed rate-limit/cooldown (or cold-start budget
        // exhaustion), do not resubmit live network calls.
        boolean naverRateLimitOrCooldown = forceSkipLiveProviders || isRateLimitOrCooldownSignal("naver");
        boolean braveRateLimitOrCooldown = forceSkipLiveProviders || isRateLimitOrCooldownSignal("brave");

        boolean skipNaver = forceSkipLiveProviders
                || (naver == null) || !naver.isEnabled()
                || isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_NAVER)
                || naverRateLimitOrCooldown;
        boolean skipBrave = forceSkipLiveProviders
                || !braveFallbackEnabled
                || (brave == null) || !brave.isEnabled()
                || isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_BRAVE)
                || (brave != null && brave.isCoolingDown())
                || braveRateLimitOrCooldown;
        if (skipNaver && skipBrave) {
            // Both providers are unavailable for live calls. As a last resort, attempt
            // cache-only escape.
            FallbackAttempt cacheOnly = cacheOnlyRescue(pass, skipNaver, skipBrave, naver, brave, query, topK);
            if (cacheOnly != null && cacheOnly.merged != null && !cacheOnly.merged.isEmpty()) {
                return cacheOnly;
            }

            logOnce("web.failsoft.hybridEmptyFallback.branchLog.allSkipped." + safe(pass) + ".once",
                    "[Nova] Hybrid websearch empty -> fallback skipped (both providers unavailable) (pass={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, lastStage={}, lastEngine={}, lastCause={}, topK={}, q={}){}",
                    pass,
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                    safe(lastStage), safe(lastEngine), safe(lastCause),
                    topK, SafeRedactor.redact(query), LogCorrelation.suffix());
            return FallbackAttempt.empty(pass, skipNaver, skipBrave);
        }

        // Best-effort trace markers.
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.pass", pass);
            TraceStore.put("web.failsoft.hybridEmptyFallback.skipNaver", skipNaver);
            TraceStore.put("web.failsoft.hybridEmptyFallback.skipBrave", skipBrave);
        } catch (Throwable ignore) {
            // best-effort
        }

        logOnce("web.failsoft.hybridEmptyFallback.branchLog.attempt." + safe(pass) + ".once",
                "[Nova] Hybrid websearch empty -> attempting completion-poll fallback (pass={}, deadlineMs~={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, lastStage={}, lastEngine={}, lastCause={}, skipNaver={}, skipBrave={}, topK={}, q={}){}",
                pass,
                Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())),
                timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                safe(lastStage), safe(lastEngine), safe(lastCause),
                skipNaver, skipBrave,
                topK, SafeRedactor.redact(query), LogCorrelation.suffix());

        long startedMs = System.currentTimeMillis();

        ProviderResult naverRes = ProviderResult.empty("naver", "skipped", 0L);
        ProviderResult braveRes = ProviderResult.empty("brave", "skipped", 0L);

        // Naver sync call: allow longer block only when Brave is NOT running in
        // parallel.
        long maxNaverBlockMs = skipBrave ? 5000L : 2000L;

        if (exec == null) {
            // Very defensive fallback: if we cannot schedule, call synchronously with tight
            // timeouts.
            // (Still never uses cancel(true) here.)
            if (!skipNaver && naver != null) {
                naverRes = callNaver(naver, query, topK, deadlineNs, maxNaverBlockMs);
            }
            if (!skipBrave && brave != null) {
                braveRes = callBrave(brave, query, topK);
            }
        } else {
            ExecutorCompletionService<ProviderResult> ecs = new ExecutorCompletionService<>(exec);
            List<Future<ProviderResult>> futures = new ArrayList<>(2);
            int submitted = 0;

            if (!skipNaver && naver != null) {
                futures.add(ecs.submit(() -> callNaver(naver, query, topK, deadlineNs, maxNaverBlockMs)));
                submitted++;
            }
            if (!skipBrave && brave != null) {
                futures.add(ecs.submit(() -> callBrave(brave, query, topK)));
                submitted++;
            }

            int remaining = submitted;
            Set<String> mergedSoFar = new LinkedHashSet<>();

            // When the deadline is already exhausted, allow one short grace window to
            // pick up near-complete tasks (min-live-budget). This mitigates starvation
            // caused by razor-thin budgeting and scheduler jitter.
            boolean usedMinLiveBudget = false;
            long minLiveBudgetNs = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, completionPollMinLiveBudgetMs));

            int pollCount = 0;
            long pollWaitNs = 0L;
            long minLiveBudgetWaitNs = 0L;
            boolean minLiveBudgetYieldedCompletion = false;

            while (remaining > 0) {
                boolean minBudgetThisPoll = false;
                long remainNs = deadlineNs - System.nanoTime();
                if (remainNs <= 0L) {
                    if (!usedMinLiveBudget && minLiveBudgetNs > 0L) {
                        usedMinLiveBudget = true;
                        remainNs = minLiveBudgetNs;
                        minBudgetThisPoll = true;
                        try {
                            TraceStore.put("web.failsoft.hybridEmptyFallback.completionPoll.minLiveBudget.used", true);
                            TraceStore.put("web.failsoft.hybridEmptyFallback.completionPoll.minLiveBudget.ms",
                                    completionPollMinLiveBudgetMs);
                        } catch (Throwable ignore) {
                            // best-effort
                        }
                    } else {
                        break;
                    }
                }
                long pollStartNs = System.nanoTime();
                pollCount++;
                Future<ProviderResult> done;
                try {
                    done = ecs.poll(remainNs, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ie) {
                    // Fail-soft: do not propagate interrupt poisoning to request threads.
                    Thread.interrupted();
                    try {
                        TraceStore.inc("web.failsoft.hybridEmptyFallback.interrupted.count");
                    } catch (Throwable ignore) {
                    }
                    break;
                }
                long waitedNs = System.nanoTime() - pollStartNs;
                pollWaitNs += waitedNs;
                if (minBudgetThisPoll) {
                    minLiveBudgetWaitNs += waitedNs;
                }
                if (done == null) {
                    break;
                }
                if (minBudgetThisPoll) {
                    minLiveBudgetYieldedCompletion = true;
                }
                ProviderResult r;
                try {
                    r = done.get();
                } catch (Throwable t) {
                    r = ProviderResult.empty("unknown", t.getClass().getSimpleName(), 0L);
                }
                remaining--;

                if (r != null) {
                    if ("naver".equalsIgnoreCase(r.provider)) {
                        naverRes = r;
                    } else if ("brave".equalsIgnoreCase(r.provider)) {
                        braveRes = r;
                    }
                    // incremental merge to allow early exit
                    mergedSoFar = mergeInto(mergedSoFar, r.snippets, topK);
                    if (mergedSoFar.size() >= topK) {
                        break;
                    }
                }
            }

            // Best-effort cancel remaining tasks WITHOUT interrupt.
            for (Future<ProviderResult> f : futures) {
                if (f == null || f.isDone()) {
                    continue;
                }
                try {
                    f.cancel(false);
                } catch (Throwable ignore) {
                    // best-effort
                }
            }
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.completionPoll.pollCount", pollCount);
                TraceStore.put("web.failsoft.hybridEmptyFallback.completionPoll.waitedMs",
                        TimeUnit.NANOSECONDS.toMillis(pollWaitNs));
                if (usedMinLiveBudget) {
                    TraceStore.put("web.failsoft.hybridEmptyFallback.completionPoll.minLiveBudget.waitedMs",
                            TimeUnit.NANOSECONDS.toMillis(minLiveBudgetWaitNs));
                    TraceStore.put("web.failsoft.hybridEmptyFallback.completionPoll.minLiveBudget.yieldedCompletion",
                            minLiveBudgetYieldedCompletion);
                }
            } catch (Throwable ignore) {
                // best-effort
            }

        }

        List<String> merged = mergeAndLimit(
                naverRes == null ? Collections.emptyList() : naverRes.snippets,
                braveRes == null ? Collections.emptyList() : braveRes.snippets,
                topK);

        // Cache-only escape hatch (STRICT -> RELAXED -> NOFILTER_SAFE ladder lives
        // inside providers).
        if (merged.isEmpty()) {
            FallbackAttempt cacheOnly = cacheOnlyRescue(pass, skipNaver, skipBrave, naver, brave, query, topK);
            if (cacheOnly != null && cacheOnly.merged != null && !cacheOnly.merged.isEmpty()) {
                merged = cacheOnly.merged;
                naverRes = cacheOnly.naver;
                braveRes = cacheOnly.brave;
                try {
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.topup", true);
                } catch (Throwable ignore) {
                }
            }
        }

        long tookMs = Math.max(0L, System.currentTimeMillis() - startedMs);

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.used", !merged.isEmpty());
            TraceStore.put("web.failsoft.hybridEmptyFallback.tookMs", tookMs);
            TraceStore.put("web.failsoft.hybridEmptyFallback.merged.count", merged.size());
            TraceStore.put("web.failsoft.hybridEmptyFallback.naver.count", safeSize(naverRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.naver.status", safeStatus(naverRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.naver.tookMs", safeTook(naverRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.brave.count", safeSize(braveRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.brave.status", safeStatus(braveRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.brave.tookMs", safeTook(braveRes));
        } catch (Throwable ignore) {
            // best-effort
        }

        if (merged.isEmpty()) {
            logOnce("web.failsoft.hybridEmptyFallback.branchLog.fallbackEmpty." + safe(pass) + ".once",
                    "[Nova] Hybrid websearch empty -> completion-poll fallback returned empty (pass={}, tookMs={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, lastStage={}, lastEngine={}, lastCause={}, naver.count={}, brave.count={}, topK={}, q={}){}",
                    pass, tookMs,
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                    safe(lastStage), safe(lastEngine), safe(lastCause),
                    safeSize(naverRes), safeSize(braveRes),
                    topK, SafeRedactor.redact(query), LogCorrelation.suffix());
            return new FallbackAttempt(pass, Collections.emptyList(), naverRes, braveRes, tookMs, skipNaver, skipBrave);
        }

        log.warn(
                "[Nova] Hybrid websearch empty after cancellation; applied completion-poll fallback (pass={}, merged={}, naver={}, brave={}, tookMs={}, q={}){}",
                pass, merged.size(), safeSize(naverRes), safeSize(braveRes), tookMs, SafeRedactor.redact(query),
                LogCorrelation.suffix());

        return new FallbackAttempt(pass, merged, naverRes, braveRes, tookMs, skipNaver, skipBrave);
    }

    private ProviderResult callNaver(NaverSearchService naver, String query, int topK, long deadlineNs,
            long maxBlockMs) {
        long st = System.nanoTime();
        try {
            // Derive a tight block timeout from the remaining budget window.
            long remainMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()));
            // NOTE: NaverSearchService's sync facade accepts an override timeout. We
            // intentionally
            // keep it within the remaining budget window (and avoid the default 250ms+
            // base)
            // so fallback workers don't get stuck for seconds when the request is already
            // starved.
            long maxMs = clampMs(maxBlockMs, 250L, 10000L);
            long blockMs = clampMs(Math.max(0L, remainMs - 30L), 50L, maxMs);
            Duration blockTimeout = Duration.ofMillis(blockMs);
            List<String> r = naver.searchSnippetsSync(query, topK, blockTimeout);
            long took = Math.max(0L, (System.nanoTime() - st) / 1_000_000L);
            return ProviderResult.of("naver", safeList(r), "ok", took);
        } catch (Throwable t) {
            long took = Math.max(0L, (System.nanoTime() - st) / 1_000_000L);
            recordProviderError("naver", t);
            return ProviderResult.empty("naver", t.getClass().getSimpleName(), took);
        }
    }

    private ProviderResult callBrave(BraveSearchService brave, String query, int topK) {
        long st = System.nanoTime();
        try {
            int braveK = Math.min(Math.max(topK, 5), 20);
            BraveSearchResult meta = brave.searchWithMeta(query, braveK);
            long took = Math.max(0L, (System.nanoTime() - st) / 1_000_000L);

            String status = (meta == null || meta.status() == null) ? "null" : meta.status().name();
            List<String> snippets = (meta == null) ? Collections.emptyList() : safeList(meta.snippets());
            return ProviderResult.of("brave", snippets, status, took);
        } catch (Throwable t) {
            long took = Math.max(0L, (System.nanoTime() - st) / 1_000_000L);
            recordProviderError("brave", t);
            return ProviderResult.empty("brave", t.getClass().getSimpleName(), took);
        }
    }

    private void recordProviderError(String provider, Throwable t) {
        if (provider == null) {
            provider = "provider";
        }
        try {
            TraceStore.inc("web.failsoft.hybridEmptyFallback.error.count");
            TraceStore.inc("web.failsoft.hybridEmptyFallback.error." + provider + ".count");
            TraceStore.put("web.failsoft.hybridEmptyFallback.error.last.provider", provider);
            TraceStore.put("web.failsoft.hybridEmptyFallback.error.last", t.getClass().getSimpleName());
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            FaultMaskingLayerMonitor monitor = faultMaskMonitorProvider.getIfAvailable();
            if (monitor != null) {
                monitor.record("web.failsoft.hybridEmptyFallback", t, provider);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private static Callable<ProviderResult> wrap(String provider, Callable<ProviderResult> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            ProviderResult r = task.call();
            if (r == null) {
                return ProviderResult.empty(provider, "null", 0L);
            }
            if (r.provider == null || r.provider.isBlank() || "unknown".equalsIgnoreCase(r.provider)) {
                return new ProviderResult(provider, r.snippets, r.status, r.tookMs);
            }
            return r;
        };
    }

    private FallbackAttempt cacheOnlyRescue(
            String pass,
            boolean skipNaverNetwork,
            boolean skipBraveNetwork,
            NaverSearchService naver,
            BraveSearchService brave,
            String query,
            int topK) {

        // Cache-only rescue is intentionally allowed even when live network calls are
        // skipped
        // due to breaker OPEN / cooldown, because it never hits the network.
        long startedMs = System.currentTimeMillis();

        boolean officialOnly = false;
        try {
            GuardContext gc = GuardContextHolder.get();
            officialOnly = (gc != null && gc.isOfficialOnly());
        } catch (Throwable ignore) {
            // best-effort
        }

        // Per-query once gate (request-scoped): avoid re-running the probe ladder
        // multiple times
        // in a demotion chain, while still allowing a second attempt when officialOnly
        // changes.
        String mpIntent = null;
        boolean zero100 = false;
        try {
            Object z = TraceStore.get("zero100.enabled");
            zero100 = (z != null && String.valueOf(z).equalsIgnoreCase("true"));
            if (zero100) {
                Object m = TraceStore.get("zero100.mpIntent");
                mpIntent = (m == null) ? null : String.valueOf(m);
            }
        } catch (Throwable ignore) {
            zero100 = false;
            mpIntent = null;
        }

        String qHash = queryHashForGate((zero100 && mpIntent != null && !mpIntent.isBlank()) ? mpIntent : query);
        String cacheOnceKey = "web.failsoft.hybridEmptyFallback.cacheOnly.once." + (officialOnly ? "o1" : "o0") + "."
                + qHash;
        try {
            Object prevCache = TraceStore.putIfAbsent(cacheOnceKey, Boolean.TRUE);
            if (prevCache != null) {
                try {
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.once.reenter", true);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.queryHash", qHash);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.onceKey", cacheOnceKey);
                } catch (Throwable ignore2) {
                    // best-effort
                }
                return null;
            }
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.queryHash", qHash);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.onceKey", cacheOnceKey);
        } catch (Throwable ignore) {
            // best-effort
        }

        // Tunables (kept conservative; this path should stay FAST)
        int probeMax = (int) Math.max(1L, Math.min(12L, getLong(6L,
                "nova.orch.web.failsoft.hybrid-empty-fallback.cache-only.probe-max",
                "nova.orch.web.failsoft.hybrid-empty-fallback.cacheOnly.probeMax",
                "nova.orch.web.failsoft.hybrid-empty-fallback.cacheOnly.probe-max")));

        boolean rescueMergeEnabled = getBoolean(true,
                "nova.orch.web.failsoft.hybrid-empty-fallback.rescue-merge.enabled",
                "nova.orch.web.failsoft.hybrid-empty-fallback.rescueMerge.enabled");

        int rescueMergeMax = (int) Math.max(1L, Math.min(20L, getLong(5L,
                "nova.orch.web.failsoft.hybrid-empty-fallback.rescue-merge.max",
                "nova.orch.web.failsoft.hybrid-empty-fallback.rescueMerge.max")));

        List<String> probeQueries = buildCacheProbeQueries(query, probeMax);

        // Zero100: expand cache-only probes using Mp-Intent anchor variants to improve cache hit
        // rate when QueryTransformer/self-ask changes the query string.
        try {
            boolean anchorProbeEnabled = getBoolean(true,
                    "nova.orch.zero100.anchor-probe.enabled",
                    "nova.orch.zero100.anchorProbeEnabled");
            if (zero100 && anchorProbeEnabled && mpIntent != null && !mpIntent.isBlank()
                    && !mpIntent.equalsIgnoreCase(query)) {
                int extra = (int) Math.max(0L, Math.min(8L, getLong(3L,
                        "nova.orch.zero100.anchor-probe.extra-candidates",
                        "nova.orch.zero100.cacheProbeExtraAnchorCandidates")));

                java.util.LinkedHashSet<String> accQ = new java.util.LinkedHashSet<>();

                String clampMode = safe(String.valueOf(TraceStore.get("zero100.clampMode")));
                boolean precisionFirst = (clampMode != null && clampMode.toUpperCase(java.util.Locale.ROOT).contains("PRECISION"));

                if (precisionFirst) {
                    accQ.addAll(buildCacheProbeQueries(mpIntent, Math.max(1, extra)));
                    accQ.addAll(probeQueries);
                } else {
                    accQ.addAll(probeQueries);
                    accQ.addAll(buildCacheProbeQueries(mpIntent, Math.max(1, extra)));
                }

                probeQueries = new java.util.ArrayList<>(accQ);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.mpIntent", mpIntent);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.zero100.anchorProbe.extra", extra);
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probeMax", probeMax);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probeQueries", probeQueries);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.count",
                    probeQueries == null ? 0 : probeQueries.size());
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.enabled", rescueMergeEnabled);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.max", rescueMergeMax);
        } catch (Throwable ignore) {
            // best-effort
        }

        List<String> merged = Collections.emptyList();

        // Provider-resolved cache hits for trace visibility (first-hit semantics)
        List<String> naverHit = Collections.emptyList();
        List<String> braveHit = Collections.emptyList();
        String hitQuery = null;
        int hitProbeIdx = -1;

        // We merge across probes to reduce cache-key fragmentation.
        LinkedHashSet<String> acc = new LinkedHashSet<>();

        for (int probeIdx = 0; probeIdx < probeQueries.size(); probeIdx++) {
            String q = probeQueries.get(probeIdx);
            if (acc.size() >= topK) {
                break;
            }
            if (q == null || q.isBlank()) {
                continue;
            }

            List<String> naverCached = Collections.emptyList();
            List<String> braveCached = Collections.emptyList();

            if (naver != null) {
                try {
                    naverCached = safeList(naver.searchSnippetsCacheOnly(q, topK));
                } catch (Throwable ignore) {
                    naverCached = Collections.emptyList();
                }
            }
            if (brave != null) {
                try {
                    braveCached = safeList(brave.searchCacheOnly(q, topK));
                } catch (Throwable ignore) {
                    braveCached = Collections.emptyList();
                }
            }

            boolean any = (naverCached != null && !naverCached.isEmpty())
                    || (braveCached != null && !braveCached.isEmpty());

            if (any && hitQuery == null) {
                hitQuery = q;
                hitProbeIdx = probeIdx;
                naverHit = (naverCached == null) ? Collections.emptyList() : naverCached;
                braveHit = (braveCached == null) ? Collections.emptyList() : braveCached;
            }

            mergeInto(acc, naverCached, topK);
            mergeInto(acc, braveCached, topK);
        }

        if (!acc.isEmpty()) {
            merged = new ArrayList<>(acc);
        }

        boolean usedRescueMerge = false;

        // Last-resort: request-local trace pool rescue merge (no network, no new
        // evidence)
        if ((merged == null || merged.isEmpty()) && rescueMergeEnabled) {
            List<String> rescued = tracePoolRescueMerge(query, topK, officialOnly, rescueMergeMax);
            if (rescued != null && !rescued.isEmpty()) {
                merged = rescued;
                usedRescueMerge = true;
                if (hitQuery == null) {
                    hitQuery = "__tracePool__";
                }
            }

            // Zero100: if the current query misses, attempt the same rescueMerge once with Mp-Intent.
            if ((merged == null || merged.isEmpty()) && zero100
                    && mpIntent != null && !mpIntent.isBlank()
                    && !mpIntent.equalsIgnoreCase(query)
                    && getBoolean(true,
                            "nova.orch.zero100.secondary-tracepool-rescue.enabled",
                            "nova.orch.zero100.secondaryTracePoolRescueEnabled")) {
                List<String> rescued2 = tracePoolRescueMerge(mpIntent, topK, officialOnly, rescueMergeMax);
                if (rescued2 != null && !rescued2.isEmpty()) {
                    merged = rescued2;
                    usedRescueMerge = true;
                    hitQuery = "__tracePool_mpIntent__";
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.secondary.used", true);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.secondary.query", mpIntent);
                } else {
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.secondary.used", false);
                }
            }
        }

        if (merged == null || merged.isEmpty()) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit", false);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit.query", safe(hitQuery));
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.hitProbeIdx", hitProbeIdx);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.usedQuery", safe(hitQuery));
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", usedRescueMerge);
                TraceStore.put("rescueMerge.used", usedRescueMerge);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.breadcrumb", "miss");
            } catch (Throwable ignore) {
                // best-effort
            }
            return null;
        }

        ProviderResult naverRes = ProviderResult.of("naver", naverHit,
                (naverHit == null || naverHit.isEmpty()) ? "cache_miss" : "cache_only", 0L);
        ProviderResult braveRes = ProviderResult.of("brave", braveHit,
                (braveHit == null || braveHit.isEmpty()) ? "cache_miss" : "cache_only", 0L);

        long tookMs = Math.max(0L, System.currentTimeMillis() - startedMs);

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.used", true);
            TraceStore.put("cacheOnly.used", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.pass", pass);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit.query", hitQuery == null ? "" : hitQuery);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.hitProbeIdx", hitProbeIdx);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.usedQuery",
                    hitQuery == null ? "" : hitQuery);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.breadcrumb",
                    usedRescueMerge ? "rescueMerge" : "probeHit");
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", merged.size());
            TraceStore.put("cacheOnly.merged.count", merged.size());
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.naver.count", safeSize(naverRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.brave.count", safeSize(braveRes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.skipNaverNetwork", skipNaverNetwork);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.skipBraveNetwork", skipBraveNetwork);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.officialOnly", officialOnly);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", usedRescueMerge);
            TraceStore.put("rescueMerge.used", usedRescueMerge);
            if (usedRescueMerge) {
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.count", merged.size());
            }
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.tookMs", tookMs);
        } catch (Throwable ignore) {
            // best-effort
        }

        logOnce("web.failsoft.hybridEmptyFallback.branchLog.cacheOnly." + safe(pass) + ".once",
                "[Nova] Hybrid websearch empty -> cache-only rescue hit (pass={}, merged={}, naver={}, brave={}, hitQuery={}, hitProbeIdx={}, rescueMerge={}, skipNaverNet={}, skipBraveNet={}, topK={}, q={}){}",
                pass,
                merged.size(),
                safeSize(naverRes),
                safeSize(braveRes),
                SafeRedactor.redact(hitQuery),
                hitProbeIdx,
                usedRescueMerge,
                skipNaverNetwork, skipBraveNetwork,
                topK, SafeRedactor.redact(query), LogCorrelation.suffix());

        return new FallbackAttempt(pass, merged, naverRes, braveRes, tookMs, skipNaverNetwork, skipBraveNetwork);
    }

    private static List<String> buildCacheProbeQueries(String query, int maxCandidates) {
        if (maxCandidates <= 0) {
            return Collections.emptyList();
        }
        String base = collapseWhitespace(query);
        if (base.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        addProbeCandidate(out, base, maxCandidates);

        // 1) strip surrounding quotes
        String q1 = stripSurroundingQuotes(base);
        addProbeCandidate(out, q1, maxCandidates);

        // 2) remove in-string quotes
        String q2 = collapseWhitespace(q1
                .replace("\"", "")
                .replace("“", "")
                .replace("”", "")
                .replace("'", ""));
        addProbeCandidate(out, q2, maxCandidates);

        // 2-a) strip bracketed qualifiers (often fragment cache keys)
        String q2a = stripBracketContent(q2);
        addProbeCandidate(out, q2a, maxCandidates);

        // 2-b) normalize punctuation / ellipsis (reduces cache-key fragmentation from
        // commas/periods/…)
        String q2b = normalizePunct(q2a);
        addProbeCandidate(out, q2b, maxCandidates);

        // 3) strip trailing intent suffix (official/docs/homepage)
        String q3 = stripTrailingSuffix(q2b);
        addProbeCandidate(out, q3, maxCandidates);

        // 3-b) tail-chop (token-level) to reduce cache fragmentation caused by
        // conversational suffixes / extra qualifiers.
        // This intentionally only affects cache-only probing; it does NOT change the
        // primary network query.
        addProbeCandidate(out, chopTailTokens(q3, 1), maxCandidates);
        addProbeCandidate(out, chopTailTokens(q3, 2), maxCandidates);

        // 4) remove site: constraints (these often fragment cache keys)
        String q4 = collapseWhitespace(q3.replaceAll("(?i)\\s+site:[^\\s]+", ""));
        addProbeCandidate(out, q4, maxCandidates);

        // 5) also try suffix-strip on the original base
        addProbeCandidate(out, stripTrailingSuffix(base), maxCandidates);

        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(out);
    }

    private static void addProbeCandidate(LinkedHashSet<String> out, String q, int maxCandidates) {
        if (out == null || maxCandidates <= 0 || out.size() >= maxCandidates) {
            return;
        }
        if (q == null) {
            return;
        }
        String t = collapseWhitespace(q);
        if (t.isEmpty()) {
            return;
        }
        out.add(t);
    }

    private static String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            return t.replaceAll("\\s+", " ").trim();
        } catch (Throwable ignore) {
            return t;
        }
    }

    private static String stripSurroundingQuotes(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty() || t.length() < 2) {
            return t;
        }
        String a = t;
        for (int i = 0; i < 2; i++) {
            if (a.length() < 2) {
                break;
            }
            char first = a.charAt(0);
            char last = a.charAt(a.length() - 1);

            boolean dq = (first == '"' && last == '"');
            boolean sq = (first == '\'' && last == '\'');
            boolean fancy = (first == '“' && last == '”');

            if (dq || sq || fancy) {
                a = collapseWhitespace(a.substring(1, a.length() - 1));
            } else {
                break;
            }
        }
        return a;
    }

    private static String stripTrailingSuffix(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }

        // keep this list short and conservative (avoid over-stripping)
        final String[] suffixes = new String[] {
                " 공식 홈페이지",
                " 공식홈페이지",
                " 공식 사이트",
                " 공식사이트",
                " 공식 문서",
                " 공식문서",
                " 홈페이지",
                " 사이트",
                " 공식",
                " 문서",
                " docs",
                " documentation",
                " api docs",
                " api documentation",
                " api",
                " official site",
                " official website",
                " official",
                " homepage",
                " website"
        };

        String cur = t;
        for (int step = 0; step < 3; step++) {
            String lower = cur.toLowerCase(java.util.Locale.ROOT);
            boolean removed = false;

            for (String suf : suffixes) {
                if (suf == null || suf.isEmpty()) {
                    continue;
                }
                String sufLower = suf.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(sufLower) && cur.length() > suf.length()) {
                    cur = collapseWhitespace(cur.substring(0, cur.length() - suf.length()));
                    removed = true;
                    break;
                }
            }

            if (!removed) {
                break;
            }
        }
        return cur;
    }

    /**
     * Cache-probe only: remove N trailing whitespace-delimited tokens.
     *
     * <p>
     * We intentionally keep this conservative: it should not aggressively
     * shorten already-short queries (which would make the cache probe overly
     * generic/noisy), but it can help when the user query contains extra
     * conversational tail tokens that don't materially change intent.
     * </p>
     */
    private static String chopTailTokens(String s, int tokensToRemove) {
        if (tokensToRemove <= 0) {
            return collapseWhitespace(s);
        }
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }
        // Only applies when there is at least one whitespace delimiter.
        // (Many KR queries are single-token; in that case we keep the original.)
        if (!t.contains(" ")) {
            return t;
        }
        String[] parts = t.split(" ");
        if (parts.length <= tokensToRemove) {
            return t;
        }
        int newLen = parts.length - tokensToRemove;
        if (newLen <= 0) {
            return t;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newLen; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        String out = collapseWhitespace(sb.toString());
        // Prevent degenerating into a 1-char probe.
        if (out.length() < 2) {
            return t;
        }
        return out;
    }

    private static String stripBracketContent(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }
        try {
            // Remove (...) / [...] / {...} blocks (common cache-key fragmentation sources)
            String x = t
                    .replaceAll("\\([^\\)]*\\)", " ")
                    .replaceAll("\\[[^\\]]*\\]", " ")
                    .replaceAll("\\{[^}]*\\}", " ");
            return collapseWhitespace(x);
        } catch (Throwable ignore) {
            return t;
        }
    }

    private static String normalizePunct(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }
        try {
            String x = t
                    .replace("…", " ")
                    .replace("·", " ")
                    .replace("•", " ");
            x = x.replaceAll("[\\p{Punct}]+", " ");
            return collapseWhitespace(x);
        } catch (Throwable ignore) {
            return t;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tracePoolRescueMerge(String query, int topK, boolean officialOnly, int rescueMergeMax) {
        int limit = Math.min(Math.max(1, topK), Math.max(1, rescueMergeMax));

        Object raw = null;
        String source = "selected";
        try {
            raw = TraceStore.get("web.failsoft.domainStagePairs.selected");
        } catch (Throwable ignore) {
            raw = null;
        }

        List<Map<?, ?>> events = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    events.add(m);
                }
            }
        }

        if (events.isEmpty()) {
            // Fallback: if "selected" is empty (e.g., strict filter starvation), use the
            // broader trace pool.
            source = "raw";
            try {
                raw = TraceStore.get("web.failsoft.domainStagePairs");
            } catch (Throwable ignore) {
                raw = null;
            }
            if (raw instanceof List<?> list2) {
                for (Object o : list2) {
                    if (o instanceof Map<?, ?> m) {
                        events.add(m);
                    }
                }
            }
        }

        if (events.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.source", source);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", events.size());
            TraceStore.put("tracePool.size", events.size());
        } catch (Throwable ignore) {
            // best-effort
        }

        String qNorm = normalizeForProbe(query);

        boolean anyMatch = false;
        if (!qNorm.isEmpty()) {
            for (Map<?, ?> e : events) {
                String c = normalizeForProbe(asString(e.get("canonicalQuery")));
                String ex = normalizeForProbe(asString(e.get("executedQuery")));
                if ((!c.isEmpty() && qNorm.equals(c)) || (!ex.isEmpty() && qNorm.equals(ex))) {
                    anyMatch = true;
                    break;
                }
            }
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Map<?, ?> e : events) {
            if (out.size() >= limit) {
                break;
            }

            if (anyMatch && !qNorm.isEmpty()) {
                String c = normalizeForProbe(asString(e.get("canonicalQuery")));
                String ex = normalizeForProbe(asString(e.get("executedQuery")));
                if (!(qNorm.equals(c) || qNorm.equals(ex))) {
                    continue;
                }
            }

            String url = asString(e.get("url")).trim();
            if (url.isEmpty()) {
                continue;
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }

            if (officialOnly) {
                String stage = asString(e.get("stageFinal"));
                if (stage.isBlank() || "EXCLUDED".equalsIgnoreCase(stage.trim())) {
                    stage = asString(e.get("stage"));
                }
                String st = stage.trim().toUpperCase(java.util.Locale.ROOT);
                if (st.isEmpty()) {
                    continue;
                }
                if (!(st.equals("OFFICIAL")
                        || st.equals("DOCS")
                        || st.equals("WHITELIST")
                        || st.contains("PROFILE")
                        || st.equals("NOFILTER_SAFE"))) {
                    continue;
                }
            }

            out.add("URL: " + url);
        }

        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(out);
    }

    private static String normalizeForProbe(String s) {
        String t = stripTrailingSuffix(stripSurroundingQuotes(s));
        if (t.isEmpty()) {
            return "";
        }
        try {
            t = t.replace("\"", "")
                    .replace("“", "")
                    .replace("”", "")
                    .replace("'", "")
                    .trim();
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            t = t.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignore) {
            // best-effort
        }
        return collapseWhitespace(t);
    }

    private static String asString(Object v) {
        if (v == null) {
            return "";
        }
        try {
            return String.valueOf(v);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static List<String> mergeAndLimit(List<String> a, List<String> b, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        mergeInto(out, a, limit);
        mergeInto(out, b, limit);
        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(out);
    }

    private static Set<String> mergeInto(Set<String> base, List<String> add, int limit) {
        if (base == null) {
            base = new LinkedHashSet<>();
        }
        if (limit <= 0 || base.size() >= limit) {
            return base;
        }
        if (add == null || add.isEmpty()) {
            return base;
        }
        for (String s : add) {
            if (base.size() >= limit) {
                break;
            }
            String t = (s == null) ? "" : s.trim();
            if (t.isEmpty()) {
                continue;
            }
            base.add(t);
        }
        return base;
    }

    private static void cacheRescuePayload(String resultKey, List<String> merged) {
        if (resultKey == null || resultKey.isBlank() || merged == null || merged.isEmpty()) {
            return;
        }
        try {
            TraceStore.put(resultKey, merged);
            TraceStore.put("web.failsoft.hybridEmptyFallback.result.cached", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.result.cached.size", merged.size());
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static String queryHashForGate(String query) {
        String norm = normalizeForGate(query);
        if (norm.isEmpty()) {
            return "00000000";
        }
        return crc32Hex(norm);
    }

    private static String normalizeForGate(String s) {
        String t = collapseWhitespace(stripSurroundingQuotes(s));
        if (t.isEmpty()) {
            return "";
        }
        try {
            t = t.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignore) {
            // best-effort
        }
        // Keep hash inputs bounded (avoid megabyte-scale query payloads)
        t = stripSoakSuffix(t);
        t = stripTrailingPunct(t);

        if (t.length() > 512) {
            t = t.substring(0, 512);
        }
        return t;
    }

    /**
     * Normalize backup-query/soak variants to reduce duplicate fallback loops.
     *
     * <p>
     * This is intentionally conservative and only targets common polite/request
     * suffixes that do not materially change the informational intent.
     */
    private static String stripSoakSuffix(String t) {
        if (t == null || t.isBlank()) {
            return "";
        }

        String s = t;
        // Korean request endings (best-effort)
        s = s.replaceAll("(해줘|해주세요|부탁해|부탁드립니다|알려줘|알려주세요|정리해줘|정리해주세요|조사해줘|조사해주세요|추천해줘|추천해주세요)\\s*$", "");
        // English-ish helpers (best-effort)
        s = s.replaceAll("(please|plz)\\s*$", "");

        return collapseWhitespace(s);
    }

    private static String stripTrailingPunct(String t) {
        if (t == null || t.isBlank()) {
            return "";
        }
        return t.replaceAll("[\\s\\p{Punct}]+$", "");
    }

    /**
     * Risk guard for NOFILTER_SAFE: drop obvious low-trust hosts when possible,
     * but never return empty purely due to filtering.
     */
    private List<String> maybeFilterLowTrustForNofilterSafe(List<String> merged, int topK) {
        boolean enabled = getBoolean(true,
                "nova.orch.web.failsoft.hybrid-empty-fallback.nofilter-safe.low-trust-filter.enabled");

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.enabled", enabled);
        } catch (Throwable ignored) {
        }

        if (!enabled || merged == null || merged.isEmpty()) {
            return merged;
        }

        double maxLowTrustRatioCfg = getDouble(0.55d,
                "nova.orch.web.failsoft.hybrid-empty-fallback.nofilter-safe.max-low-trust-ratio");
        double maxLowTrustRatio = (maxLowTrustRatioCfg < 0d) ? 0d : Math.min(1.0d, maxLowTrustRatioCfg);
        int maxLowTrust = (int) Math.floor(Math.max(0, topK) * maxLowTrustRatio);
        if (maxLowTrust <= 0 && merged != null && !merged.isEmpty()) {
            // Fail-soft: keep at least one even if ratio is 0, otherwise we'd return empty
            // and loop.
            maxLowTrust = 1;
        }

        List<String> filtered = new ArrayList<>(merged.size());
        int removed = 0;
        int lowTrustKept = 0;
        List<String> removedHostHashes = new ArrayList<>();
        List<String> keptHostHashes = new ArrayList<>();

        for (String s : merged) {
            String host = extractHost(s);
            boolean lowTrust = host != null && isLowTrustHost(host);
            if (lowTrust && lowTrustKept >= maxLowTrust) {
                removed++;
                if (host != null && removedHostHashes.size() < 6) {
                    removedHostHashes.add(crc32Hex(host));
                }
                continue;
            }

            if (lowTrust) {
                lowTrustKept++;
            }

            filtered.add(s);
            if (host != null && keptHostHashes.size() < 6) {
                keptHostHashes.add(crc32Hex(host));
            }
            if (filtered.size() >= topK) {
                break;
            }
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.removedCount",
                    removed);
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.keptCount",
                    filtered.size());
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.maxLowTrustRatio",
                    maxLowTrustRatio);
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.maxLowTrust",
                    maxLowTrust);
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.lowTrustKept",
                    lowTrustKept);
            TraceStore.put(
                    "web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.removedHostCrc32.sample",
                    String.join(",", removedHostHashes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.keptHostCrc32.sample",
                    String.join(",", keptHostHashes));
        } catch (Throwable ignored) {
        }

        if (!filtered.isEmpty()) {
            return filtered;
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.allFiltered", true);
        } catch (Throwable ignored) {
        }
        return merged;
    }

    private static String extractHost(String snippet) {
        if (snippet == null || snippet.isBlank())
            return null;

        Matcher m = URL_PATTERN.matcher(snippet);
        if (!m.find())
            return null;

        String url = m.group();
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null)
                return null;
            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean isLowTrustHost(String host) {
        if (host == null || host.isBlank())
            return false;
        String h = host.toLowerCase();
        for (String marker : NOFILTER_SAFE_LOW_TRUST_HOST_MARKERS) {
            if (marker == null || marker.isBlank())
                continue;
            if (h.contains(marker))
                return true;
        }
        return false;
    }

    private static String crc32Hex(String s) {
        if (s == null || s.isEmpty()) {
            return "00000000";
        }
        try {
            CRC32 crc = new CRC32();
            crc.update(s.getBytes(StandardCharsets.UTF_8));
            long v = crc.getValue();
            String hx = Long.toHexString(v);
            if (hx.length() >= 8) {
                return hx.substring(hx.length() - 8);
            }
            return "00000000".substring(hx.length()) + hx;
        } catch (Throwable ignore) {
            try {
                return Integer.toHexString(s.hashCode());
            } catch (Throwable ignore2) {
                return "00000000";
            }
        }
    }

    private static boolean isBreakerOpen(NightmareBreaker breaker, String key) {
        try {
            return breaker != null && breaker.isOpen(key);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void logOnce(String key, String fmt, Object... args) {
        try {
            Object prev = TraceStore.putIfAbsent(key, Boolean.TRUE);
            if (prev != null) {
                return;
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
        try {
            log.info(fmt, args);
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private static boolean isTrueish(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static List<String> safeList(List<String> v) {
        return (v == null) ? Collections.emptyList() : v;
    }

    private static int safeSize(ProviderResult r) {
        if (r == null || r.snippets == null) {
            return 0;
        }
        return r.snippets.size();
    }

    private static long safeTook(ProviderResult r) {
        return (r == null) ? 0L : r.tookMs;
    }

    private static String safeStatus(ProviderResult r) {
        return (r == null || r.status == null) ? "" : r.status;
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private boolean isColdStart() {
        try {
            long w = coldStartWindowMs;
            if (w <= 0L) {
                return false;
            }
            long age = System.currentTimeMillis() - BOOT_EPOCH_MS;
            return age >= 0L && age <= w;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private long coldStartAgeMs() {
        try {
            long age = System.currentTimeMillis() - BOOT_EPOCH_MS;
            return Math.max(0L, age);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    private boolean isBudgetExhaustedRaw0() {
        try {
            // Prefer explicit cause marker emitted by
            // HybridWebSearchProvider.awaitWithDeadline
            Object causeObj = TraceStore.get("web.await.minLiveBudget.cause");
            String cause = (causeObj == null) ? "" : String.valueOf(causeObj).trim();
            long budgetExhausted = TraceStore.getLong("web.await.budgetExhausted");
            if (!"budget_exhausted".equalsIgnoreCase(cause) && budgetExhausted <= 0L) {
                return false;
            }

            // Conservative detection: only treat as raw=0ms when an explicit rawTimeoutMs
            // marker exists. DO NOT infer from missing keys (TraceStore.getLong(..)
            // defaults
            // to 0 and can cause over-triggering of "offline-first" skip).
            Long raw = null;
            Object rawObj = TraceStore.get("web.await.minLiveBudget.lastRawTimeoutMs");
            if (rawObj != null) {
                if (rawObj instanceof Number n) {
                    raw = n.longValue();
                } else {
                    long parsed = parseLong(String.valueOf(rawObj), Long.MIN_VALUE);
                    raw = (parsed == Long.MIN_VALUE) ? null : parsed;
                }
            }
            if (raw == null) {
                Object rawObj2 = TraceStore.get("web.await.minLiveBudget.tinyBudget.rawTimeoutMs");
                if (rawObj2 != null) {
                    if (rawObj2 instanceof Number n) {
                        raw = n.longValue();
                    } else {
                        long parsed = parseLong(String.valueOf(rawObj2), Long.MIN_VALUE);
                        raw = (parsed == Long.MIN_VALUE) ? null : parsed;
                    }
                }
            }

            if (raw == null) {
                // No explicit raw budget marker -> keep "online" path available.
                // (This is intentionally conservative to avoid skipping 정상 웹 검색.)
                try {
                    TraceStore.inc("web.failsoft.hybridEmptyFallback.budgetExhaustedRaw0.missingRawMarker");
                } catch (Throwable ignore2) {
                    // best-effort
                }
                return false;
            }

            // raw timeout marker (0ms in cold-start) – treat <=0 as "no live budget".
            return raw <= 0L;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Best-effort signal: was this provider effectively unavailable due to
     * rate-limit/cooldown/backoff (as opposed to a true 0-hit)?
     *
     * <p>
     * This is used to avoid re-submitting live network calls during cold-start
     * (GLGO) and to classify terminal merged=0 cases.
     */
    private boolean isRateLimitOrCooldownSignal(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        try {
            if (isTrueish(TraceStore.get("web.failsoft.rateLimitBackoff." + p + ".skipped"))
                    || isTrueish(TraceStore.get("web.failsoft.rateLimitBackoff." + p + ".rateLimited"))
                    || isTrueish(TraceStore.get("web." + p + ".cooldown.startedNow"))) {
                return true;
            }

            Object reasonObj = TraceStore.get("web." + p + ".skipped.reason");
            String reason = (reasonObj == null) ? "" : String.valueOf(reasonObj).trim().toLowerCase(Locale.ROOT);
            if (reason.isEmpty()) {
                return false;
            }
            // hedge_skip is an optimization; allow fallback to still try the provider.
            if (reason.contains("hedge")) {
                return false;
            }
            return reason.contains("rate_limit")
                    || reason.contains("429")
                    || reason.contains("cooldown");
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void markProviderSkippedBudgetExhaustedRaw0(String provider) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        try {
            TraceStore.put("web." + p + ".skipped", true);
            TraceStore.putIfAbsent("web." + p + ".skipped.reason", "budget_exhausted_raw0");
            TraceStore.putIfAbsent("web." + p + ".skipped.stage", "coldstart_minLiveBudget");
            TraceStore.put("web." + p + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + p + ".skipped.count");
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private boolean getBoolean(boolean defaultVal, String... keys) {
        if (env == null || keys == null || keys.length == 0) {
            return defaultVal;
        }
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            try {
                String v = env.getProperty(k);
                if (v == null) {
                    continue;
                }
                v = v.trim();
                if (v.isEmpty()) {
                    continue;
                }
                return Boolean.parseBoolean(v);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        return defaultVal;
    }

    private long getLong(long defaultVal, String... keys) {
        if (env == null || keys == null || keys.length == 0) {
            return defaultVal;
        }
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            try {
                String v = env.getProperty(k);
                if (v == null) {
                    continue;
                }
                v = v.trim();
                if (v.isEmpty()) {
                    continue;
                }
                return Long.parseLong(v);
            } catch (Throwable ignore) {
                // best-effort: try next alias
            }
        }
        return defaultVal;
    }

    private double getDouble(double defaultVal, String... keys) {
        if (env == null || keys == null || keys.length == 0) {
            return defaultVal;
        }
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            try {
                String v = env.getProperty(k);
                if (v == null) {
                    continue;
                }
                v = v.trim();
                if (v.isEmpty()) {
                    continue;
                }
                return Double.parseDouble(v);
            } catch (Throwable ignore) {
                // best-effort: try next alias
            }
        }
        return defaultVal;
    }

    private static long parseLong(String v, long def) {
        if (v == null) {
            return def;
        }
        try {
            String t = v.trim();
            if (t.isEmpty()) {
                return def;
            }
            return Long.parseLong(t);
        } catch (Throwable ignore) {
            return def;
        }
    }

    private static long clampMs(long v, long min, long max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static final class FallbackAttempt {
        final String pass;
        final List<String> merged;
        final ProviderResult naver;
        final ProviderResult brave;
        final long tookMs;
        final boolean skipNaver;
        final boolean skipBrave;

        private FallbackAttempt(String pass, List<String> merged, ProviderResult naver, ProviderResult brave,
                long tookMs,
                boolean skipNaver, boolean skipBrave) {
            this.pass = pass;
            this.merged = (merged == null) ? Collections.emptyList() : merged;
            this.naver = naver;
            this.brave = brave;
            this.tookMs = tookMs;
            this.skipNaver = skipNaver;
            this.skipBrave = skipBrave;
        }

        static FallbackAttempt empty(String pass, boolean skipNaver, boolean skipBrave) {
            return new FallbackAttempt(pass,
                    Collections.emptyList(),
                    ProviderResult.empty("naver", "skipped", 0L),
                    ProviderResult.empty("brave", "skipped", 0L),
                    0L,
                    skipNaver,
                    skipBrave);
        }
    }

    private static final class ProviderResult {
        final String provider;
        final List<String> snippets;
        final String status;
        final long tookMs;

        private ProviderResult(String provider, List<String> snippets, String status, long tookMs) {
            this.provider = provider;
            this.snippets = (snippets == null) ? Collections.emptyList() : snippets;
            this.status = status;
            this.tookMs = tookMs;
        }

        static ProviderResult of(String provider, List<String> snippets, String status, long tookMs) {
            return new ProviderResult(provider, snippets, status, tookMs);
        }

        static ProviderResult empty(String provider, String status, long tookMs) {
            return new ProviderResult(provider, Collections.emptyList(), status, tookMs);
        }
    }
}
