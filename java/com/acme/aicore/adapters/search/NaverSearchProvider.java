package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;




/**
 * Minimal shim of a Naver search provider used for testing caching and
 * provider fan-out.  Returns an empty {@link SearchBundle} and assigns a
 * lower priority than Bing.
 */
@Component
public class NaverSearchProvider implements WebSearchProvider {
    @Override
    public String id() {
        return "naver";
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        return Mono.just(new SearchBundle("web", List.of()));
    }

    @Override
    public int priority() {
        return 5;
    }
}