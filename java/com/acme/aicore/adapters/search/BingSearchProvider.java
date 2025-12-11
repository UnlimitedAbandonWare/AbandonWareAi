package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;




/**
 * Minimal shim of a Bing search provider.  In a real implementation this
 * provider would invoke the Bing API via WebClient and convert the response
 * into a {@link SearchBundle}.  The priority is set high so that this
 * provider is invoked first when fanning out.
 */
@Component
public class BingSearchProvider implements WebSearchProvider {
    @Override
    public String id() {
        return "bing";
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        // Return an empty bundle for the purposes of this skeleton
        return Mono.just(new SearchBundle("web", List.of()));
    }

    @Override
    public int priority() {
        return 10;
    }
}