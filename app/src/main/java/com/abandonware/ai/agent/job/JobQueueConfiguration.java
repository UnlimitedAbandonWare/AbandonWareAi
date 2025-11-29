package com.abandonware.ai.agent.job;

import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.consent.RedisConsentService;
import com.abandonware.ai.agent.job.redis.RedisJobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.job.JobQueueConfiguration
 * Role: config
 * Dependencies: com.abandonware.ai.agent.consent.ConsentService, com.abandonware.ai.agent.consent.RedisConsentService, com.abandonware.ai.agent.job.redis.RedisJobQueue, +1 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.job.JobQueueConfiguration
role: config
*/
public class JobQueueConfiguration {

    @Bean
    @ConditionalOnProperty(name="jobs.redis.enabled", havingValue="true", matchIfMissing=true)
    public JobQueue redisJobQueue(StringRedisTemplate redis, ObjectMapper om) {
        return new RedisJobQueue(redis, om);
    }

    @Bean
    @ConditionalOnMissingBean(JobQueue.class)
    public JobQueue inMemoryJobQueue() {
        return new InMemoryJobQueue();
    }

    @Bean
    @ConditionalOnProperty(name="consent.redis.enabled", havingValue="true", matchIfMissing=true)
    public ConsentService redisConsentService(StringRedisTemplate redis, ObjectMapper om) {
        return new RedisConsentService(redis, om);
    }
}