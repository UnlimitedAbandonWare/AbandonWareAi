package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedWebSearchTest {

    @Test
    void zeroFanoutReturnsEmptyWithoutCallingProviders() {
        AtomicInteger calls = new AtomicInteger();
        WebSearchProvider provider = new WebSearchProvider() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                calls.incrementAndGet();
                return Mono.just(new SearchBundle("web", List.of(
                        new SearchBundle.Doc("doc-1", "Title", "Snippet", "https://example.test", ""))));
            }
        };

        SearchBundle bundle = new CachedWebSearch(List.of(provider))
                .searchMulti(new WebSearchQuery("query"), 0)
                .block();

        assertEquals(0, calls.get());
        assertEquals("empty", bundle.type());
        assertTrue(bundle.docs().isEmpty());
    }
}
