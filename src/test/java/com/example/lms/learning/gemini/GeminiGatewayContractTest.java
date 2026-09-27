package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.agent.FreeTierApiThrottleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiGatewayContractTest {

    private static final Set<String> STATUS_FIELDS = Set.of(
            "provider",
            "route",
            "model",
            "enabled",
            "credentialPresent",
            "attemptCount",
            "statusCode",
            "latencyMs",
            "cacheHit",
            "quotaDecision",
            "fallbackReason",
            "errorClass");

    @Test
    void missingCredentialProducesStatusWithoutAWireAttempt() {
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(new MockEnvironment(), request -> {
            exchanges.incrementAndGet();
            return Mono.error(new AssertionError("wire call must stay disabled"));
        });

        GeminiGateway.GenerationResult result = gateway
                .generate("private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertNotNull(result);
        assertEquals("", result.text());
        assertEquals(0, exchanges.get());
        assertFalse(result.status().enabled());
        assertFalse(result.status().credentialPresent());
        assertEquals("missing-credential", result.status().fallbackReason());
        assertEquals(STATUS_FIELDS, result.status().asMap().keySet());
    }

    @Test
    void conflictingCredentialAliasesProduceZeroWireAttempts() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("gemini.api-key", "gemini-first-test-value")
                .withProperty("GEMINI_API_KEY", "gemini-second-test-value");
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.error(new AssertionError("conflicting provider must remain disabled"));
        });

        GeminiGateway.GenerationResult result = gateway
                .generate("private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertNotNull(result);
        assertEquals(0, exchanges.get());
        assertFalse(result.status().enabled());
        assertTrue(result.status().credentialPresent());
        assertEquals("conflicting-credential-aliases", result.status().fallbackReason());
        assertFalse(result.status().asMap().toString().contains("gemini-first-test-value"));
        assertFalse(result.status().asMap().toString().contains("gemini-second-test-value"));
    }

    @Test
    void enabledPurposeUsesConfiguredModelOnceAndKeepsKeyOutOfTheUrl() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-wire-test-value")
                .withProperty("gemini.gateway.models.search-expansion", "gemini-2.5-flash-test");
        AtomicInteger exchanges = new AtomicInteger();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("""
                            {"candidates":[{"content":{"parts":[{"text":"expanded query"}]}}]}
                            """)
                    .build());
        });

        GeminiGateway.GenerationResult result = gateway
                .generate("private search prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertNotNull(result);
        assertEquals("expanded query", result.text());
        assertEquals(1, exchanges.get());
        assertTrue(captured.get().url().getPath()
                .endsWith("/v1beta/models/gemini-2.5-flash-test:generateContent"));
        assertFalse(captured.get().url().toString().contains("gemini-wire-test-value"));
        assertEquals("gemini-wire-test-value", captured.get().headers().getFirst("x-goog-api-key"));
        assertEquals("gemini-2.5-flash-test", result.status().model());
        assertEquals("search-expansion", result.status().route());
        assertEquals(1, result.status().attemptCount());
        assertEquals(200, result.status().statusCode());
        assertEquals(STATUS_FIELDS, result.status().asMap().keySet());
        assertFalse(result.status().asMap().toString().contains("private search prompt"));
        assertFalse(result.status().asMap().toString().contains("gemini-wire-test-value"));
    }

    @Test
    void disabledPurposeProducesZeroWireAttemptsEvenWithACredential() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-disabled-purpose-value")
                .withProperty("gemini.gateway.purpose.translation.enabled", "false");
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.error(new AssertionError("disabled purpose must not call provider"));
        });

        GeminiGateway.GenerationResult result = gateway
                .generate("private translation prompt", GeminiGateway.Purpose.TRANSLATION)
                .block();

        assertNotNull(result);
        assertEquals(0, exchanges.get());
        assertFalse(result.status().enabled());
        assertTrue(result.status().credentialPresent());
        assertEquals("purpose-disabled", result.status().fallbackReason());
    }

    @Test
    void nonSearchPurposeRetriesOnlyUpToTheConfiguredAttemptBound() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-retry-test-value")
                .withProperty("gemini.gateway.purpose.translation.enabled", "true")
                .withProperty("gemini.gateway.max-attempts", "2");
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(environment, request -> {
            if (exchanges.incrementAndGet() == 1) {
                return Mono.error(new java.io.IOException("transient-test-failure"));
            }
            return Mono.just(successResponse("retried expansion"));
        });

        GeminiGateway.GenerationResult result = gateway
                .generate("private retry prompt", GeminiGateway.Purpose.TRANSLATION)
                .block();

        assertNotNull(result);
        assertEquals("retried expansion", result.text());
        assertEquals(2, exchanges.get());
        assertEquals(2, result.status().attemptCount());
    }

    @Test
    void searchExpansionNeverRetriesItsSingleAllowedWireAttempt() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-search-no-retry-test-value")
                .withProperty("gemini.gateway.max-attempts", "3");
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.error(new java.io.IOException("transient-search-test-failure"));
        });

        GeminiGateway.GenerationResult result = gateway
                .generate("private bounded search prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertNotNull(result);
        assertEquals("", result.text());
        assertEquals(1, exchanges.get());
        assertEquals(1, result.status().attemptCount());
    }

    @Test
    void exhaustedQuotaPreventsAnAdditionalWireAttempt() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-quota-test-value");
        AtomicInteger exchanges = new AtomicInteger();
        FreeTierApiThrottleService throttle = new FreeTierApiThrottleService(1, 1);
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.just(successResponse("first expansion"));
        }, throttle);

        GeminiGateway.GenerationResult first = gateway
                .generate("first private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();
        GeminiGateway.GenerationResult second = gateway
                .generate("second private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("first expansion", first.text());
        assertEquals("", second.text());
        assertEquals(1, exchanges.get());
        assertEquals("denied", second.status().quotaDecision());
        assertEquals("quota-denied", second.status().fallbackReason());
    }

    @Test
    void successfulModelPreflightRunsOncePerModelBeforeGeneration() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-preflight-test-value")
                .withProperty("gemini.gateway.preflight.enabled", "true")
                .withProperty("gemini.gateway.models.search-expansion", "gemini-2.5-flash-preflight");
        AtomicInteger exchanges = new AtomicInteger();
        AtomicInteger preflights = new AtomicInteger();
        AtomicInteger generations = new AtomicInteger();
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            if (request.method() == org.springframework.http.HttpMethod.GET) {
                preflights.incrementAndGet();
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body("""
                                {"name":"models/gemini-2.5-flash-preflight",
                                 "supportedGenerationMethods":["generateContent"]}
                                """)
                        .build());
            }
            generations.incrementAndGet();
            return Mono.just(successResponse("preflight expansion"));
        });

        GeminiGateway.GenerationResult first = gateway
                .generate("first private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();
        GeminiGateway.GenerationResult second = gateway
                .generate("second private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertEquals("preflight expansion", first.text());
        assertEquals("preflight expansion", second.text());
        assertEquals(3, exchanges.get());
        assertEquals(1, preflights.get());
        assertEquals(2, generations.get());
    }

    @Test
    void openCircuitBreakerPreventsAnotherWireAttempt() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-breaker-test-value")
                .withProperty("gemini.gateway.circuit-breaker.minimum-calls", "2")
                .withProperty("gemini.gateway.circuit-breaker.sliding-window-size", "2")
                .withProperty("gemini.gateway.circuit-breaker.failure-rate-threshold", "50")
                .withProperty("gemini.gateway.circuit-breaker.wait-open-ms", "60000");
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.error(new java.io.IOException("breaker-test-failure"));
        });

        gateway.generate("first private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION).block();
        gateway.generate("second private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION).block();
        GeminiGateway.GenerationResult blocked = gateway
                .generate("third private prompt", GeminiGateway.Purpose.SEARCH_EXPANSION)
                .block();

        assertNotNull(blocked);
        assertEquals(2, exchanges.get());
        assertEquals(0, blocked.status().attemptCount());
        assertEquals("circuit-open", blocked.status().fallbackReason());
    }

    @Test
    void openAiCompatibleRouterModelIsBuiltAndObservedByTheGateway() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchanges.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            payload.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"gateway ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            MockEnvironment environment = baseEnvironment()
                    .withProperty("GEMINI_API_KEY", "gemini-router-test-value")
                    .withProperty("gemini.gateway.purpose.router.enabled", "true");
            GeminiGateway gateway = gateway(environment, request -> Mono.error(new AssertionError("native path unused")));
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai";

            ChatModel model = gateway.buildOpenAiCompatibleChatModel(new GeminiGateway.RouterSpec(
                    baseUrl,
                    "gemini-2.5-pro-router-test",
                    Duration.ofSeconds(2),
                    0,
                    0.2,
                    0.9,
                    null,
                    null,
                    128), true);
            String text = model.chat(List.of(UserMessage.from("bounded router probe"))).aiMessage().text();

            assertEquals("gateway ok", text);
            assertEquals("json_object",payload.get().path("response_format").path("type").asText());
            assertEquals("low",payload.get().path("reasoning_effort").asText());
            assertFalse(payload.get().has("service_tier"));
            assertEquals(1, exchanges.get());
            assertEquals("/v1beta/openai/chat/completions", requestPath.get());
            assertEquals("router", gateway.latestStatus().route());
            assertEquals("gemini-2.5-pro-router-test", gateway.latestStatus().model());
            assertEquals(1, gateway.latestStatus().attemptCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gemini38RouterModelOmitsUnsupportedSamplingFields() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchanges.incrementAndGet();
            payload.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            MockEnvironment environment = baseEnvironment()
                    .withProperty("GEMINI_API_KEY", "gemini-router-38-test-value")
                    .withProperty("gemini.gateway.purpose.router.enabled", "true");
            GeminiGateway gateway = gateway(environment, request -> Mono.error(new AssertionError("native path unused")));
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai";

            ChatModel model = gateway.buildOpenAiCompatibleChatModel(new GeminiGateway.RouterSpec(
                    baseUrl,
                    "gemini-3.8-flash",
                    Duration.ofSeconds(2),
                    0,
                    0.2,
                    0.9,
                    0.1,
                    0.1,
                    128), true);
            model.chat(List.of(UserMessage.from("bounded router probe"))).aiMessage().text();

            assertEquals(1, exchanges.get());
            assertFalse(payload.get().has("temperature"));
            assertFalse(payload.get().has("top_p"));
            assertFalse(payload.get().has("frequency_penalty"));
            assertFalse(payload.get().has("presence_penalty"));
            assertEquals("low", payload.get().path("reasoning_effort").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonGemini38RouterModelRetainsSamplingFields() throws Exception {
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            payload.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            MockEnvironment environment = baseEnvironment()
                    .withProperty("GEMINI_API_KEY", "gemini-router-35-test-value")
                    .withProperty("gemini.gateway.purpose.router.enabled", "true");
            GeminiGateway gateway = gateway(environment, request -> Mono.error(new AssertionError("native path unused")));
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai";

            ChatModel model = gateway.buildOpenAiCompatibleChatModel(new GeminiGateway.RouterSpec(
                    baseUrl,
                    "gemini-3.5-flash-lite",
                    Duration.ofSeconds(2),
                    0,
                    0.2,
                    0.9,
                    null,
                    null,
                    128), false);
            model.chat(List.of(UserMessage.from("bounded router probe"))).aiMessage().text();

            assertEquals(0.2, payload.get().path("temperature").asDouble());
            assertEquals(0.9, payload.get().path("top_p").asDouble());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void activeSourceAndResourcesContainNoRetiredGeminiModelReference() throws Exception {
        try (Stream<Path> paths = Stream.concat(Files.walk(Path.of("main/java")),
                Files.walk(Path.of("main/resources")))) {
            List<String> hits = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".yml")
                                || name.endsWith(".yaml") || name.endsWith(".properties");
                    })
                    .filter(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8)
                                    .contains("gemini-1.5-flash");
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertEquals(List.of(), hits);
        }
    }

    @Test
    void routerContainsNoIndependentGeminiCredentialResolutionPath() throws Exception {
        String routerSource = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"));

        assertFalse(routerSource.contains("resolveGeminiApiKey("),
                "Gemini router credentials must be owned only by GeminiGateway");
    }

    @Test
    void searchExpansionProducesOneBoundedDistinctQueryWithOneGenerationAttempt() {
        AtomicInteger exchanges = new AtomicInteger();
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-search-expansion-test-value");
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.just(successResponse("- expanded evidence query\nignored second candidate"));
        });

        GeminiGateway.SearchExpansion expansion = gateway
                .expandSearchQueryOnce("locally rewritten query")
                .block();

        assertNotNull(expansion);
        assertEquals("expanded evidence query", expansion.query());
        assertEquals(1, exchanges.get());
        assertEquals(1, expansion.status().attemptCount());
        assertEquals("search-expansion", expansion.status().route());
    }

    @Test
    void duplicateSearchExpansionFailsSoftWithoutAnotherGenerationAttempt() {
        AtomicInteger exchanges = new AtomicInteger();
        MockEnvironment environment = baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-search-duplicate-test-value");
        GeminiGateway gateway = gateway(environment, request -> {
            exchanges.incrementAndGet();
            return Mono.just(successResponse("locally rewritten query"));
        });

        GeminiGateway.SearchExpansion expansion = gateway
                .expandSearchQueryOnce("locally rewritten query")
                .block();

        assertNotNull(expansion);
        assertEquals("", expansion.query());
        assertEquals(1, exchanges.get());
        assertEquals("duplicate-expansion", expansion.status().fallbackReason());
    }

    private static MockEnvironment baseEnvironment() {
        return new MockEnvironment()
                .withProperty("gemini.gateway.enabled", "true")
                .withProperty("gemini.gateway.purpose.search-expansion.enabled", "true")
                .withProperty("gemini.gateway.preflight.enabled", "false")
                .withProperty("gemini.gateway.max-attempts", "1")
                .withProperty("gemini.gateway.timeout-ms", "2000");
    }

    private static GeminiGateway gateway(MockEnvironment environment, ExchangeFunction exchangeFunction) {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(environment);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new GeminiGateway(builder, resolver, environment);
    }

    private static GeminiGateway gateway(
            MockEnvironment environment,
            ExchangeFunction exchangeFunction,
            FreeTierApiThrottleService throttle) {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(environment);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new GeminiGateway(builder, resolver, environment, throttle);
    }

    private static ClientResponse successResponse(String text) {
        String escaped = text == null ? "" : text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escaped + "\"}]}}]}")
                .build();
    }
}
