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
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
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
        return CircuitBreaker.of("llm", config);
    }

    @Bean
    public TimeLimiter llmTimeLimiter() {
        return TimeLimiter.of(Duration.ofSeconds(20));
    }
}
