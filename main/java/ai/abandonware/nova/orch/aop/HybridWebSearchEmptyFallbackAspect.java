package ai.abandonware.nova.orch.aop;

import static ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackSupport.*;

import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final FailurePatternOrchestrator failurePatternOrchestrator;

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
        this(env, naverSearchServiceProvider, braveSearchServiceProvider, searchIoExecutorProvider,
                nightmareBreakerProvider, faultMaskMonitorProvider, null);
    }

    public HybridWebSearchEmptyFallbackAspect(
            Environment env,
            ObjectProvider<NaverSearchService> naverSearchServiceProvider,
            ObjectProvider<BraveSearchService> braveSearchServiceProvider,
            ObjectProvider<ExecutorService> searchIoExecutorProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider,
            FailurePatternOrchestrator failurePatternOrchestrator) {
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
        this.failurePatternOrchestrator = failurePatternOrchestrator;
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
        if (isTrueish(TraceStore.get("web.boundedRoute"))) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.skipped.reason", "boundedRoute");
            } catch (Exception ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.boundedRouteTrace", ignore);
            }
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
                log.warn("[HybridEmptyFallback] searchWithTrace called but args invalid: queryHash={}, queryLength={}, topK={}",
                        SafeRedactor.hashValue(query), query == null ? 0 : query.length(), topK);
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
        if (isTrueish(TraceStore.get("web.boundedRoute"))) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.skipped.reason", "boundedRoute");
            } catch (Exception ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.boundedRouteTrace", ignore);
            }
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.guardContextEarly", ignore);
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
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cachedResult", ignore);
                cached = null;
            }
            if (cached instanceof List<?> cachedList && !cachedList.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> typedCachedList = (List<String>) cachedList;
                if (topK > 0 && typedCachedList.size() > topK) {
                    try {
                        return new ArrayList<>(typedCachedList.subList(0, topK));
                    } catch (Throwable ignore) {
                        WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cachedTopK", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.gateKeysTrace", ignore);
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
            String starvationTrigger = "hybridEmptyFallback->" + root;
            TraceStore.put("web.failsoft.starvationFallback.trigger", starvationTrigger);
            TraceStore.put("starvationFallback.trigger", starvationTrigger);
            recordWebStarvation(lastEngine, starvationTrigger);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.rootCauseTrace", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.riskContext", ignore);
            highRiskOrSensitive = false;
        }

        // Basic trace markers for ops/debug.
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.mode", "completion-poll");
            TraceStore.put("web.failsoft.hybridEmptyFallback.deadlineMs", deadlineMs);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.stage", safe(lastStage));
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.engine", safe(lastEngine));
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.cause", safe(lastCause));
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.triggerTrace", ignore);
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
            } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.coldstartTrace", ignore); }
        }
        if (budgetExhaustedRaw0) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.budgetExhaustedRaw0", true);
            } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.budgetExhaustedRaw0Trace", ignore); }
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
            } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.coldstartCacheOnlyTrace", ignore); }

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
            } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.budgetSkipLiveTrace", ignore); }
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
                    } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.relaxedAttemptTrace", ignore); }

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
                        } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.relaxedUsedTrace", ignore); }
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
                    } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.nofilterAttemptTrace", ignore); }

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
                        } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.nofilterUsedTrace", ignore); }

                        List<String> merged = maybeFilterLowTrustForNofilterSafe(
                                nofilterAttempt.merged,
                                topK,
                                getBoolean(true,
                                        "nova.orch.web.failsoft.hybrid-empty-fallback.nofilter-safe.low-trust-filter.enabled"),
                                getDouble(0.55d,
                                        "nova.orch.web.failsoft.hybrid-empty-fallback.nofilter-safe.max-low-trust-ratio"),
                                getDouble(0.5d, "nova.ecosystem.ammonia.threshold"),
                                env == null ? "UNVERIFIED"
                                        : env.getProperty("nova.ecosystem.ammonia.quarantine-tag", "UNVERIFIED"));
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
                        WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.nofilterSkippedTrace", ignore);
                    }
                }
            } catch (Throwable ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.demotionTrace", ignore);
            } finally {
                try {
                    GuardContextHolder.set(original);
                } catch (Throwable ignore) {
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.demotedContextRestore", ignore);
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
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.finalCacheOnlyTrace", ignore);
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
                            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.finalCacheOnlyNofilterUsedTrace", ignore);
                        }
                        cacheRescuePayload(resultKey, cacheOnlyNofilter.merged);
                        return cacheOnlyNofilter.merged;
                    }
                } catch (Throwable ignore) {
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.finalCacheOnlyNofilterTrace", ignore);
                } finally {
                    try {
                        GuardContextHolder.set(original);
                    } catch (Throwable ignore) {
                        WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.finalContextRestore", ignore);
                    }
                }
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.finalSafetyNet", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.noRescueSignals", ignore);
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

            // Final KPI/classification markers for terminal merged=0 postmortem scripts.
            String trigger = "noRescue:" + clazz;
            TraceStore.put("web.failsoft.starvationFallback.trigger", trigger);
            TraceStore.put("starvationFallback.trigger", trigger);
            recordWebStarvation(lastEngine, trigger);
            TraceStore.put("end.classification", "merged0." + clazz);
            TraceStore.put("end.classification.callSite", safe(callSite));
            TraceStore.put("end.classification.merged", 0);
            TraceStore.put("end.classification.queryHash", queryHashForGate(query));
            TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 0);
            TraceStore.putIfAbsent("cacheOnly.merged.count", 0);
            TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 0);
            TraceStore.putIfAbsent("tracePool.size", 0);
            TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", false);
            TraceStore.putIfAbsent("rescueMerge.used", false);
            TraceStore.putIfAbsent("web.failsoft.starvationFallback.poolSafeEmpty", false);
            TraceStore.putIfAbsent("starvationFallback.poolSafeEmpty", false);
            TraceStore.putIfAbsent("poolSafeEmpty", false);

            if (isColdStart()) {
                TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.coldStart", true);
                TraceStore.put("web.failsoft.hybridEmptyFallback.noRescue.coldStartAgeMs", coldStartAgeMs());
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.noRescueTrace", ignore);
        }
        logOnce("web.failsoft.hybridEmptyFallback.branchLog.noRescue."
                + safe(callSite) + "." + safe(clazz) + ".once",
                "[Nova] Hybrid websearch empty -> no rescue (class={}, callSite={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkippedSignal={}, naverSkippedSignal={}, partialDown={}, starvation={}, strictPlanHint={}, strictDomainRequired={}, lastStage={}, lastEngine={}, lastCause={}, topK={}, queryHash={}, queryLength={}){}",
                clazz,
                safe(callSite),
                timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount,
                braveSkippedSignal, naverSkippedSignal, partialDownSignal, starvationSignal,
                strictPlanHint, strictDomainRequired,
                safe(lastStage), safe(lastEngine), safe(lastCause),
                topK, SafeRedactor.hashValue(query), query == null ? 0 : query.length(), LogCorrelation.suffix());
    }

    private void recordWebStarvation(String provider, String ladderStage) {
        if (failurePatternOrchestrator == null) {
            return;
        }
        try {
            failurePatternOrchestrator.recordWebStarvation(
                    provider == null || provider.isBlank() ? "web" : provider,
                    ladderStage);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.failurePatternStarvation", ignore);
        }
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
                    "[Nova] Hybrid websearch empty -> fallback skipped (both providers unavailable) (pass={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, lastStage={}, lastEngine={}, lastCause={}, topK={}, queryHash={}, queryLength={}){}",
                    pass,
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                    safe(lastStage), safe(lastEngine), safe(lastCause),
                    topK, SafeRedactor.hashValue(query), query == null ? 0 : query.length(), LogCorrelation.suffix());
            return FallbackAttempt.empty(pass, skipNaver, skipBrave);
        }

        // Best-effort trace markers.
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.pass", pass);
            TraceStore.put("web.failsoft.hybridEmptyFallback.skipNaver", skipNaver);
            TraceStore.put("web.failsoft.hybridEmptyFallback.skipBrave", skipBrave);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.attemptTrace", ignore);
        }

        logOnce("web.failsoft.hybridEmptyFallback.branchLog.attempt." + safe(pass) + ".once",
                "[Nova] Hybrid websearch empty -> attempting completion-poll fallback (pass={}, deadlineMs~={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, lastStage={}, lastEngine={}, lastCause={}, skipNaver={}, skipBrave={}, topK={}, queryHash={}, queryLength={}){}",
                pass,
                Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())),
                timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                safe(lastStage), safe(lastEngine), safe(lastCause),
                skipNaver, skipBrave,
                topK, SafeRedactor.hashValue(query), query == null ? 0 : query.length(), LogCorrelation.suffix());

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
                            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.minLiveBudgetTrace", ignore);
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
                    } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.interruptedTrace", ignore); }
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
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.providerFuture", t);
                    r = ProviderResult.empty("unknown", HybridWebProviderFailureStatus.from(t), 0L);
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
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cancelRemaining", ignore);
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
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.completionPollTrace", ignore);
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
                } catch (Throwable ignore) { WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyTopupTrace", ignore); }
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.attemptResultTrace", ignore);
        }

        if (merged.isEmpty()) {
            logOnce("web.failsoft.hybridEmptyFallback.branchLog.fallbackEmpty." + safe(pass) + ".once",
                    "[Nova] Hybrid websearch empty -> completion-poll fallback returned empty (pass={}, tookMs={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, skipped={}, braveSkipped={}, lastStage={}, lastEngine={}, lastCause={}, naver.count={}, brave.count={}, topK={}, queryHash={}, queryLength={}){}",
                    pass, tookMs,
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, skippedCount, braveSkippedSignal,
                    safe(lastStage), safe(lastEngine), safe(lastCause),
                    safeSize(naverRes), safeSize(braveRes),
                    topK, SafeRedactor.hashValue(query), query == null ? 0 : query.length(), LogCorrelation.suffix());
            return new FallbackAttempt(pass, Collections.emptyList(), naverRes, braveRes, tookMs, skipNaver, skipBrave);
        }

        log.warn(
                "[Nova] Hybrid websearch empty after cancellation; applied completion-poll fallback (pass={}, merged={}, naver={}, brave={}, tookMs={}, queryHash={}, queryLength={}){}",
                pass, merged.size(), safeSize(naverRes), safeSize(braveRes), tookMs,
                SafeRedactor.hashValue(query), query == null ? 0 : query.length(),
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
            return ProviderResult.empty("naver", HybridWebProviderFailureStatus.from(t), took);
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
            return ProviderResult.empty("brave", HybridWebProviderFailureStatus.from(t), took);
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
            TraceStore.put("web.failsoft.hybridEmptyFallback.error.last", HybridWebProviderFailureStatus.from(t));
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.providerErrorTrace", ignore);
        }
        try {
            FaultMaskingLayerMonitor monitor = faultMaskMonitorProvider.getIfAvailable();
            if (monitor != null) {
                monitor.record("web.failsoft.hybridEmptyFallback", t, provider);
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.providerErrorFaultMask", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyGuardContext", ignore);
        }

        // Per-query once gate (request-scoped): avoid re-running the probe ladder
        // multiple times
        // in a demotion chain, while still allowing a second attempt when officialOnly
        // changes.
        String mpIntentHash12 = null;
        boolean zero100 = false;
        try {
            Object z = TraceStore.get("zero100.enabled");
            zero100 = (z != null && String.valueOf(z).equalsIgnoreCase("true"));
            if (zero100) {
                Object m = TraceStore.get("zero100.mpIntent.hash12");
                mpIntentHash12 = (m == null) ? null : String.valueOf(m);
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyZero100Context", ignore);
            zero100 = false;
            mpIntentHash12 = null;
        }

        String qHash = queryHashForGate(query);
        String cacheOnceKey = "web.failsoft.hybridEmptyFallback.cacheOnly.once." + (officialOnly ? "o1" : "o0") + "."
                + qHash;
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 0);
            TraceStore.put("cacheOnly.merged.count", 0);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 0);
            TraceStore.put("tracePool.size", 0);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", false);
            TraceStore.put("rescueMerge.used", false);
            TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", false);
            TraceStore.put("starvationFallback.poolSafeEmpty", false);
            TraceStore.put("poolSafeEmpty", false);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyZeroMergedTrace", ignore);
        }
        try {
            Object prevCache = TraceStore.putIfAbsent(cacheOnceKey, Boolean.TRUE);
            if (prevCache != null) {
                try {
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.once.reenter", true);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.queryHash", qHash);
                    TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.onceKey", cacheOnceKey);
                } catch (Throwable ignore2) {
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyReenterTrace", ignore2);
                }
                return null;
            }
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.queryHash", qHash);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.onceKey", cacheOnceKey);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyOnceTrace", ignore);
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

        // Zero100 keeps Mp-Intent out of TraceStore; record hash-only diagnostics and
        // never rebuild probe queries from raw intent text here.
        try {
            boolean anchorProbeEnabled = getBoolean(true,
                    "nova.orch.zero100.anchor-probe.enabled",
                    "nova.orch.zero100.anchorProbeEnabled");
            if (zero100 && anchorProbeEnabled) {
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.mpIntentHash12", mpIntentHash12);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.zero100.anchorProbe.rawIntentUnavailable", true);
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyMpIntentTrace", ignore);
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probeMax", probeMax);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probeQueryHashes", queryHashes(probeQueries));
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.count",
                    probeQueries == null ? 0 : probeQueries.size());
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.enabled", rescueMergeEnabled);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.max", rescueMergeMax);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 0);
            TraceStore.put("cacheOnly.merged.count", 0);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 0);
            TraceStore.put("tracePool.size", 0);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", false);
            TraceStore.put("rescueMerge.used", false);
            TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", false);
            TraceStore.put("starvationFallback.poolSafeEmpty", false);
            TraceStore.put("poolSafeEmpty", false);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyProbeTrace", ignore);
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
                    naverCached = filterScopedCacheOnlySnippets(query, naverCached, topK, "naver");
                } catch (Throwable ignore) {
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyNaver", ignore);
                    naverCached = Collections.emptyList();
                }
            }
            if (brave != null) {
                try {
                    braveCached = safeList(brave.searchCacheOnly(q, topK));
                    braveCached = filterScopedCacheOnlySnippets(query, braveCached, topK, "brave");
                } catch (Throwable ignore) {
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyBrave", ignore);
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

            // Zero100: secondary Mp-Intent rescue requires raw intent text, which is no longer
            // trace-stored. Keep the diagnostic hash and skip rather than reintroducing raw trace.
            if ((merged == null || merged.isEmpty()) && zero100
                    && getBoolean(true,
                            "nova.orch.zero100.secondary-tracepool-rescue.enabled",
                            "nova.orch.zero100.secondaryTracePoolRescueEnabled")) {
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.secondary.used", false);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.secondary.skipped",
                        "raw_mp_intent_unavailable");
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.secondary.queryHash12",
                        mpIntentHash12);
            }
        }

        if (merged == null || merged.isEmpty()) {
            try {
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit", false);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit.queryHash12", queryHashOrMarker(hitQuery));
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.hitProbeIdx", hitProbeIdx);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.usedQueryHash12", queryHashOrMarker(hitQuery));
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", usedRescueMerge);
                TraceStore.put("rescueMerge.used", usedRescueMerge);
                TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.breadcrumb", "miss");
            } catch (Throwable ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyMissTrace", ignore);
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
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.hit.queryHash12", queryHashOrMarker(hitQuery));
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.hitProbeIdx", hitProbeIdx);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.probe.usedQueryHash12",
                    queryHashOrMarker(hitQuery));
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.cacheOnlyHitTrace", ignore);
            // best-effort
        }

        logOnce("web.failsoft.hybridEmptyFallback.branchLog.cacheOnly." + safe(pass) + ".once",
                "[Nova] Hybrid websearch empty -> cache-only rescue hit (pass={}, merged={}, naver={}, brave={}, hitQueryHash={}, hitProbeIdx={}, rescueMerge={}, skipNaverNet={}, skipBraveNet={}, topK={}, queryHash={}, queryLength={}){}",
                pass,
                merged.size(),
                safeSize(naverRes),
                safeSize(braveRes),
                SafeRedactor.hashValue(hitQuery),
                hitProbeIdx,
                usedRescueMerge,
                skipNaverNetwork, skipBraveNetwork,
                topK, SafeRedactor.hashValue(query), query == null ? 0 : query.length(), LogCorrelation.suffix());

        return new FallbackAttempt(pass, merged, naverRes, braveRes, tookMs, skipNaverNetwork, skipBraveNetwork);
    }

    private static boolean isBreakerOpen(NightmareBreaker breaker, String key) {
        try {
            return breaker != null && breaker.isOpen(key);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.breakerOpenCheck", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.logOncePutIfAbsent", ignore);
            // fail-soft
        }
        try {
            log.info(fmt, args);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.logOnceEmit", ignore);
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
        return SafeRedactor.traceLabelOrFallback(s, "");
    }

    private static List<String> queryHashes(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> hashes = new ArrayList<>();
        for (String query : queries) {
            String marker = queryHashOrMarker(query);
            if (marker != null && !marker.isBlank()) {
                hashes.add(marker);
            }
        }
        return hashes;
    }

    private static String queryHashOrMarker(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String trimmed = query.trim();
        if (trimmed.startsWith("__") && trimmed.endsWith("__")) {
            return trimmed;
        }
        String hash = SafeRedactor.hash12(trimmed);
        return hash == null ? "" : hash;
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.coldStart", ignore);
            return false;
        }
    }

    private long coldStartAgeMs() {
        try {
            long age = System.currentTimeMillis() - BOOT_EPOCH_MS;
            return Math.max(0L, age);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.coldStartAge", ignore);
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
                    WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.budgetExhaustedRaw0MissingRawMarker", ignore2);
                    // best-effort
                }
                return false;
            }

            // raw timeout marker (0ms in cold-start) – treat <=0 as "no live budget".
            return raw <= 0L;
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.budgetExhaustedRaw0", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.rateLimitCooldownSignal", ignore);
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
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.markProviderSkippedBudgetExhaustedRaw0", ignore);
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
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.getBoolean", ignore);
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
            } catch (NumberFormatException ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.getLong", ignore);
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
            } catch (NumberFormatException ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.getDouble", ignore);
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
        } catch (NumberFormatException ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallback.parseLong", ignore);
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
