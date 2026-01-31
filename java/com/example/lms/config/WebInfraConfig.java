package com.example.lms.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;




/**
 * Configuration for web infrastructure components.  Defines a standalone
 * Caffeine cache for caching web search results when backed by Upstash.  The
 * cache is configured with a generous size and TTL and records statistics
 * for observability.  When Upstash is disabled this cache still provides
 * in-memory caching.
 */
@Configuration
public class WebInfraConfig {

    /**
     * Local Caffeine cache used by {@link com.example.lms.infra.upstash.UpstashBackedWebCache}
     * as the first tier.  Values are stored as raw JSON strings keyed by a
     * hash of the search parameters.
     *
     * @return an empty Caffeine cache
     */
    @Bean
    public Cache<String, String> webLocalCache() {
        return Caffeine.newBuilder()
                .recordStats()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }
}