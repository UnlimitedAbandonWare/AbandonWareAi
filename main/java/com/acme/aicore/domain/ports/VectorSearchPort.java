package com.acme.aicore.domain.ports;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.VectorQuery;
import reactor.core.publisher.Mono;



/**
 * Abstraction for vector search backends.  Implementations return a
 * {@link SearchBundle} containing vector search results for a query.
 */
public interface VectorSearchPort {
    Mono<SearchBundle> search(VectorQuery query);
}