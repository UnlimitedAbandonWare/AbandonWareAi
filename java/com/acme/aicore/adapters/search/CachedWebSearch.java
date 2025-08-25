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
        return Flux.fromIterable(providers)
                .sort(Comparator.comparingInt(WebSearchProvider::priority).reversed())
                .flatMap(p -> p.search(query))
                .take(fanout)
                .collectList()
                .map(SearchBundle::merge);
    }
}