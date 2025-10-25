package com.example.lms.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.example.lms.learning.gemini.GeminiClient;



/**
 * Decorates a {@link GeminiClient} with Resilience4j policies.  The decorator
 * extends {@link GeminiClient} and therefore retains all original behaviour
 * while wrapping the {@link #keywordVariants(String, String, int)} method
 * with a time limiter, retry and circuit breaker.  This bean is marked
 * as {@code @Primary} so that it will be selected over the base
 * {@code GeminiClient} when multiple candidates are present.
 */
@Component
@Primary
public class GeminiClientDecorator extends GeminiClient {

    private final GeminiClient delegate;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public GeminiClientDecorator(GeminiClient delegate,
                                 WebClient.Builder webClientBuilder,
                                 TimeLimiterRegistry timeLimiterRegistry,
                                 RetryRegistry retryRegistry,
                                 CircuitBreakerRegistry circuitBreakerRegistry) {
        super(webClientBuilder);
        this.delegate = delegate;
        this.timeLimiter = timeLimiterRegistry.timeLimiter("geminiKeyword");
        this.retry = retryRegistry.retry("geminiKeyword");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("geminiKeyword");
    }

    @Override
    public List<String> keywordVariants(String cleaned, String anchor, int cap) {
        // base supplier
        Supplier<List<String>> base = () -> delegate.keywordVariants(cleaned, anchor, cap);
        // retry -> circuit-breaker
        Supplier<List<String>> withRetry = io.github.resilience4j.retry.Retry.decorateSupplier(retry, base);
        Supplier<List<String>> withCb    = io.github.resilience4j.circuitbreaker.CircuitBreaker
                .decorateSupplier(circuitBreaker, withRetry);
        // time limiter는 Future 공급자를 요구 → 비동기 래핑
        Callable<List<String>> timed = io.github.resilience4j.timelimiter.TimeLimiter
                .decorateFutureSupplier(timeLimiter, () -> CompletableFuture.supplyAsync(withCb));
        try {
            return timed.call();
        } catch (Exception e) {
            throw new RuntimeException("GeminiClient keywordVariants timed out or failed", e);
        }
    }
}