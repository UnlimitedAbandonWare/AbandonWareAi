package com.example.lms.config;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;




@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker llmCircuitBreaker() {
        var cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(ex ->
                        ex instanceof HttpException
                                || ex instanceof InternalServerException
                                || ex instanceof TimeoutException
                                || ex.getCause() instanceof ConnectException)
                .build();
        return CircuitBreaker.of("llm", cfg);
    }

    @Bean
    public TimeLimiter llmTimeLimiter() {
        // LLM 연결·응답 지연 상한
        return TimeLimiter.of(Duration.ofSeconds(20));
    }
}