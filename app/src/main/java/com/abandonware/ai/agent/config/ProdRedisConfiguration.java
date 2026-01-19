package com.abandonware.ai.agent.config;

import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.consent.RedisConsentService;
import com.abandonware.ai.agent.job.JobQueue;
import com.abandonware.ai.agent.job.redis.RedisJobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;



@Configuration
@Profile("prod")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.config.ProdRedisConfiguration
 * Role: config
 * Dependencies: com.abandonware.ai.agent.consent.ConsentService, com.abandonware.ai.agent.consent.RedisConsentService, com.abandonware.ai.agent.job.JobQueue, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.config.ProdRedisConfiguration
role: config
*/
public class ProdRedisConfiguration {

    @Bean
    @Primary
    public JobQueue jobQueue(StringRedisTemplate redis, ObjectMapper objectMapper){
        return new RedisJobQueue(redis, objectMapper);
    }

    @Bean
    @Primary
    public ConsentService consentService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisConsentService(redis, objectMapper);
    }
}