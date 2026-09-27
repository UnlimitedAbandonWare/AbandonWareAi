package com.example.lms.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

/**
 * Legacy compatibility decorator. Gemini resilience now belongs to
 * {@link com.example.lms.learning.gemini.GeminiGateway}; this class is not a
 * Spring bean and cannot become a competing wire owner.
 */
@Deprecated
public class GeminiClientDecorator {
    private static final Logger log = LoggerFactory.getLogger(GeminiClientDecorator.class);

    private final GeminiClient delegate;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService llmFastExecutor;

    @Autowired
    public GeminiClientDecorator(GeminiClient delegate,
            WebClient.Builder webClientBuilder,
            com.example.lms.guard.KeyResolver keyResolver,
            @Qualifier("llmFastExecutor") ExecutorService llmFastExecutor,
            TimeLimiterRegistry timeLimiterRegistry,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.delegate = delegate;
        this.llmFastExecutor = llmFastExecutor;
        this.timeLimiter = timeLimiterRegistry.timeLimiter("geminiKeyword");
        this.retry = retryRegistry.retry("geminiKeyword");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("geminiKeyword");
    }

    public List<String> keywordVariants(String cleaned, String anchor, int cap) {
        // base supplier
        Supplier<List<String>> base = () -> delegate.keywordVariants(cleaned, anchor, cap);
        // retry -> circuit-breaker
        Supplier<List<String>> withRetry = io.github.resilience4j.retry.Retry.decorateSupplier(retry, base);
        Supplier<List<String>> withCb = io.github.resilience4j.circuitbreaker.CircuitBreaker
                .decorateSupplier(circuitBreaker, withRetry);
        // time limiter는 Future 공급자를 요구 → 비동기 래핑
        Callable<List<String>> timed = io.github.resilience4j.timelimiter.TimeLimiter
                .decorateFutureSupplier(timeLimiter, () -> CompletableFuture.supplyAsync(withCb, llmFastExecutor));
        try {
            return timed.call();
        } catch (Exception e) {
            traceKeywordVariantFailure(cleaned, anchor, cap, e);
            throw new RuntimeException("GeminiClient keywordVariants timed out or failed", e);
        }
    }

    private static void traceKeywordVariantFailure(String cleaned, String anchor, int cap, Throwable failure) {
        try {
            TraceStore.put("gemini.keywordVariants.failed", true);
            TraceStore.put("gemini.keywordVariants.cap", cap);
            traceText("gemini.keywordVariants.query", cleaned);
            traceText("gemini.keywordVariants.anchor", anchor);
            TraceStore.put("gemini.keywordVariants.errorType", errorType(failure));
            TraceStore.put("gemini.keywordVariants.errorHash", SafeRedactor.hashValue(messageOf(failure)));
            TraceStore.put("gemini.keywordVariants.errorLength", messageLength(failure));
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][gemini][keywordVariants] failure trace skipped errorType={}",
                    errorType(traceFailure));
        }
    }

    private static void traceText(String prefix, String value) {
        if (value == null || value.isBlank()) {
            TraceStore.put(prefix + "Hash", "hash:");
            TraceStore.put(prefix + "Length", 0);
            return;
        }
        TraceStore.put(prefix + "Hash", SafeRedactor.hashValue(value));
        TraceStore.put(prefix + "Length", value.length());
    }

    private static String errorType(Throwable failure) {
        return failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String messageOf(Throwable failure) {
        return failure == null ? null : failure.getMessage();
    }

    private static int messageLength(Throwable failure) {
        String message = messageOf(failure);
        return message == null ? 0 : message.length();
    }
}
