package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.trace.LogCorrelation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;

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

    @Value("${gpt-search.brave.subscription-token:${gpt-search.brave.api-key:${search.brave.subscription-token:${search.brave.api-key:${brave.subscription.token:${brave.api.key:${GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN:${GPT_SEARCH_BRAVE_API_KEY:${BRAVE_SUBSCRIPTION_TOKEN:${BRAVE_API_KEY:__MISSING__}}}}}}}}}}")
    private String apiKey;

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
        if (text == null || text.isBlank()) {
            // Downstream safety pin: skip provider calls for blank queries.
            if (LOGGED_EMPTY_QUERY.compareAndSet(false, true)) {
                log.info("[SKIP_EMPTY_QUERY] BraveSearchProvider skipped (blank query){}", LogCorrelation.suffix());
            }
            return Mono.just(SearchBundle.empty());
        }

        if (!enabled) {
            if (LOGGED_MISSING_KEY.compareAndSet(false, true)) {
                log.info("[ProviderGuard] BraveSearchProvider disabled (config flag off){}", LogCorrelation.suffix());
            }
            return Mono.just(SearchBundle.empty());
        }
        // Return empty bundle when no API key is configured.
        if (ConfigValueGuards.isMissing(apiKey)) {
            if (LOGGED_MISSING_KEY.compareAndSet(false, true)) {
                log.warn("[ProviderGuard] BraveSearchProvider: 키 없음으로 disable (missing api key){}",
                        LogCorrelation.suffix());
            }
            return Mono.just(SearchBundle.empty());
        }

        final int effectiveQps = Math.max(1, qps);
        final int effectiveCount = Math.min(20, Math.max(1, (count <= 0 ? 8 : count)));
        final int effectiveTimeoutMs = (timeoutMs > 0 ? timeoutMs : Math.max(1, timeoutSec) * 1000);

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
                        return Mono.just(BraveMapper.toBundle(opt.get()));
                    }
                    // Enforce per-second QPS via the rate limiter. When the limit is exceeded
                    // an empty result is returned to allow the chain to continue.
                    return limiter.allow("brave:web", effectiveQps, Duration.ofSeconds(1))
                            .flatMap(allowed -> {
                                if (!allowed) {
                                    return Mono.just(SearchBundle.empty());
                                }
                                // Execute the HTTP call to Brave. Use configured base URL,
                                // result count and timeout. Errors and timeouts yield empty responses.
                                return http.baseUrl(finalBase)
                                        .build()
                                        .get()
                                        .uri(u -> u.path(finalPath)
                                                .queryParam("q", text)
                                                .queryParam("count", effectiveCount)
                                                .build())
                                        .header("X-Subscription-Token", apiKey)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .timeout(Duration.ofMillis(effectiveTimeoutMs))
                                        .onErrorResume(ex -> {
                                            log.debug("BraveSearchProvider failed: {}{}", ex.toString(),
                                                    LogCorrelation.suffix());
                                            return Mono.empty();
                                        })
                                        .flatMap(json -> {
                                            if (json == null || json.isBlank()) {
                                                return Mono.empty();
                                            }
                                            // Write through to cache asynchronously then return JSON
                                            return cache.put(cacheKey, json, null).thenReturn(json);
                                        })
                                        .defaultIfEmpty("")
                                        .map(BraveMapper::toBundle);
                            });
                })
                // Fallback: when the cache returns empty Mono
                .switchIfEmpty(Mono.just(SearchBundle.empty()));
    }
}