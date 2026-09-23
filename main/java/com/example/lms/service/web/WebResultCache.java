package com.example.lms.service.web;

import java.time.Duration;
import java.util.Optional;
import reactor.core.publisher.Mono;

public interface WebResultCache {
    Mono<Optional<String>> get(String key);

    Mono<Void> put(String key, String json, Duration ttl);
}
