package com.example.lms.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;




@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cm = new CaffeineCacheManager("chatResponses");
        cm.setCaffeine(Caffeine.newBuilder()
                // Enable recording of cache statistics. This aids in debugging latency and hit ratio issues.
                .recordStats()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500));
        return cm;
    }
}