package com.example.lms.infra.upstash;

import com.example.lms.service.web.WebResultCache;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * Two‑tier cache combining a local Caffeine cache with Upstash Redis.  The
 * in‑memory tier provides fast access to recently used entries while the
 * Upstash tier allows sharing cached results across instances.  All values
 * are stored as JSON strings keyed by an externally supplied hash.  When
 * Upstash is disabled the cache degenerates to the local tier only.
 */
@Component
@RequiredArgsConstructor
public class UpstashBackedWebCache implements WebResultCache {
    private final Cache<String, String> local;
    private final UpstashRedisClient upstash;
    @Value("${upstash.cache.ttl-seconds:600}")
    private int ttlSec;

    @Override
    public Mono<Optional<String>> get(String key) {
        // Check local cache first
        String v = local.getIfPresent(key);
        if (v != null) {
            return Mono.just(Optional.of(v));
        }
        // Fallback to remote cache
        return upstash.get(key)
                .map(res -> {
                    if (res != null) local.put(key, res);
                    return Optional.ofNullable(res);
                });
    }

    @Override
    public Mono<Void> put(String key, String json, Duration ttl) {
        local.put(key, json);
        Duration duration = (ttl == null ? Duration.ofSeconds(ttlSec) : ttl);
        return upstash.setEx(key, json, duration).then();
    }
}