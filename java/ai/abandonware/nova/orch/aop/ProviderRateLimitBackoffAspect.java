package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.LogCorrelation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProviderRateLimitBackoffAspect {

    private static final Logger log = LoggerFactory.getLogger(ProviderRateLimitBackoffAspect.class);

    private final RateLimitBackoffCoordinator backoff;
    private final BraveRateLimitState braveState;

    public ProviderRateLimitBackoffAspect(RateLimitBackoffCoordinator backoff, BraveRateLimitState braveState) {
        this.backoff = backoff;
        this.braveState = braveState;
    }

    @Around("execution(* com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object aroundNaverSearchSnippetsSync(ProceedingJoinPoint pjp) throws Throwable {
        RateLimitBackoffCoordinator.Decision d = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
        if (d.shouldSkip()) {
            markSkipped("naver", d);
            markWebPartialDown("naver", "cooldown:" + safeStr(d.reason()));
            return List.of();
        }

        try {
            Object out = pjp.proceed();
            if (isTrueish(TraceStore.get("web.naver.429")) || isTrueish(TraceStore.get("web.rateLimited"))) {
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, null,
                        "naver_trace_429", null);
                TraceStore.put("web.naver.cooldown.startedNow", true);
            } else if (out instanceof List<?> l && !l.isEmpty()) {
                backoff.recordSuccess(RateLimitBackoffCoordinator.PROVIDER_NAVER);
            }
            return out;
        } catch (Throwable t) {
            if (isRateLimit429(t)) {
                Long retryAfterMs = extractRetryAfterMs(t);
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, retryAfterMs,
                        "naver_exception_429", extractRetryAfterHeader(t));
                TraceStore.put("web.naver.cooldown.startedNow", true);
                markRateLimited("naver", retryAfterMs, t);
                return List.of();
            }
            if (isAwaitTimeout(t)) {
                // await_timeout is a local join/await timebox outcome (not a provider I/O timeout).
                // Keep it shallow (<=2s) so we don't accidentally install 10s-level TIMEOUT backoff.
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_NAVER,
                        RateLimitBackoffCoordinator.FailureKind.AWAIT_TIMEOUT,
                        "naver_exception_await_timeout", t.getClass().getSimpleName());
                try {
                    TraceStore.inc("web.naver.await_timeout.exception.count");
                } catch (Throwable ignore) {
                }
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
                markSkipped("naver", d2);
                return List.of();
            }
            if (isTimeout(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_NAVER,
                        RateLimitBackoffCoordinator.FailureKind.TIMEOUT,
                        "naver_exception_timeout", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
                markSkipped("naver", d2);
                return List.of();
            }
            if (isCancelled(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_NAVER,
                        RateLimitBackoffCoordinator.FailureKind.CANCELLED,
                        "naver_exception_cancelled", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
                markSkipped("naver", d2);
                return List.of();
            }
            throw t;
        }
    }

    @Around("execution(* com.example.lms.service.NaverSearchService.searchWithTraceSync(..))")
    public Object aroundNaverSearchWithTraceSync(ProceedingJoinPoint pjp) throws Throwable {
        RateLimitBackoffCoordinator.Decision d = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
        if (d.shouldSkip()) {
            markSkipped("naver", d);
            markWebPartialDown("naver", "cooldown:" + safeStr(d.reason()));
            return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
        }

        try {
            Object out = pjp.proceed();
            if (isTrueish(TraceStore.get("web.naver.429")) || isTrueish(TraceStore.get("web.rateLimited"))) {
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, null,
                        "naver_trace_429", null);
                TraceStore.put("web.naver.cooldown.startedNow", true);
            } else if (out instanceof NaverSearchService.SearchResult sr
                    && sr.snippets() != null && !sr.snippets().isEmpty()) {
                backoff.recordSuccess(RateLimitBackoffCoordinator.PROVIDER_NAVER);
            }
            return out;
        } catch (Throwable t) {
            if (isRateLimit429(t)) {
                Long retryAfterMs = extractRetryAfterMs(t);
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, retryAfterMs,
                        "naver_exception_429", extractRetryAfterHeader(t));
                TraceStore.put("web.naver.cooldown.startedNow", true);
                markRateLimited("naver", retryAfterMs, t);
                return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
            }
            if (isAwaitTimeout(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_NAVER,
                        RateLimitBackoffCoordinator.FailureKind.AWAIT_TIMEOUT,
                        "naver_exception_await_timeout", t.getClass().getSimpleName());
                try {
                    TraceStore.inc("web.naver.await_timeout.exception.count");
                } catch (Throwable ignore) {
                }
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
                markSkipped("naver", d2);
                return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
            }
            if (isTimeout(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_NAVER,
                        RateLimitBackoffCoordinator.FailureKind.TIMEOUT,
                        "naver_exception_timeout", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
                markSkipped("naver", d2);
                return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
            }
            if (isCancelled(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_NAVER,
                        RateLimitBackoffCoordinator.FailureKind.CANCELLED,
                        "naver_exception_cancelled", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
                markSkipped("naver", d2);
                return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
            }
            throw t;
        }
    }

    @Around("execution(* com.example.lms.service.web.BraveSearchService.searchWithMeta(..))")
    public Object aroundBraveSearchWithMeta(ProceedingJoinPoint pjp) throws Throwable {
        RateLimitBackoffCoordinator.Decision d = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
        if (d.shouldSkip()) {
            markSkipped("brave", d);
            String dr = "cooldown:" + safeStr(d.reason());
            markWebPartialDown("brave", dr);
            markBraveDisabledReasonIfAbsent(dr);
            return BraveSearchResult.cooldown(d.remainingMs(), 0L);
        }

        try {
            Object out = pjp.proceed();
            if (out instanceof BraveSearchResult r) {
                BraveSearchResult.Status st = r.status();
                if (st == BraveSearchResult.Status.HTTP_429
                        || st == BraveSearchResult.Status.HTTP_503
                        || st == BraveSearchResult.Status.RATE_LIMIT_LOCAL
                        || st == BraveSearchResult.Status.COOLDOWN) {
                    Long cooldownMs = (r.cooldownMs() > 0L) ? r.cooldownMs() : null;
                    if (st == BraveSearchResult.Status.RATE_LIMIT_LOCAL || st == BraveSearchResult.Status.COOLDOWN) {
                        // RATE_LIMIT_LOCAL is a short client-side throttle. Use a shallow local cooldown
                        // rather than inflating the global 429 exp-backoff streak.
                        backoff.recordLocalRateLimit(RateLimitBackoffCoordinator.PROVIDER_BRAVE, cooldownMs,
                                "brave_local_" + st);
                    } else {
                        // Real server-side signals (429/503): Retry-After preferred + exp backoff + jitter + cap.
                        backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_BRAVE, cooldownMs,
                                "brave_meta_" + st, null);
                    }
                    TraceStore.put("web.brave.cooldown.startedNow", true);

                    // Align with canonical skip markers used by HybridWebSearchProvider KPIs.
                    // (Even though this call executed, it yielded a rate-limit-like status.)
                    markRateLimitedMeta("brave", cooldownMs, st);

                    String dr = "rate_limit:" + st;
                    markWebPartialDown("brave", dr);
                    markBraveDisabledReasonIfAbsent(dr);
                } else if (st == BraveSearchResult.Status.DISABLED) {
                    String dr = resolveBraveDisabledReason(pjp);
                    markWebPartialDown("brave", dr);
                    markBraveDisabledReasonOverwrite(dr);

                    // Canonical skip marker so downstream policy can reason about Brave being down.
                    TraceStore.put("web.brave.skipped", true);
                    TraceStore.putIfAbsent("web.brave.skipped.reason", "disabled");
                    TraceStore.putIfAbsent("web.brave.skipped.stage", "provider_disabled");
                } else if (st == BraveSearchResult.Status.OK
                        && r.snippets() != null && !r.snippets().isEmpty()) {
                    backoff.recordSuccess(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
                }
            }
            return out;
        } catch (Throwable t) {
            if (isRateLimit429(t)) {
                Long retryAfterMs = extractRetryAfterMs(t);
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_BRAVE, retryAfterMs,
                        "brave_exception_429", extractRetryAfterHeader(t));
                TraceStore.put("web.brave.cooldown.startedNow", true);
                markRateLimited("brave", retryAfterMs, t);
                long cd = (retryAfterMs != null && retryAfterMs > 0L) ? retryAfterMs : 1000L;
                String dr = "rate_limit:exception_429";
                markWebPartialDown("brave", dr);
                markBraveDisabledReasonIfAbsent(dr);
                return BraveSearchResult.cooldown(cd, 0L);
            }
            if (isAwaitTimeout(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_BRAVE,
                        RateLimitBackoffCoordinator.FailureKind.AWAIT_TIMEOUT,
                        "brave_exception_await_timeout", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
                markSkipped("brave", d2);
                String dr = "await_timeout_backoff:" + safeStr(d2.reason());
                markWebPartialDown("brave", dr);
                markBraveDisabledReasonIfAbsent(dr);
                return BraveSearchResult.cooldown(d2.remainingMs(), 0L);
            }
            if (isTimeout(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_BRAVE,
                        RateLimitBackoffCoordinator.FailureKind.TIMEOUT,
                        "brave_exception_timeout", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
                markSkipped("brave", d2);
                String dr = "timeout_backoff:" + safeStr(d2.reason());
                markWebPartialDown("brave", dr);
                markBraveDisabledReasonIfAbsent(dr);
                return BraveSearchResult.cooldown(d2.remainingMs(), 0L);
            }
            if (isCancelled(t)) {
                backoff.recordFailure(RateLimitBackoffCoordinator.PROVIDER_BRAVE,
                        RateLimitBackoffCoordinator.FailureKind.CANCELLED,
                        "brave_exception_cancelled", t.getClass().getSimpleName());
                RateLimitBackoffCoordinator.Decision d2 = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
                markSkipped("brave", d2);
                String dr = "cancel_backoff:" + safeStr(d2.reason());
                markWebPartialDown("brave", dr);
                markBraveDisabledReasonIfAbsent(dr);
                return BraveSearchResult.cooldown(d2.remainingMs(), 0L);
            }
            throw t;
        }
    }

    /**
     * Distinguish Hybrid join/await timebox expiry from provider I/O TIMEOUT.
     * <p>
     * In some code paths a local await outcome surfaces as an exception with a message
     * containing {@code await_timeout}, which would otherwise be misclassified as TIMEOUT
     * (and inflate cooldown up to the hard cap).
     */
    private static boolean isAwaitTimeout(Throwable t) {
        Throwable r = rootCause(t);
        if (r == null) return false;
        String msg = null;
        try {
            msg = r.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = t.getMessage();
            }
        } catch (Throwable ignore) {
            msg = null;
        }
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("await_timeout") || m.contains("await timeout") || m.contains("await-timeout");
    }

    private static boolean isTimeout(Throwable t) {
        Throwable r = rootCause(t);
        if (r == null) return false;
        if (r instanceof java.util.concurrent.TimeoutException) return true;
        String cn = r.getClass().getName();
        if (cn.endsWith("HttpTimeoutException")) return true;
        String msg = r.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("timed out") || m.contains("timeout");
    }

    private static boolean isCancelled(Throwable t) {
        Throwable r = rootCause(t);
        if (r == null) return false;
        if (r instanceof java.util.concurrent.CancellationException) return true;
        if (r instanceof InterruptedException) return true;
        String cn = r.getClass().getName();
        if (cn.endsWith("CancellationException")) return true;
        String msg = r.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("cancel") || m.contains("disposed") || m.contains("interrupted");
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null) return null;
        Throwable r = t;
        for (int i = 0; i < 10; i++) {
            Throwable c = r.getCause();
            if (c == null || c == r) break;
            r = c;
        }
        return r;
    }

    private static void markSkipped(String provider, RateLimitBackoffCoordinator.Decision d) {
        try {
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".skipped", true);
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".remainingMs", d.remainingMs());
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".reason", safeStr(d.reason()));
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".justStarted", d.justStarted());
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".skipped.tsMs", System.currentTimeMillis());

            // Canonical skip markers (ops KPI / downstream ladder compatibility)
            // NOTE: Do not overwrite an existing reason (e.g., breaker_open / hedge_skip).
            TraceStore.put("web." + provider + ".skipped", true);
            TraceStore.putIfAbsent("web." + provider + ".skipped.reason", "cooldown");
            TraceStore.putIfAbsent("web." + provider + ".skipped.stage", "aop_backoff");
            if (d.remainingMs() > 0L) {
                TraceStore.put("web." + provider + ".skipped.extraMs", d.remainingMs());
                TraceStore.putIfAbsent("web." + provider + ".cooldownMs", d.remainingMs());
            }
            TraceStore.put("web." + provider + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + provider + ".skipped.count");

            String onceKey = "web.failsoft.rateLimitBackoff." + provider + ".skipped.logOnce";
            if (TraceStore.putIfAbsent(onceKey, Boolean.TRUE) == null) {
                if (d.justStarted()) {
                    log.info("[Nova] {} skipped by rate-limit backoff (remainingMs={}; justStarted={}; reason={}){}",
                            provider, d.remainingMs(), d.justStarted(), safeStr(d.reason()), LogCorrelation.suffix());
                } else if (log.isDebugEnabled()) {
                    log.debug("[Nova] {} skipped by rate-limit backoff (remainingMs={}; reason={}){}",
                            provider, d.remainingMs(), safeStr(d.reason()), LogCorrelation.suffix());
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void markRateLimited(String provider, Long retryAfterMs, Throwable t) {
        try {
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".rateLimited", true);
            if (retryAfterMs != null) {
                TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".retryAfterMs", retryAfterMs);
            }

            // Canonical skip markers (ops KPI / downstream ladder compatibility)
            TraceStore.put("web." + provider + ".skipped", true);
            TraceStore.putIfAbsent("web." + provider + ".skipped.reason", "cooldown");
            TraceStore.putIfAbsent("web." + provider + ".skipped.stage", "aop_backoff");
            if (retryAfterMs != null && retryAfterMs > 0L) {
                TraceStore.put("web." + provider + ".skipped.extraMs", retryAfterMs);
                TraceStore.putIfAbsent("web." + provider + ".cooldownMs", retryAfterMs);
            }
            if (t != null) {
                TraceStore.put("web." + provider + ".skipped.err", t.getClass().getSimpleName());
            }
            TraceStore.put("web." + provider + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + provider + ".skipped.count");

            String onceKey = "web.failsoft.rateLimitBackoff." + provider + ".logOnce";
            if (TraceStore.putIfAbsent(onceKey, true) == null) {
                log.warn("[Nova] {} rate-limit(429) intercepted; applying backoff (retryAfterMs={}; type={}){}",
                        provider, retryAfterMs, t.getClass().getSimpleName(), LogCorrelation.suffix());
            }
        } catch (Throwable ignore) {
        }
    }

    private static void markRateLimitedMeta(String provider, Long retryAfterMs, BraveSearchResult.Status st) {
        try {
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".rateLimited", true);
            if (retryAfterMs != null && retryAfterMs > 0L) {
                TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".retryAfterMs", retryAfterMs);
            }

            // Canonical skip markers (ops KPI / downstream ladder compatibility)
            TraceStore.put("web." + provider + ".skipped", true);
            String reason = (st == null) ? "rate_limit_meta" : ("brave_meta_" + st);
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".reason", reason);
            TraceStore.putIfAbsent("web." + provider + ".skipped.reason", "cooldown");
            TraceStore.putIfAbsent("web." + provider + ".skipped.stage", "aop_backoff");
            if (retryAfterMs != null && retryAfterMs > 0L) {
                TraceStore.put("web." + provider + ".skipped.extraMs", retryAfterMs);
                TraceStore.putIfAbsent("web." + provider + ".cooldownMs", retryAfterMs);
            }
            TraceStore.put("web." + provider + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + provider + ".skipped.count");
        } catch (Throwable ignore) {
            // best-effort
        }
    }


    private static void markWebPartialDown(String provider, String reason) {
        try {
            // Compatibility: existing code checks "orch.webPartialDown" (boolean).
            TraceStore.put("orch.webPartialDown", true);
            // New: explicit anyDown signal (operators want to see it in traces).
            TraceStore.put("orch.webPartialDown.anyDown", true);

            if (provider != null && !provider.isBlank()) {
                TraceStore.put("orch.webPartialDown.provider." + provider, true);
                TraceStore.putIfAbsent("orch.webPartialDown.firstProvider", provider);
            }
            if (hasText(reason)) {
                TraceStore.putIfAbsent("orch.webPartialDown.reason." + provider, reason);
                TraceStore.putIfAbsent("orch.webPartialDown.firstReason", reason);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private static void markBraveDisabledReasonIfAbsent(String reason) {
        try {
            if (hasText(reason)) {
                TraceStore.putIfAbsent("web.await.brave.disabledReason", reason);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private static void markBraveDisabledReasonOverwrite(String reason) {
        try {
            if (hasText(reason)) {
                TraceStore.put("web.await.brave.disabledReason", reason);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private String resolveBraveDisabledReason(ProceedingJoinPoint pjp) {
        try {
            Object tgt = pjp.getTarget();
            if (tgt instanceof BraveSearchService svc) {
                String dr = svc.disabledReason();
                if (dr != null && !dr.isBlank()) {
                    return dr;
                }

                // If we have a latched quota reset time, surface it.
                long until = (braveState != null) ? braveState.quotaExhaustedUntilEpochMs() : 0L;
                long now = System.currentTimeMillis();
                if (until > 0L && now < until) {
                    long rem = Math.max(0L, until - now);
                    return "quota_exhausted remainingMs=" + rem;
                }

                if (svc.isCoolingDown()) {
                    long rem = svc.cooldownRemainingMs();
                    return "cooldown remainingMs=" + rem;
                }

                if (!svc.isEnabled()) {
                    return "disabled";
                }
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
        return "quota_exhausted_or_disabled";
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean isTrueish(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.longValue() != 0L;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static boolean isRateLimit429(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof WebClientResponseException w) {
            return w.getRawStatusCode() == 429;
        }
        if (t instanceof HttpClientErrorException h) {
            return h.getStatusCode().value() == 429;
        }
        // nested
        return isRateLimit429(t.getCause());
    }

    private static String extractRetryAfterHeader(Throwable t) {
        try {
            if (t instanceof WebClientResponseException w) {
                return w.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            }
            if (t instanceof HttpClientErrorException h) {
                return h.getResponseHeaders() != null
                        ? h.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)
                        : null;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Long extractRetryAfterMs(Throwable t) {
        String v = extractRetryAfterHeader(t);
        long ms = RateLimitBackoffCoordinator.parseRetryAfterMs(v, System.currentTimeMillis());
        return ms > 0L ? ms : null;
    }

    private static String safeStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
