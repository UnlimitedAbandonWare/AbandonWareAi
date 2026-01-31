package com.example.lms.service.web;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Optional;




/**
 * Interface for a web result cache.  Caching web search results reduces
 * latency and external API usage by storing JSON responses keyed by a
 * deterministic hash of the search parameters.  Implementations may
 * combine a fast in-memory tier with a distributed backend for
 * persistence across instances.
 */
public interface WebResultCache {
    /**
     * Retrieve a cached JSON result for the given key.
     *
     * @param key cache key (e.g. SHA-256 hash of query and filters)
     * @return a Mono emitting an {@link Optional} containing the cached
     *         JSON string when present, or an empty Optional when missing
     */
    Mono<Optional<String>> get(String key);

    /**
     * Store a JSON result under the given key with the specified TTL.  When
     * the TTL is {@code null} the implementation should fall back to a
     * sensible default.
     *
     * @param key  cache key
     * @param json JSON payload to store
     * @param ttl  time to live; may be null
     * @return a Mono that completes when the put operation has been issued
     */
    Mono<Void> put(String key, String json, Duration ttl);
}