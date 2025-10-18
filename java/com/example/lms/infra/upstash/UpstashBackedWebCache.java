package com.example.lms.infra.upstash;

import java.util.concurrent.*;
import com.example.lms.service.web.WebResultCache;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * Two‑tier cache combining a local Caffeine cache with Upstash Redis.
 * The in‑memory tier provides fast access to recently used entries while
 * the Upstash tier allows sharing cached results across instances.
 * All values are stored as JSON strings keyed by a deterministic hash.
 * When Upstash is disabled the cache degenerates to the local tier only.
 */
@Component
@RequiredArgsConstructor
public class UpstashBackedWebCache implements WebResultCache {
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    private final Cache<String, String> local;
    private final UpstashRedisClient upstash;

    @Value("${upstash.cache.ttl-seconds:600}")
    private int ttlSec;

    @Override
    public Mono<Optional<String>> get(String key) {
        // Try local cache first
        String cached = local.getIfPresent(key);
        if (cached != null) {
            return Mono.just(Optional.of(cached));
        }
        if (!upstash.enabled()) {
            return Mono.just(Optional.empty());
        }
        // Fallback to Upstash
        return upstash.get(key).map(res -> {
            if (res != null) {
                local.put(key, res);
            }
            return Optional.ofNullable(res);
        });
    }

    @Override
    public Mono<Void> put(String key, String json, Duration ttl) {
        local.put(key, json);
        Duration effectiveTtl = (ttl == null ? Duration.ofSeconds(ttlSec) : ttl);
        if (!upstash.enabled()) {
            return Mono.empty();
        }
        return upstash.setEx(key, json, effectiveTtl).then();
    }

    public String getSingleFlight(String key, java.util.function.Supplier<String> remoteFetcher) {
        CompletableFuture<String> fut = inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    return remoteFetcher.get();
                } finally {
                    inFlight.remove(k);
                }
            })
        );
        try {
            return fut.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            inFlight.remove(key);
            return remoteFetcher.get();
        }
    }
    
}
