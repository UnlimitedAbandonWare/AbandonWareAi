package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import java.util.List;




import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link CachedWebSearch}.  Ensures that identical queries
 * within the TTL are served from the cache and that providers are only
 * invoked once.
 */
public class CachedWebSearchTest {
    @Test
    void cachesSameQuery() {
        // Create spied providers
        WebSearchProvider bing = Mockito.spy(new BingSearchProvider());
        WebSearchProvider naver = Mockito.spy(new NaverSearchProvider());
        CachedWebSearch service = new CachedWebSearch(List.of(bing, naver));
        // Perform two identical searches
        service.searchMulti(new WebSearchQuery("스프링 WebClient"), 2).block();
        service.searchMulti(new WebSearchQuery("스프링 WebClient"), 2).block();
        // Verify each provider invoked only once
        verify(bing, times(1)).search(any());
        verify(naver, times(1)).search(any());
    }
}