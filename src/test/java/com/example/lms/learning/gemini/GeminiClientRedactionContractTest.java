package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientRedactionContractTest {

    @Test
    void translateFailureFallbackRedactsSecretLikeInputText() {
        GeminiClient client = client(new MockEnvironment(), request -> Mono.error(new AssertionError("no wire")));
        String rawKey = "sk-" + "1234567890abcdef1234";

        String result = client.translate("translate " + rawKey, "en", "ko").block();

        assertTrue(result.contains("[translation failed]"), result);
        assertTrue(result.contains("translate "), result);
        assertTrue(result.contains("***"), result);
        assertFalse(result.contains(rawKey), result);
        assertFalse(result.contains("1234567890abcdef1234"), result);
    }

    @Test
    void generateFailureFallbackUsesHashAndLengthOnly() {
        GeminiClient client = client(new MockEnvironment(), request -> Mono.error(new AssertionError("no wire")));

        String result = client.generate("prompt with ownerToken=raw-secret").block();

        assertTrue(result.contains("\"ok\"   : false"), result);
        assertTrue(result.contains("\"errorHash\""), result);
        assertTrue(result.contains("\"errorLength\""), result);
        assertFalse(result.contains("Gemini API key missing"), result);
        assertFalse(result.contains("ownerToken"), result);
    }

    @Test
    void keywordVariantFailureFallsBackWithTraceHashAndLengthOnly() {
        TraceStore.clear();
        try {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("gemini.api-key", "AIza" + "1234567890abcdef1234567890")
                    .withProperty("gemini.gateway.preflight.enabled", "false")
                    .withProperty("gemini.gateway.purpose.keyword-training.enabled", "true");
            GeminiClient client = client(
                    env,
                    request -> Mono.error(new IllegalStateException("ownerToken=raw-secret")));

            GeminiClient.KeywordVariantsResult result = client.keywordVariantsWithMeta(
                    "prompt with ownerToken=raw-secret",
                    "anchor",
                    2,
                    java.time.Duration.ofMillis(50));

            assertTrue(result.variants().isEmpty());
            assertEquals("IllegalStateException", TraceStore.get("provider.status.gemini.errorClass"));
            assertEquals("provider-error", TraceStore.get("provider.status.gemini.fallbackReason"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
            assertFalse(trace.contains("1234567890abcdef1234567890"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    private static GeminiClient client(
            MockEnvironment environment,
            org.springframework.web.reactive.function.client.ExchangeFunction exchangeFunction) {
        environment.withProperty("gemini.gateway.enabled", "true");
        GeminiGateway gateway = new GeminiGateway(
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ProviderCredentialResolver(environment),
                environment);
        return new GeminiClient(gateway);
    }
}
