package com.example.lms.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class WebInfraConfig {

    @Bean
    public Cache<String, String> webLocalCache() {
        return Caffeine.newBuilder()
                .recordStats()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }
}
