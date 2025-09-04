package com.acme.aicore.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary; // ★ 추가

import java.time.Duration;

@Configuration
@EnableCaching
@org.springframework.context.annotation.Profile("acme")
public class CacheConfig {

    @Bean
    @Primary // ★ 기본 CacheManager는 이걸로 강제 고정
    public CacheManager cacheManager() {
        CaffeineCacheManager manager =
                new CaffeineCacheManager("webSearch", "embeddings", "rerank", "chatResponses");
        manager.setCaffeine(Caffeine.newBuilder()
                .recordStats()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30)));
        return manager;
    }
}
