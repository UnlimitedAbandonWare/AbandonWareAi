package com.acme.aicore.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;




/**
 * Central cache configuration.  Defines Caffeine caches for web search,
 * vector embeddings and reranking results.  A maximum size and TTL are set
 * conservatively to strike a balance between hit ratio and memory usage.
 */
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("webSearch", "embeddings", "rerank");
        manager.setCaffeine(Caffeine.newBuilder()
                // Enable recording of cache statistics such as hit ratio and load penalties.
                .recordStats()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30)));
        return manager;
    }
}