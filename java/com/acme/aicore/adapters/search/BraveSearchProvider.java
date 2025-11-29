package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;




/**
 * Implementation of {@link WebSearchProvider} backed by the Brave search API.
 * This provider honours an API key supplied via configuration and uses a
 * reactive {@link WebClient} to perform HTTP requests.  When no API key is
 * configured the provider returns an empty bundle immediately.  Any
 * exceptions during the call are logged and result in an empty bundle.  The
 * default priority is higher than the built-in Bing and Naver providers so
 * that Brave results are preferred when available.
 */
@Component
@RequiredArgsConstructor
public class BraveSearchProvider implements WebSearchProvider {

    @Value("${search.brave.api-key:${brave.api.key:${BRAVE_API_KEY:}}}")
    private String apiKey;
    @Value("${search.brave.base-url:https://api.search.brave.com}")
    private String baseUrl;
    @Value("${search.brave.count:8}")
    private int count;
    @Value("${search.brave.timeout-ms:2500}")
    private int timeoutMs;
    @Value("${search.brave.qps:1}")
    private int qps;

    private final WebClient.Builder http;
    /**
     * Two-tier cache composed of an in-memory Caffeine tier and an optional
     * Upstash Redis backend.  Results are keyed by a deterministic prefix
     * with the raw query to maximise reuse.  When Upstash is disabled the
     * cache degenerates to the local tier only.
     */
    private final com.example.lms.infra.upstash.UpstashBackedWebCache cache;
    /**
     * Rate limiter backed by Upstash.  Applies a per-second quota to
     * Brave requests based on the {@code search.brave.qps} property.
     */
    private final com.example.lms.infra.upstash.UpstashRateLimiter limiter;

    @Override
    public String id() {
        return "brave";
    }

    /**
     * A higher priority (20) than typical providers ensures Brave is invoked
     * before others during fan-out when a key is present.
     *
     * @return the provider priority
     */
    @Override
    public int priority() {
        return 20;
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        // Return empty bundle when no API key is configured.
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just(SearchBundle.empty());
        }
        String text = query.text();
        String cacheKey = "brv:" + text;
        // First attempt to serve from cache.
        return cache.get(cacheKey)
                .flatMap(opt -> {
                    if (opt.isPresent()) {
                        // Parse cached JSON into a SearchBundle
                        return Mono.just(BraveMapper.toBundle(opt.get()));
                    }
                    // Enforce per-second QPS via the rate limiter.  When the limit is exceeded
                    // an empty result is returned to allow the chain to continue.
                    return limiter.allow("brave:web", qps, java.time.Duration.ofSeconds(1))
                            .flatMap(allowed -> {
                                if (!allowed) {
                                    return Mono.just(SearchBundle.empty());
                                }
                                // Execute the HTTP call to Brave.  Use configured base URL,
                                // result count and timeout.  Errors and timeouts yield empty responses.
                                return http.baseUrl(baseUrl)
                                        .build()
                                        .get()
                                        .uri(u -> u.path("/res/v1/web/search")
                                                .queryParam("q", text)
                                                .queryParam("count", count)
                                                .build())
                                        .header("X-Subscription-Token", apiKey)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .timeout(java.time.Duration.ofMillis(timeoutMs))
                                        .onErrorResume(ex -> Mono.empty())
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