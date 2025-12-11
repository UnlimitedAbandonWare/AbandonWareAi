package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Comparator;
import java.util.List;




/**
 * Aggregates multiple web search providers and caches the merged result.
 * The caching layer uses the "webSearch" cache defined in {@link com.acme.aicore.config.CacheConfig}.
 * Fan-out across providers is controlled by the caller; only the top providers
 * according to {@link WebSearchProvider#priority()} are invoked.
 */
@Component
public class CachedWebSearch {
    private final List<WebSearchProvider> providers;

    public CachedWebSearch(List<WebSearchProvider> providers) {
        this.providers = providers;
    }

    @Cacheable(value = "webSearch", key = "#query.text() + ':' + #fanout")
    public Mono<SearchBundle> searchMulti(WebSearchQuery query, int fanout) {
        // When aggregating providers use the highest priority providers first.  Only the top
        // `fanout` providers are invoked to reduce unnecessary outbound calls.  The
        // flatMap concurrency parameter further limits concurrent invocation to the
        // same number of providers to avoid excessive parallelism.  The call order
        // is preserved by sorting prior to taking the subset.  Once all results
        // are collected they are merged into a single SearchBundle.
        return Flux.fromIterable(providers)
                // Sort providers by ascending priority so that lower values imply higher precedence.
                // With priorities Naver(5) → Bing(10) → Brave(20), this yields the desired order.
                .sort(Comparator.comparingInt(WebSearchProvider::priority))
                .take(fanout) // 상위 N만 실제 호출
                .flatMap(p -> p.search(query), fanout) // 동시성 = fanout
                .collectList()
                .map(SearchBundle::merge);
    }
}