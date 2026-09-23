package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Locale;

/**
 * Implementation of {@link WebSearchProvider} backed by the Brave search API.
 * This provider honours an API key supplied via configuration and uses a
 * reactive {@link WebClient} to perform HTTP requests. When no API key is
 * configured the provider returns an empty bundle immediately. Any
 * exceptions during the call are logged and result in an empty bundle. The
 * default priority is higher than the built-in Bing and Naver providers so
 * that Brave results are preferred when available.
 */
@Component
@RequiredArgsConstructor
public class BraveSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchProvider.class);
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_MISSING_KEY = new java.util.concurrent.atomic.AtomicBoolean(
            false);
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_EMPTY_QUERY = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    @Value("${gpt-search.brave.api-key:${BRAVE_API_KEY:${GPT_SEARCH_BRAVE_API_KEY:__MISSING__}}}")
    private String apiKey;
    @Value("${gpt-search.brave.api-key-free:${BRAVE_API_KEY_FREE:${GPT_SEARCH_BRAVE_API_KEY_FREE:}}}")
    private String apiKeyFree;

    /**
     * Accepts either a host base URL (e.g. https://api.search.brave.com) or a
     * full endpoint URL (e.g. https://api.search.brave.com/res/v1/web/search).
     */
    @Value("${gpt-search.brave.base-url:${search.brave.base-url:https://api.search.brave.com}}")
    private String baseUrl;

    @Value("${gpt-search.brave.count:${search.brave.count:8}}")
    private int count;

    /**
     * When timeout-ms is 0 or not configured, timeout-sec is used.
     */
    @Value("${gpt-search.brave.timeout-ms:${search.brave.timeout-ms:0}}")
    private int timeoutMs;

    @Value("${gpt-search.brave.timeout-sec:${search.brave.timeout-sec:3}}")
    private int timeoutSec;

    @Value("${gpt-search.brave.qps:${search.brave.qps:1}}")
    private int qps;

    @Value("${gpt-search.brave.enabled:${search.brave.enabled:true}}")
    private boolean enabled;

    private final WebClient.Builder http;
    /**
     * Two-tier cache composed of an in-memory Caffeine tier and an optional
     * Upstash Redis backend. Results are keyed by a deterministic prefix
     * with the raw query to maximise reuse. When Upstash is disabled the
     * cache degenerates to the local tier only.
     */
    private final com.example.lms.infra.upstash.UpstashBackedWebCache cache;
    /**
     * Rate limiter backed by Upstash. Applies a per-second quota to
     * Brave requests based on the {@code search.brave.qps} property.
     */
    private final com.example.lms.infra.upstash.UpstashRateLimiter limiter;

    @Autowired(required = false)
    private BraveSearchService braveQuotaGuard;

    @Autowired(required = false)
    private ProviderCredentialResolver credentialResolver;

    @Override
    public String id() {
        return "brave";
    }

    /**
     * Priority/order used by {@link CachedWebSearch}.
     *
     * <p>
     * Lower values are invoked first. We place Brave between the built-in
     * Naver(5) and Bing(10) providers so that {@code fanout=2} includes Brave
     * results when a key is present.
     * </p>
     */
    @Override
    public int priority() {
        return 7;
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        String text = query == null ? "" : query.text();
        final int effectiveCount = effectiveCount();
        if (text == null || text.isBlank()) {
            // Downstream safety pin: skip provider calls for blank queries.
            if (LOGGED_EMPTY_QUERY.compareAndSet(false, true)) {
                log.info("[SKIP_EMPTY_QUERY] BraveSearchProvider skipped (blank query){}", LogCorrelation.suffix());
            }
            traceBraveCounts(text, 0, 0, 0, false, null);
            TraceStore.put("web.brave.failureReason", "empty-query");
            return Mono.just(SearchBundle.empty());
        }

        if (!enabled) {
            if (LOGGED_MISSING_KEY.compareAndSet(false, true)) {
                log.info("[ProviderGuard] BraveSearchProvider disabled (config flag off){}", LogCorrelation.suffix());
            }
            traceBraveCounts(text, effectiveCount, 0, 0, true, "disabled_by_config");
            return Mono.just(SearchBundle.empty());
        }
        // When the shared quota guard is present the lane+token is pinned at
        // reservation time (inside the cache-miss branch below), so a request
        // that lands on the BASE lane is never blocked by the FREE-tier check
        // and a granted FREE reservation always carries the FREE token.
        BraveSearchService quotaGuard = braveQuotaGuard;
        String effectiveApiKey = apiKey;
        if (quotaGuard == null) {
        ProviderCredentialResolver resolver = credentialResolver;
        if (resolver != null) {
            ProviderCredentialResolver.Resolution free = resolver.resolveBraveFree();
            if (free.enabled() && !ConfigValueGuards.isMissing(free.valueOrNull())) {
                effectiveApiKey = free.valueOrNull();
            } else {
            ProviderCredentialResolver.Resolution resolution = resolver.resolve(
                    ProviderCredentialResolver.Provider.BRAVE);
            effectiveApiKey = resolution.valueOrNull();
            if (!resolution.enabled() && ConfigValueGuards.isMissing(effectiveApiKey)) {
                String reason = "missing-credential".equals(resolution.disabledReason())
                        ? "missing_brave_api_key"
                        : resolution.disabledReason();
                traceBraveCounts(text, effectiveCount, 0, 0, true, reason);
                return Mono.just(SearchBundle.empty());
            }
            }
        }
        }
        // Return empty bundle when no API key is configured.
        if (quotaGuard == null && ConfigValueGuards.isMissing(effectiveApiKey)) {
            if (LOGGED_MISSING_KEY.compareAndSet(false, true)) {
                log.warn("[ProviderGuard] BraveSearchProvider: 키 없음으로 disable (missing api key){}",
                        LogCorrelation.suffix());
            }
            traceBraveCounts(text, effectiveCount, 0, 0, true, "missing_brave_api_key");
            return Mono.just(SearchBundle.empty());
        }
        final String providerApiKey = effectiveApiKey;

        final int effectiveQps = Math.max(1, qps);
        final int effectiveTimeoutMs = (timeoutMs > 0 ? timeoutMs : Math.max(1, timeoutSec) * 1000);
        final long startedNs = System.nanoTime();
        final java.util.concurrent.atomic.AtomicBoolean failureRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);

        // baseUrl may include the endpoint path; split it to keep compatibility with
        // both styles.
        final String trimmed = (baseUrl == null || baseUrl.isBlank()) ? "https://api.search.brave.com" : baseUrl.trim();
        final String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        String base = trimmed;
        String path = "/res/v1/web/search";
        int idx = lower.indexOf("/res/v1/web/search");
        if (idx >= 0) {
            base = trimmed.substring(0, idx);
            path = trimmed.substring(idx);
        }
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            path = path.substring(0, qIdx);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isBlank()) {
            base = "https://api.search.brave.com";
        }
        if (path == null || path.isBlank()) {
            path = "/res/v1/web/search";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // remove a trailing slash to avoid accidental double slashes with
        // uriBuilder.path(...)
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        // Lambda 내에서 사용하기 위해 final 변수로 복사
        final String finalBase = base;
        final String finalPath = path;

        String cacheKey = "brv:" + text;
        // First attempt to serve from cache.
        return cache.get(cacheKey)
                .flatMap(opt -> {
                    if (opt.isPresent()) {
                        // Parse cached JSON into a SearchBundle
                        SearchBundle cached = BraveMapper.toBundle(opt.get());
                        traceBraveCounts(text, effectiveCount, docCount(cached), docCount(cached), false, null);
                        return Mono.just(cached);
                    }
                    // Enforce per-second QPS via the rate limiter. When the limit is exceeded
                    // an empty result is returned to allow the chain to continue.
                    return limiter.allow("brave:web", effectiveQps, Duration.ofSeconds(1))
                            .flatMap(allowed -> {
                                if (!allowed) {
                                    traceBraveFailure(text, effectiveCount, -1, "rate-limit", true, false,
                                            null, elapsedMs(startedNs));
                                    return Mono.just(SearchBundle.empty());
                                }
                                BraveSearchService.LaneReservation laneReservation =
                                        quotaGuard == null
                                                ? BraveSearchService.LaneReservation.unmanaged(providerApiKey)
                                                : quotaGuard.reserveForRequest();
                                BraveSearchService.QuotaReservation quotaReservation = laneReservation.reservation();
                                if (!quotaReservation.allowed()) {
                                    String reason = quotaGuard == null || quotaGuard.disabledReason() == null
                                            || quotaGuard.disabledReason().isBlank()
                                            ? "quota_exhausted"
                                            : quotaGuard.disabledReason();
                                    traceBraveCounts(text, effectiveCount, 0, 0, true, reason);
                                    return Mono.just(SearchBundle.empty());
                                }
                                String wireToken = laneReservation.token();
                                if (ConfigValueGuards.isMissing(wireToken)) {
                                    traceBraveCounts(text, effectiveCount, 0, 0, true, "missing_brave_api_key");
                                    return Mono.just(SearchBundle.empty());
                                }
                                TraceStore.put("web.brave.keyLane", laneReservation.freeLane() ? "free" : "base");
                                java.util.concurrent.atomic.AtomicBoolean quotaCompleted =
                                        new java.util.concurrent.atomic.AtomicBoolean(false);
                                // Execute the HTTP call to Brave. Use configured base URL,
                                // result count and timeout. Errors and timeouts yield empty responses.
                                return http.baseUrl(finalBase)
                                        .build()
                                        .get()
                                        .uri(u -> u.path(finalPath)
                                                .queryParam("q", text)
                                                .queryParam("count", effectiveCount)
                                                .build())
                                        .header("X-Subscription-Token", wireToken)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .timeout(Duration.ofMillis(effectiveTimeoutMs))
                                        .onErrorResume(ex -> {
                                            failureRecorded.set(true);
                                            if (quotaGuard != null && quotaCompleted.compareAndSet(false, true)) {
                                                if (ex instanceof WebClientResponseException w) {
                                                    quotaGuard.completeFreeTierQuota(quotaReservation, w.getHeaders(), false);
                                                } else if (!isTimeoutFailure(ex)) {
                                                    quotaGuard.releaseFreeTierQuota(quotaReservation);
                                                }
                                            }
                                            traceBraveFailure(text, effectiveCount, httpStatus(ex),
                                                    failureReason(ex), isRateLimited(ex), isTimeoutFailure(ex),
                                                    errorBody(ex), elapsedMs(startedNs));
                                            log.debug("BraveSearchProvider failed failureReason={} errorType={}{}",
                                                    failureReason(ex),
                                                    ex == null ? "unknown" : ex.getClass().getSimpleName(),
                                                    LogCorrelation.suffix());
                                            return Mono.empty();
                                        })
                                        .flatMap(json -> {
                                            if (json == null || json.isBlank()) {
                                                if (!failureRecorded.get()) {
                                                    traceBraveCounts(text, effectiveCount, 0, 0, false, null);
                                                }
                                                return Mono.empty();
                                            }
                                            // Write through to cache asynchronously then return JSON
                                            return cache.put(cacheKey, json, null).thenReturn(json);
                                        })
                                        .defaultIfEmpty("")
                                        .map(BraveMapper::toBundle)
                                        .doOnNext(bundle -> {
                                            if (!failureRecorded.get()) {
                                                if (quotaGuard != null && quotaCompleted.compareAndSet(false, true)) {
                                                    quotaGuard.completeFreeTierQuota(quotaReservation, null, true);
                                                }
                                                traceBraveCounts(text, effectiveCount, docCount(bundle),
                                                        docCount(bundle), false, null);
                                            }
                                        });
                            });
                })
                // Fallback: when the cache returns empty Mono
                .switchIfEmpty(Mono.just(SearchBundle.empty()));
    }

    private int effectiveCount() {
        return Math.min(20, Math.max(1, (count <= 0 ? 8 : count)));
    }

    private static int docCount(SearchBundle bundle) {
        return bundle == null || bundle.docs() == null ? 0 : bundle.docs().size();
    }

    private static long elapsedMs(long startedNs) {
        return Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L);
    }

    private static void traceBraveCounts(String query,
                                         int requestedCount,
                                         int returnedCount,
                                         int afterFilterCount,
                                         boolean providerDisabled,
                                         String disabledReason) {
        try {
            int requested = Math.max(0, requestedCount);
            int returned = Math.max(0, returnedCount);
            int after = Math.max(0, afterFilterCount);
            TraceStore.put("web.brave.requestedCount", requested);
            TraceStore.put("web.brave.returnedCount", returned);
            TraceStore.put("web.brave.afterFilterCount", after);
            TraceStore.put("web.brave.zeroResults", returned == 0);
            TraceStore.put("web.brave.afterFilterStarved", returned > 0 && after == 0);
            TraceStore.put("web.brave.providerDisabled", providerDisabled);
            TraceStore.put("web.brave.providerEmpty", !providerDisabled && returned == 0);
            TraceStore.put("web.brave.queryHash", query == null ? "" : SafeRedactor.hashValue(query));
            TraceStore.put("web.brave.queryLength", query == null ? 0 : query.length());
            TraceStore.put("web.brave.queryTokenBucket", queryTokenBucket(query));
            TraceStore.put("web.brave.httpStatus", null);
            TraceStore.put("web.brave.429", false);
            TraceStore.put("web.brave.rateLimited", false);
            TraceStore.put("web.brave.timeout", false);
            TraceStore.put("web.brave.cancelled", false);
            TraceStore.put("web.brave.failureReason",
                    classifyCountFailure(returned, after, providerDisabled));
            if (providerDisabled) {
                String reason = disabledReason == null || disabledReason.isBlank()
                        ? "disabled"
                        : disabledReason;
                String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
                TraceStore.put("web.brave.providerDisabled", true);
                TraceStore.put("web.brave.disabledReason", safeReason);
                TraceStore.put("web.brave.disabledReasonCanonical", safeReason);
                TraceStore.put("web.brave.skipped", true);
                TraceStore.put("web.brave.skipped.reason", safeReason);
            }
        } catch (Throwable ignore) {
            traceSuppressed("countsTrace", ignore);
        }
    }

    private static void traceBraveFailure(String query,
                                          int requestedCount,
                                          int httpStatus,
                                          String failureReason,
                                          boolean rateLimited,
                                          boolean timeout,
                                          String errorBody,
                                          long tookMs) {
        try {
            String safeReason = failureReason == null ? "unknown" : failureReason;
            boolean cancelled = "cancelled".equals(safeReason);
            traceBraveCounts(query, requestedCount, 0, 0, false, null);
            if (httpStatus > 0) {
                TraceStore.put("web.brave.httpStatus", httpStatus);
            }
            TraceStore.put("web.brave.429", httpStatus == 429);
            TraceStore.put("web.brave.rateLimited", rateLimited);
            TraceStore.put("web.brave.timeout", timeout);
            TraceStore.put("web.brave.cancelled", cancelled);
            if (cancelled) {
                TraceStore.put("web.brave.exceptionType", "cancelled");
            }
            TraceStore.put("web.brave.failureReason", safeReason);
            TraceStore.put("web.brave.tookMs", Math.max(0L, tookMs));
            if (rateLimited) {
                TraceStore.put("web.rateLimited", true);
            }
            traceErrorBody(errorBody);
            log.warn("[AWX][search][brave] acme-provider failure failureReason={} httpStatus={} queryHash={} queryLength={} bodyHash={} bodyLength={} tookMs={}",
                    safeReason,
                    httpStatus > 0 ? httpStatus : "none",
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    errorBody == null || errorBody.isBlank() ? "" : SafeRedactor.hashValue(errorBody),
                    errorBody == null ? 0 : errorBody.length(),
                    Math.max(0L, tookMs));
        } catch (Throwable ignore) {
            traceSuppressed("failureTrace", ignore);
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        try {
            TraceStore.put("web.brave.provider.suppressed.stage", safeStage);
            TraceStore.put("web.brave.provider.suppressed.errorType", errorType);
            TraceStore.put("web.brave.provider.suppressed." + safeStage, true);
            TraceStore.put("web.brave.provider.suppressed." + safeStage + ".errorType", errorType);
        } catch (Throwable traceFailure) {
            log.debug("[AWX][search][brave] telemetry suppression trace failed stage={} errorType={}",
                    safeStage,
                    traceFailure == null ? "unknown" : traceFailure.getClass().getSimpleName());
        }
    }

    private static void traceErrorBody(String body) {
        TraceStore.put("web.brave.errorBodyLength", body == null ? 0 : body.length());
        if (body == null || body.isBlank()) {
            return;
        }
        TraceStore.put("web.brave.errorBodyHash", SafeRedactor.hashValue(body));
    }

    private static String classifyCountFailure(int returned, int after, boolean providerDisabled) {
        if (providerDisabled) {
            return "provider-disabled";
        }
        if (returned <= 0) {
            return "provider-empty";
        }
        if (after <= 0) {
            return "after-filter-starvation";
        }
        return "none";
    }

    private static int httpStatus(Throwable t) {
        if (t instanceof WebClientResponseException w) {
            return w.getStatusCode().value();
        }
        return -1;
    }

    private static boolean isRateLimited(Throwable t) {
        return httpStatus(t) == 429;
    }

    private static String failureReason(Throwable t) {
        int status = httpStatus(t);
        if (status == 429) {
            return "rate-limit";
        }
        if (isCancellationFailure(t)) {
            return "cancelled";
        }
        if (isTimeoutFailure(t)) {
            return "timeout";
        }
        if (status > 0) {
            return "http-error";
        }
        return "exception";
    }

    private static String errorBody(Throwable t) {
        if (t instanceof WebClientResponseException w) {
            return w.getResponseBodyAsString();
        }
        return null;
    }

    private static boolean isTimeoutFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof java.util.concurrent.TimeoutException || t instanceof java.net.SocketTimeoutException) {
            return true;
        }
        String type = t.getClass().getName().toLowerCase(Locale.ROOT);
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        return type.contains("timeout") || msg.contains("timeout") || msg.contains("timed out");
    }

    private static boolean isCancellationFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.util.concurrent.CancellationException || cur instanceof InterruptedException) {
                return true;
            }
            String type = cur.getClass().getName().toLowerCase(Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(Locale.ROOT);
            if (type.contains("cancellationexception")
                    || msg.contains("cancelled")
                    || msg.contains("canceled")
                    || msg.contains("interrupted")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }
}
