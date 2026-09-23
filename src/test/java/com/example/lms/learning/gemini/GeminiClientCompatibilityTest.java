package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.guard.ProviderCredentialResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientCompatibilityTest {

    @Test
    void translationUnderstandingAndKeywordApisUseGatewayPurposeModels() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-facade-test-value")
                .withProperty("gemini.gateway.enabled", "true")
                .withProperty("gemini.gateway.preflight.enabled", "false")
                .withProperty("gemini.gateway.max-attempts", "1")
                .withProperty("gemini.gateway.purpose.translation.enabled", "true")
                .withProperty("gemini.gateway.purpose.understanding.enabled", "true")
                .withProperty("gemini.gateway.purpose.keyword-training.enabled", "true")
                .withProperty("gemini.gateway.models.translation", "gemini-2.5-flash-translation")
                .withProperty("gemini.gateway.models.understanding", "gemini-2.5-flash-understanding")
                .withProperty("gemini.gateway.models.keyword-training", "gemini-2.5-flash-keyword");
        AtomicInteger exchanges = new AtomicInteger();
        List<String> paths = new ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            paths.add(request.url().getPath());
            int attempt = exchanges.incrementAndGet();
            String text = switch (attempt) {
                case 1 -> "translated text";
                case 2 -> "understood text";
                default -> "keyword one\nkeyword two";
            };
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\""
                            + text.replace("\n", "\\n") + "\"}]}}]}")
                    .build());
        });
        GeminiGateway gateway = new GeminiGateway(
                builder,
                new ProviderCredentialResolver(environment),
                environment);
        GeminiClient client = new GeminiClient(gateway);

        String translated = client.translate("source", "en", "ko").block();
        String understood = client.generate("question").block();
        GeminiClient.KeywordVariantsResult keywords = client.keywordVariantsWithMeta(
                "base", "anchor", 2, Duration.ofSeconds(1));

        assertEquals("translated text", translated);
        assertTrue(understood.contains("\"data\" : \"understood text\""), understood);
        assertEquals(List.of("keyword one", "keyword two"), keywords.variants());
        assertEquals(3, exchanges.get());
        assertTrue(paths.get(0).contains("gemini-2.5-flash-translation"));
        assertTrue(paths.get(1).contains("gemini-2.5-flash-understanding"));
        assertTrue(paths.get(2).contains("gemini-2.5-flash-keyword"));
    }

    @Test
    void curationUsesGatewayPurposeModelAndParsesKnowledgeDelta() throws JsonProcessingException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-facade-curation-value")
                .withProperty("gemini.gateway.enabled", "true")
                .withProperty("gemini.gateway.preflight.enabled", "false")
                .withProperty("gemini.gateway.max-attempts", "1")
                .withProperty("gemini.gateway.purpose.curation.enabled", "true")
                .withProperty("gemini.gateway.models.curation", "gemini-2.5-flash-curation");
        AtomicInteger exchanges = new AtomicInteger();
        List<String> paths = new ArrayList<>();
        String deltaJson = """
                {"triples":[{"s":"subject","p":"predicate","o":"object","sourceUrl":"https://example.invalid/source"}],
                 "rules":[],"aliases":[],"memories":[],"protectedTerms":[]}
                """;
        String encodedDelta = new ObjectMapper().writeValueAsString(deltaJson);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            paths.add(request.url().getPath());
            exchanges.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                            + encodedDelta + "}]}}]}")
                    .build());
        });
        GeminiGateway gateway = new GeminiGateway(
                builder,
                new ProviderCredentialResolver(environment),
                environment);
        GeminiClient client = new GeminiClient(gateway);

        KnowledgeDelta delta = client.curate(
                new LearningEvent("session", "question", "answer", List.of(), List.of(), 1.0, 0.0),
                "legacy-caller-model-must-not-own-policy",
                Duration.ofSeconds(1));

        assertEquals(1, exchanges.get());
        assertTrue(paths.get(0).contains("gemini-2.5-flash-curation"));
        assertEquals(1, delta.triples().size());
        assertEquals("subject", delta.triples().get(0).s());
    }
}
