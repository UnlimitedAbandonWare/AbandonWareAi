package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fail-soft bypass for Hybrid(Brave+Naver) web search when the primary concurrent join
 * returns empty due to timeout/cancellation.
 *
 * <p>
 * Motivation: 운영 환경에서 timeout/budget 경계에서 Future cancel/interrupt 전염이 발생하면
 * (Brave breaker-open + Naver cancelled 조합 등) 결과가 0개가 되는 starvation이 반복될 수 있다.
 *
 * <p>
 * This aspect executes a last-resort, <b>budgeted partial-collect</b> fallback <b>only when</b>:
 * <ul>
 *   <li>HybridWebSearchProvider.search 결과가 empty</li>
 *   <li>TraceStore 에 timeout/non-ok await 이벤트가 기록됨 (즉, cancellation 흔적이 있음)</li>
 *   <li>privacy/web guard에 의해 웹 호출이 차단되지 않음</li>
 * </ul>
 *
 * <p>
 * Implementation:
 * <ul>
 *   <li>Naver + Brave 를 동시에 submit</li>
 *   <li>{@link ExecutorCompletionService#poll(long, TimeUnit)} 로 deadline 내 완료된 것만 회수</li>
 *   <li>하나라도 오면 merge + dedupe + limit 후 반환</li>
 *   <li>미완료 Future 는 <b>cancel(false)</b>만 best-effort (절대 cancel(true) 호출 금지)</li>
 * </ul>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class HybridWebSearchEmptyFallbackAspect {

    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchEmptyFallbackAspect.class);

    private final Environment env;
    private final ObjectProvider<NaverSearchService> naverSearchServiceProvider;
    private final ObjectProvider<BraveSearchService> braveSearchServiceProvider;
    private final ObjectProvider<ExecutorService> searchIoExecutorProvider;
    private final ObjectProvider<NightmareBreaker> nightmareBreakerProvider;
    private final ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider;

    public HybridWebSearchEmptyFallbackAspect(
            Environment env,
            ObjectProvider<NaverSearchService> naverSearchServiceProvider,
            ObjectProvider<BraveSearchService> braveSearchServiceProvider,
            ObjectProvider<ExecutorService> searchIoExecutorProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider) {
        this.env = env;
        this.naverSearchServiceProvider = naverSearchServiceProvider;
        this.braveSearchServiceProvider = braveSearchServiceProvider;
        this.searchIoExecutorProvider = searchIoExecutorProvider;
        this.nightmareBreakerProvider = nightmareBreakerProvider;
        this.faultMaskMonitorProvider = faultMaskMonitorProvider;
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

        boolean enabled = Boolean.parseBoolean(env.getProperty(
                "nova.orch.web.failsoft.hybrid-empty-fallback.enabled", "true"));
        if (!enabled) {
            return out;
        }

        long timeoutCount = TraceStore.getLong("web.await.events.timeout.count");
        long timeoutHardCount = TraceStore.getLong("web.await.events.timeout.hard.count");
        long timeoutSoftCount = TraceStore.getLong("web.await.events.timeout.soft.count");
        long nonOkCount = TraceStore.getLong("web.await.events.nonOk.count");

        boolean auxDegraded = isTrueish(TraceStore.get("orch.auxDegraded")) || isTrueish(TraceStore.get("aux.llm.degraded"));
        boolean auxHardDown = isTrueish(TraceStore.get("orch.auxHardDown")) || isTrueish(TraceStore.get("aux.llm.hardDown"));

        // Respect guard: if web search is blocked, never bypass.
        if (isTrueish(TraceStore.get("privacy.web.blocked"))) {
            logOnce("web.failsoft.hybridEmptyFallback.branchLog.webBlocked.once",
                    "[Nova] Hybrid websearch empty but web blocked; skip fallback (timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, auxDegraded={}, auxHardDown={}){}",
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount, auxDegraded, auxHardDown, LogCorrelation.suffix());
            return out;
        }

        if (timeoutCount <= 0 && nonOkCount <= 0) {
            return out;
        }

        // Avoid repeated re-entry within same request.
        Object prev = TraceStore.putIfAbsent("web.failsoft.hybridEmptyFallback.once", Boolean.TRUE);
        if (prev != null) {
            return out;
        }

        Object[] args = pjp.getArgs();
        String query = (args != null && args.length > 0 && args[0] != null) ? String.valueOf(args[0]) : "";
        int topK = (args != null && args.length > 1 && args[1] instanceof Integer i) ? i : 5;
        if (query.isBlank() || topK <= 0) {
            return out;
        }

        // Capture the last await event snapshot for quick "where did it start" debugging.
        String lastStage = null;
        String lastEngine = null;
        String lastCause = null;
        try {
            Object last = TraceStore.get("web.await.last");
            if (last instanceof Map<?, ?> m) {
                Object s = m.get("stage");
                Object e = m.get("engine");
                Object c = m.get("cause");
                lastStage = (s == null) ? null : String.valueOf(s);
                lastEngine = (e == null) ? null : String.valueOf(e);
                lastCause = (c == null) ? null : String.valueOf(c);
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        // Resolve dependencies (best-effort / fail-soft).
        NaverSearchService naver = naverSearchServiceProvider.getIfAvailable();
        BraveSearchService brave = braveSearchServiceProvider.getIfAvailable();
        ExecutorService exec = searchIoExecutorProvider.getIfAvailable();
        NightmareBreaker breaker = nightmareBreakerProvider.getIfAvailable();

        boolean skipNaver = (naver == null) || !naver.isEnabled() || isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_NAVER);
        boolean skipBrave = (brave == null) || !brave.isEnabled() || isBreakerOpen(breaker, NightmareKeys.WEBSEARCH_BRAVE)
                || (brave != null && brave.isCoolingDown());

        if (skipNaver && skipBrave) {
            logOnce("web.failsoft.hybridEmptyFallback.branchLog.allSkipped.once",
                    "[Nova] Hybrid websearch empty -> fallback skipped (both providers unavailable) (timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, lastStage={}, lastEngine={}, lastCause={}, topK={}, q={}){}",
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount,
                    safe(lastStage), safe(lastEngine), safe(lastCause),
                    topK, SafeRedactor.redact(query), LogCorrelation.suffix());
            return out;
        }

        long deadlineMs = clampMs(parseLong(env.getProperty(
                "nova.orch.web.failsoft.hybrid-empty-fallback.deadline-ms", "850"), 850L), 50L, 4000L);

        // Basic trace markers for ops/debug.
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.mode", "completion-poll");
            TraceStore.put("web.failsoft.hybridEmptyFallback.deadlineMs", deadlineMs);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.stage", lastStage);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.engine", lastEngine);
            TraceStore.put("web.failsoft.hybridEmptyFallback.trigger.cause", lastCause);
            TraceStore.put("web.failsoft.hybridEmptyFallback.skipNaver", skipNaver);
            TraceStore.put("web.failsoft.hybridEmptyFallback.skipBrave", skipBrave);
        } catch (Throwable ignore) {
            // best-effort
        }

        logOnce("web.failsoft.hybridEmptyFallback.branchLog.attempt.once",
                "[Nova] Hybrid websearch empty -> attempting completion-poll fallback (deadlineMs={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, lastStage={}, lastEngine={}, lastCause={}, skipNaver={}, skipBrave={}, topK={}, q={}){}",
                deadlineMs, timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount,
                safe(lastStage), safe(lastEngine), safe(lastCause),
                skipNaver, skipBrave,
                topK, SafeRedactor.redact(query), LogCorrelation.suffix());

        long startedMs = System.currentTimeMillis();
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);

        ProviderResult naverRes = ProviderResult.empty("naver", "skipped", 0L);
        ProviderResult braveRes = ProviderResult.empty("brave", "skipped", 0L);

        if (exec == null) {
            // Very defensive fallback: if we cannot schedule, call synchronously with tight timeouts.
            // (Still never uses cancel(true) here.)
            if (!skipNaver && naver != null) {
                naverRes = callNaver(naver, query, topK, deadlineNs);
            }
            if (!skipBrave && brave != null) {
                braveRes = callBrave(brave, query, topK);
            }
        } else {
            ExecutorCompletionService<ProviderResult> ecs = new ExecutorCompletionService<>(exec);
            List<Future<ProviderResult>> futures = new ArrayList<>(2);
            int submitted = 0;

            if (!skipNaver && naver != null) {
                futures.add(ecs.submit(wrap("naver", () -> callNaver(naver, query, topK, deadlineNs))));
                submitted++;
            }
            if (!skipBrave && brave != null) {
                futures.add(ecs.submit(wrap("brave", () -> callBrave(brave, query, topK))));
                submitted++;
            }

            int remaining = submitted;
            Set<String> mergedSoFar = new LinkedHashSet<>();

            while (remaining > 0) {
                long remainNs = deadlineNs - System.nanoTime();
                if (remainNs <= 0L) {
                    break;
                }
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
                if (done == null) {
                    break;
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
        }

        List<String> merged = mergeAndLimit(
                naverRes == null ? Collections.emptyList() : naverRes.snippets,
                braveRes == null ? Collections.emptyList() : braveRes.snippets,
                topK);

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
            logOnce("web.failsoft.hybridEmptyFallback.branchLog.fallbackEmpty.once",
                    "[Nova] Hybrid websearch empty -> completion-poll fallback returned empty (deadlineMs={}, tookMs={}, timeoutAll={}, timeoutHard={}, timeoutSoft={}, nonOk={}, lastStage={}, lastEngine={}, lastCause={}, naver.count={}, brave.count={}, topK={}, q={}){}",
                    deadlineMs, tookMs,
                    timeoutCount, timeoutHardCount, timeoutSoftCount, nonOkCount,
                    safe(lastStage), safe(lastEngine), safe(lastCause),
                    safeSize(naverRes), safeSize(braveRes),
                    topK, SafeRedactor.redact(query), LogCorrelation.suffix());
            return out;
        }

        log.warn("[Nova] Hybrid websearch empty after cancellation; applied completion-poll fallback (merged={}, naver={}, brave={}, tookMs={}, q={}){}",
                merged.size(), safeSize(naverRes), safeSize(braveRes), tookMs, SafeRedactor.redact(query), LogCorrelation.suffix());

        return merged;
    }

    private ProviderResult callNaver(NaverSearchService naver, String query, int topK, long deadlineNs) {
        long st = System.nanoTime();
        try {
            // Derive a tight block timeout from the remaining budget window.
            long remainMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()));
            // NOTE: NaverSearchService's sync facade accepts an override timeout. We intentionally
            // keep it within the remaining budget window (and avoid the default 250ms+ base)
            // so fallback workers don't get stuck for seconds when the request is already starved.
            long blockMs = clampMs(Math.max(0L, remainMs - 30L), 50L, 2000L);
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
