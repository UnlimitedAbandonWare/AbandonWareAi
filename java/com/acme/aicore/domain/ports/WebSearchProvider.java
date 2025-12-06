package com.acme.aicore.domain.ports;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import reactor.core.publisher.Mono;



/**
 * Abstraction for web search providers.  Each provider exposes a unique
 * identifier and returns a {@link SearchBundle} when executing a query.
 * Providers may assign themselves a priority; higher priority providers
 * will be invoked first during fan-out.
 */
public interface WebSearchProvider {
    String id();
    Mono<SearchBundle> search(WebSearchQuery query);
    default int priority() { return 0; }
}