package com.example.lms.client;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiClientSecurityTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void placeholderApiKeyReturnsEmptyModelsWithoutOutboundCall() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"data\":[{\"id\":\"gpt-test\",\"owned_by\":\"owner\"}]}")
                            .build());
                })
                .build();
        OpenAiClient openAiClient = new OpenAiClient(client, "sk-local", "https://api.openai.com");

        assertTrue(openAiClient.listModels().isEmpty());
        assertEquals(0, calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("openai.models.providerDisabled"));
        assertEquals("missing_openai_api_key", TraceStore.get("openai.models.disabledReason"));
    }

    @Test
    void listModelsFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/client/OpenAiClient.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"openai.models.suppressed\", true);"));
        assertTrue(source.contains("TraceStore.put(\"openai.models.failureClass\", \"list_models_failed\");"));
    }

    @Test
    void listModelsFailureLeavesStableErrorTypeWithoutRawExceptionMessage() {
        String raw = "ownerToken=secret-openai-models";
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException(raw)))
                .build();
        OpenAiClient openAiClient = new OpenAiClient(client, "sk-test-key", "https://api.openai.com");

        assertTrue(openAiClient.listModels().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("openai.models.suppressed"));
        assertEquals("list_models_failed", TraceStore.get("openai.models.failureClass"));
        assertEquals("listModels", TraceStore.get("openai.models.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("openai.models.suppressed.errorType"));
        assertTrue(String.valueOf(TraceStore.get("openai.models.suppressed.messageHash")).startsWith("hash:"));
        assertEquals(raw.length(), TraceStore.get("openai.models.suppressed.messageLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }
}
