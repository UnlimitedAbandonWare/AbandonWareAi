package com.acme.aicore.domain.ports;

public interface WebSearchProvider {
    String id();
    reactor.core.publisher.Mono<com.acme.aicore.domain.model.SearchBundle> search(
        com.acme.aicore.domain.model.WebSearchQuery query
    );
    default int priority() { return 0; }
    default boolean isEnabled() { return true; }
}
