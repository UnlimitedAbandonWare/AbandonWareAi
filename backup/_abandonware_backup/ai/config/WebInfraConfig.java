package com.abandonware.ai.config;

import com.abandonware.ai.infra.upstash.UpstashBackedWebCache;
import com.abandonware.ai.infra.upstash.UpstashRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.config.WebInfraConfig
 * Role: config
 * Dependencies: com.abandonware.ai.infra.upstash.UpstashBackedWebCache, com.abandonware.ai.infra.upstash.UpstashRateLimiter
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.config.WebInfraConfig
role: config
*/
public class WebInfraConfig {
    @Bean
    public UpstashBackedWebCache upstashBackedWebCache() { return new UpstashBackedWebCache(); }
    @Bean
    public UpstashRateLimiter upstashRateLimiter() { return new UpstashRateLimiter(); }
}