package com.example.lms.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@EnableAspectJAutoProxy(proxyTargetClass = false)
@ConditionalOnMissingBean(CacheManager.class) // ★ 기본이 없을 때만 등록
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cm = new CaffeineCacheManager("chatResponses", "webSearch");
        cm.setCaffeine(Caffeine.newBuilder()
                .recordStats()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(512));
        cm.setAllowNullValues(false);
        return cm;
    }
}
