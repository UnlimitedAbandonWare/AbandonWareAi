package com.abandonware.ai.config;

import com.abandonware.ai.infra.upstash.UpstashBackedWebCache;
import com.abandonware.ai.infra.upstash.UpstashRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebInfraConfig {
    @Bean
    public UpstashBackedWebCache upstashBackedWebCache() { return new UpstashBackedWebCache(); }
    @Bean
    public UpstashRateLimiter upstashRateLimiter() { return new UpstashRateLimiter(); }
}