package com.example.lms.client;

import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientDecoratorTest {

    @BeforeEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void keywordVariantFailureLeavesRedactedTraceBeforeWrapping() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            GeminiClientDecorator decorator = new GeminiClientDecorator(
                    new FailingGeminiClient(),
                    WebClient.builder(),
                    new KeyResolver(new MockEnvironment()),
                    executor,
                    TimeLimiterRegistry.ofDefaults(),
                    RetryRegistry.ofDefaults(),
                    CircuitBreakerRegistry.ofDefaults());

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> decorator.keywordVariants(
                            "raw query api_key=raw-secret",
                            "anchor ownerToken=raw-secret",
                            3));

            assertTrue(thrown.getMessage().contains("GeminiClient keywordVariants timed out or failed"));
            assertTrue(String.valueOf(TraceStore.get("gemini.keywordVariants.errorHash")).startsWith("hash:"));
            assertInstanceOf(Integer.class, TraceStore.get("gemini.keywordVariants.errorLength"));
            assertTrue((Integer) TraceStore.get("gemini.keywordVariants.errorLength") > 0);
            assertTrue(String.valueOf(TraceStore.get("gemini.keywordVariants.queryHash")).startsWith("hash:"));
            assertTrue(String.valueOf(TraceStore.get("gemini.keywordVariants.anchorHash")).startsWith("hash:"));
            assertTrue(String.valueOf(TraceStore.get("gemini.keywordVariants.errorType")).length() > 0);
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("raw-secret"));
            assertFalse(trace.contains("api_key="));
            assertFalse(trace.contains("ownerToken="));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FailingGeminiClient extends GeminiClient {
        private FailingGeminiClient() {
            super(gateway());
        }

        @Override
        public List<String> keywordVariants(String cleaned, String anchor, int cap) {
            throw new IllegalStateException("api_key=raw-secret ownerToken=raw-secret");
        }

        private static GeminiGateway gateway() {
            MockEnvironment environment = new MockEnvironment();
            return new GeminiGateway(
                    WebClient.builder(),
                    new ProviderCredentialResolver(environment),
                    environment);
        }
    }
}
