package com.example.lms.config;

import com.example.lms.telemetry.SseEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class TelemetryConfig {

    @Bean
    @ConditionalOnMissingBean(SseEventPublisher.class)
    public SseEventPublisher fallbackSseEventPublisher() {
        // 익명 클래스로 No-Op 구현
        return new SseEventPublisher() {
            @Override public void emit(String type, Object payload) { /* no-op */ }
        };
    }
}