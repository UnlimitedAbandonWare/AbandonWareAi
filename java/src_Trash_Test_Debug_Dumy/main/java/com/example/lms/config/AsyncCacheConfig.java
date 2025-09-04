package com.example.lms.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Reactor(Mono/Flux) 반환형 전용 Async 캐시 매니저.
 * webSearch / embeddings / rerank 등 비동기 캐시 이름을 이 매니저가 관리한다.
 */
@Configuration
public class AsyncCacheConfig {

    @Bean(name = "asyncCaffeineCacheManager")
    public CaffeineCacheManager asyncCaffeineCacheManager() {
        CaffeineCacheManager cm = new CaffeineCacheManager("webSearch", "embeddings", "rerank", "jammini");
        cm.setCaffeine(Caffeine.newBuilder()
                .recordStats()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10_000));
        cm.setAllowNullValues(false);
        cm.setAsyncCacheMode(true); // ★ 핵심: Reactive 캐싱은 반드시 Async 모드
        return cm;
    }
}
