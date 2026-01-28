package com.example.lms.infra.upstash;

import com.example.lms.service.web.WebResultCache;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.github.benmanes.caffeine.cache.Cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Two-tier web cache: local Caffeine first, optional Upstash Redis second.
 * This implementation is small and self-contained to prevent compile issues.
 */
@Component
public class UpstashBackedWebCache implements WebResultCache {

    private final Cache<String, String> local;
    private final UpstashRedisClient upstash;

    @Value("${upstash.cache.ttl-seconds:600}")
    private int defaultTtlSeconds;

    @Autowired
    public UpstashBackedWebCache(Cache<String,String> webLocalCache, UpstashRedisClient upstash) {
        this.local = webLocalCache;
        this.upstash = upstash;
    }

    @Override
    public Mono<Optional<String>> get(String key) {
        try {
            String v = local.getIfPresent(key);
            if (v != null) return Mono.just(Optional.of(v));
        } catch (Throwable ignore) {}
        // remote
        return upstash.get(key)
                .map(Optional::ofNullable)
                .doOnNext(opt -> opt.ifPresent(val -> {
                    try { local.put(key, val); } catch (Throwable ignore) {}
                }))
                .onErrorReturn(Optional.empty());
    }

    @Override
    public Mono<Void> put(String key, String json, Duration ttl) {
        try { local.put(key, json); } catch (Throwable ignore) {}
        Duration useTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(defaultTtlSeconds)
                : ttl;
        return upstash.setEx(key, json, useTtl)
                .then();
    }
}