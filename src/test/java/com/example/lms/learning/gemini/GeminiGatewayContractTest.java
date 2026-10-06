package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.agent.FreeTierApiThrottleService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiGatewayContractTest {
    @Test void nativeStrictTimeoutKeepsItsReasonInsteadOfBecomingBackendUnavailable() {
        for(boolean timeout:List.of(true,false)){
            var env=baseEnvironment().withProperty("GEMINI_API_KEY","synthetic-focus-key")
                    .withProperty("gemini.gateway.purpose.router.enabled","true");
            var exchanges=new AtomicInteger();
            var gateway=gateway(env,request->{
                exchanges.incrementAndGet();
                return Mono.error(timeout?new java.util.concurrent.TimeoutException("synthetic"):
                        new IllegalStateException("synthetic"));
            });
            var spec=new GeminiGateway.RouterSpec("https://generativelanguage.googleapis.com/v1beta/openai",
                    "gemini-3.8-flash",Duration.ofSeconds(2),0,null,null,null,null,500);
            var model=gateway.buildOpenAiCompatibleChatModel(spec,false,null,true,true);
            var error=org.junit.jupiter.api.Assertions.assertThrows(com.example.lms.llm.ModelSelectionException.class,
                    ()->model.chat("synthetic"));
            assertEquals(timeout?"backend_timeout":"backend_unavailable",error.code());
            assertEquals(1,exchanges.get());
        }
    }
    @Test void requiredSearchCannotDispatchWhenGroundingTurnsOffAfterReadiness() {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var reads = new AtomicInteger();
        var env = new MockEnvironment() {
            @Override public <T> T getProperty(String key, Class<T> type, T defaultValue) {
                if (armed.get() && key.equals("gemini.gateway.grounding.enabled"))
                    return type.cast(reads.incrementAndGet() == 1);
                return super.getProperty(key, type, defaultValue);
            }
        };
        env.withProperty("GEMINI_API_KEY", "synthetic-focus-key")
            .withProperty("gemini.gateway.enabled", "true")
            .withProperty("gemini.gateway.purpose.router.enabled", "true");
        var exchanges = new AtomicInteger();
        var gateway = gateway(env, request -> {
            exchanges.incrementAndGet();
            return Mono.just(successResponse("synthetic"));
        });
        var spec = new GeminiGateway.RouterSpec("https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-3.8-flash", Duration.ofSeconds(2), 0, null, null, null, null, 500);
        var model = gateway.buildOpenAiCompatibleChatModel(spec, false, null, true, true);
        armed.set(true);
        org.junit.jupiter.api.Assertions.assertThrows(com.example.lms.llm.ModelSelectionException.class,
            () -> model.chat("synthetic"));
        assertEquals(0, exchanges.get());
    }
    @Test void exclusivePublicationRequiresLinkedSourcesAndSafeSuggestions() {
        var source=new GeminiGateway.GroundingChunk(new GeminiGateway.WebSource("https://example.com/source","Source"));
        var support=new GeminiGateway.GroundingSupport(new GeminiGateway.Segment(0,0,6,"원문"),List.of(0),List.of());
        for(String html:List.of("<div>Suggestions</div>","<script>alert(1)</script>","<div onclick='run()'>Suggestions</div>","<style>@import 'https://example.com';</style>")){
            var metadata=new GeminiGateway.GroundingMetadata(List.of("query"),List.of(source),List.of(support),new GeminiGateway.SearchEntryPoint(html));
            assertEquals(html.equals("<div>Suggestions</div>"),new GeminiGateway.GroundedAnswer("원문","gemini-3.8-flash",metadata,List.of("원문"),true).exclusivePublicationReady());
        }
        var empty=new GeminiGateway.GroundingMetadata(List.of("query"),List.of(source),List.of(),new GeminiGateway.SearchEntryPoint("<div>Suggestions</div>"));
        var answer=new GeminiGateway.GroundedAnswer("원문","gemini-3.8-flash",empty,List.of("원문"),true);
        assertTrue(answer.searchObserved());assertTrue(answer.publicationReady());assertFalse(answer.exclusivePublicationReady());
        var unlinked=new GeminiGateway.GroundingMetadata(List.of("query"),List.of(source),List.of(new GeminiGateway.GroundingSupport(support.segment(),List.of(),List.of())),empty.searchEntryPoint());
        assertFalse(new GeminiGateway.GroundedAnswer("원문","gemini-3.8-flash",unlinked,List.of("원문"),true).exclusivePublicationReady());
    }
    @Test void requiredNativeSearchKeepsBothSelectedWireModelsAndNoCompatibleRequest() throws Exception {
        var paths=new java.util.ArrayList<String>();var bodies=new java.util.ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();paths.add(path);bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String model=path.substring(path.indexOf("/models/")+8,path.indexOf(":generateContent"));
            byte[] response=("{\"modelVersion\":\""+model+"\",\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"synthetic\"}]}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var env=baseEnvironment().withProperty("GEMINI_API_KEY","synthetic-focus-key").withProperty("gemini.gateway.purpose.router.enabled","true").withProperty("gemini.gateway.base-url",base);
            var gateway=new GeminiGateway(WebClient.builder(),new ProviderCredentialResolver(env),env);
            for(String id:List.of("gemini-3.8-flash","gemini-3.5-flash-lite")){
                var spec=new GeminiGateway.RouterSpec(base+"/v1beta/openai",id,Duration.ofSeconds(2),0,null,null,null,null,500);
                var model=gateway.buildOpenAiCompatibleChatModel(spec,false,null,true,true);
                var result=model.chat(List.of(dev.langchain4j.data.message.SystemMessage.from("system fixture"),UserMessage.from("prior question"),dev.langchain4j.data.message.AiMessage.from("prior answer"),UserMessage.from("current question")));
                assertEquals(id,result.modelName());assertEquals(id,((GeminiGateway.GroundedChatMetadata)result.metadata()).grounding().selectedModel());
                env.withProperty("gemini.gateway.grounding.enabled","false");
                org.junit.jupiter.api.Assertions.assertThrows(com.example.lms.llm.ModelSelectionException.class,()->model.chat("synthetic"));
                env.withProperty("gemini.gateway.grounding.enabled","true");
            }
            assertEquals(List.of("/v1beta/models/gemini-3.8-flash:generateContent","/v1beta/models/gemini-3.5-flash-lite:generateContent"),paths);
            assertEquals(2,bodies.size());for(String body:bodies){assertTrue(body.contains("google_search"));assertTrue(body.contains("systemInstruction"));assertTrue(body.contains("prior answer"));assertTrue(body.contains("current question"));}
        }finally{server.stop(0);}
    }
    @Test void requiredNativeSearchRejectsUnsupportedAuthorityAndKillSwitchBeforeTransport() {
        var env=baseEnvironment().withProperty("GEMINI_API_KEY","synthetic-focus-key")
            .withProperty("gemini.gateway.purpose.router.enabled","true");
        var gateway=new GeminiGateway(WebClient.builder(),new ProviderCredentialResolver(env),env);
        var required=org.junit.jupiter.api.Assertions.assertDoesNotThrow(()->GeminiGateway.class.getMethod(
            "buildOpenAiCompatibleChatModel",GeminiGateway.RouterSpec.class,boolean.class,
            dev.langchain4j.model.chat.request.json.JsonSchema.class,boolean.class,boolean.class));
        for(String endpoint:List.of("https://example.com/v1beta/openai","https://generativelanguage.googleapis.com/v1beta/openai")){
            env.withProperty("gemini.gateway.grounding.enabled",String.valueOf(endpoint.contains("example.com")));
            var spec=new GeminiGateway.RouterSpec(endpoint,"gemini-3.8-flash",Duration.ofSeconds(2),0,null,null,null,null,500);
            var error=org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                ()->required.invoke(gateway,spec,false,null,true,true));
            assertTrue(error.getCause() instanceof com.example.lms.llm.ModelSelectionException);
        }
        env.withProperty("gemini.gateway.grounding.enabled","true");
        for(String id:List.of("","gemini-unverified","llmrouter.gemini-pro")){
            var spec=new GeminiGateway.RouterSpec("https://generativelanguage.googleapis.com/v1beta/openai",id,Duration.ofSeconds(2),0,null,null,null,null,500);
            assertFalse(gateway.focusSearchReadiness(spec).ready());
        }
    }
    @Test void nativeFocusBodyOmitsToolWhenOffAndNeverDropsUnsupportedMessages(){
        var spec=new GeminiGateway.RouterSpec("https://generativelanguage.googleapis.com/v1beta/openai","gemini-fixture",Duration.ofSeconds(2),0,null,null,null,null,500);
        var messages=List.<dev.langchain4j.data.message.ChatMessage>of(dev.langchain4j.data.message.UserMessage.from("synthetic"));
        assertFalse(GeminiGateway.nativeChatBody(messages,spec,false).containsKey("tools"));
        assertTrue(GeminiGateway.nativeChatBody(messages,spec,true).containsKey("tools"));
        assertEquals(500,((Map<?,?>)GeminiGateway.nativeChatBody(messages,spec,true).get("generationConfig")).get("maxOutputTokens"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->GeminiGateway.nativeChatBody(List.of(dev.langchain4j.data.message.ToolExecutionResultMessage.from("id","tool","fixture")),spec,true));
    }
    @Test void byteAttributionIsValidatedAgainstOriginalPartsAndSearchObservationIsSeparate(){
        var source=new GeminiGateway.GroundingChunk(new GeminiGateway.WebSource("https://example.com/source","Source"));
        var suggestions=new GeminiGateway.SearchEntryPoint("<div>Suggestions</div>");
        var valid=new GeminiGateway.GroundingSupport(new GeminiGateway.Segment(1,0,6,"원문"),List.of(0),List.of());
        var metadata=new GeminiGateway.GroundingMetadata(List.of("query"),List.of(source),List.of(valid),suggestions);
        assertTrue(new GeminiGateway.GroundedAnswer("원문","gemini-fixture",metadata,List.of("","원문"),true).publicationReady());
        var split=new GeminiGateway.GroundingSupport(new GeminiGateway.Segment(1,0,5,null),List.of(0),List.of());
        assertFalse(new GeminiGateway.GroundedAnswer("원문","gemini-fixture",new GeminiGateway.GroundingMetadata(List.of("query"),List.of(source),List.of(split),suggestions),List.of("","원문"),true).publicationReady());
        assertFalse(new GeminiGateway.GroundedAnswer("원문","gemini-fixture",new GeminiGateway.GroundingMetadata(List.of("query"),List.of(source),List.of(valid),null),List.of("","원문"),true).publicationReady());
        var blank=new GeminiGateway.GroundedAnswer("plain","gemini-fixture",new GeminiGateway.GroundingMetadata(List.of(" "),List.of(),List.of(),null),List.of("plain"),true);
        assertFalse(blank.searchObserved());assertTrue(blank.publicationReady());
    }
    @Test void focusNativeChatUsesSelectedModelAndKeepsSystemHistoryAndGrounding() throws Exception {
        var path = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = """
                {"modelVersion":"gemini-selected-fixture","candidates":[{"content":{"parts":[{"text":"원문 그대로"}]},"groundingMetadata":{
                  "webSearchQueries":["synthetic query"],"groundingChunks":[{"web":{"uri":"https://example.com/source","title":"Source"}}],
                  "groundingSupports":[{"segment":{"partIndex":0,"startIndex":0,"endIndex":16,"text":"원문 그대로"},"groundingChunkIndices":[0]}],
                  "searchEntryPoint":{"renderedContent":"<div>Google Search Suggestions</div>"}}}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        server.start();
        try {
            var env=baseEnvironment().withProperty("GEMINI_API_KEY","synthetic-focus-key")
                .withProperty("gemini.gateway.purpose.router.enabled","true")
                .withProperty("gemini.gateway.base-url","http://127.0.0.1:"+server.getAddress().getPort());
            var gateway=new GeminiGateway(WebClient.builder(),new ProviderCredentialResolver(env),env);
            // Structural RED: the current router discards the native grounding boundary.
            var build=GeminiGateway.class.getMethod("buildOpenAiCompatibleChatModel",GeminiGateway.RouterSpec.class,
                boolean.class,dev.langchain4j.model.chat.request.json.JsonSchema.class,boolean.class);
            var spec=new GeminiGateway.RouterSpec("http://127.0.0.1:"+server.getAddress().getPort()+"/v1beta/openai",
                "gemini-selected-fixture",Duration.ofSeconds(2),0,0.2,0.9,null,null,500);
            var model=(ChatModel)build.invoke(gateway,spec,false,null,true);
            var response=model.chat(List.of(dev.langchain4j.data.message.SystemMessage.from("system fixture"),
                dev.langchain4j.data.message.UserMessage.from("prior question"),
                dev.langchain4j.data.message.AiMessage.from("prior answer"),
                dev.langchain4j.data.message.UserMessage.from("current question")));
            assertEquals("/v1beta/models/gemini-selected-fixture:generateContent",path.get());
            assertTrue(body.get().contains("google_search"));assertTrue(body.get().contains("systemInstruction"));
            assertTrue(body.get().contains("prior answer"));assertTrue(body.get().contains("current question"));
            assertEquals("원문 그대로",response.aiMessage().text());
            assertEquals("gemini-selected-fixture",response.modelName());
            assertEquals("GroundedChatMetadata",response.metadata().getClass().getSimpleName());
            assertFalse(gateway.latestStatus().asMap().toString().contains("synthetic query"));
        } finally {server.stop(0);}
    }

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

    @Test
    void groundingOptInAddsGoogleSearchToolToTheNativeBody() {
        Map<String, Object> grounded = GeminiGateway.generationBody("private prompt", true);
        assertEquals(List.of(Map.of("google_search", Map.of())), grounded.get("tools"));

        Map<String, Object> plain = GeminiGateway.generationBody("private prompt", false);
        assertFalse(plain.containsKey("tools"));
    }
    @Test void nativeGroundingMetadataStaysWithItsCandidateAndOutOfStatus() throws Exception {
        var env=baseEnvironment().withProperty("GEMINI_API_KEY","gemini-grounding-fixture")
                .withProperty("gemini.gateway.purpose.understanding.enabled","true");
        var gateway=gateway(env,request->Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type","application/json").body("""
                {"candidates":[{"content":{"parts":[{"text":"fixture answer"}]},"groundingMetadata":{
                  "webSearchQueries":["fixture query"],"groundingChunks":[{"web":{"uri":"https://example.org/source","title":"Fixture"}}],
                  "groundingSupports":[{"segment":{"startIndex":0,"endIndex":14,"text":"fixture answer"},"groundingChunkIndices":[0]}],
                  "searchEntryPoint":{"renderedContent":"<div>Suggestions</div>"}}}]}
                """).build()));
        var result=gateway.generate("synthetic",GeminiGateway.Purpose.UNDERSTANDING,true).block();
        var metadata=new ObjectMapper().valueToTree(result).path("groundingMetadata");
        assertEquals("fixture query",metadata.path("webSearchQueries").get(0).asText());
        assertEquals("https://example.org/source",metadata.path("groundingChunks").get(0).path("web").path("uri").asText());
        assertEquals(0,metadata.path("groundingSupports").get(0).path("groundingChunkIndices").get(0).asInt());
        assertEquals("<div>Suggestions</div>",metadata.path("searchEntryPoint").path("renderedContent").asText());
        assertFalse(result.status().asMap().toString().contains("fixture query"));
        assertTrue(result.searchToolAllowed());assertTrue(result.searchObserved());
    }
    @Test void allowedToolWithoutMetadataKeepsNormalTextAndDoesNotProveSearch() {
        var env=baseEnvironment().withProperty("GEMINI_API_KEY","gemini-grounding-fixture")
                .withProperty("gemini.gateway.purpose.understanding.enabled","true");
        var gateway=gateway(env,request->Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
                .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"normal answer\"}]},\"citationMetadata\":{}}]}").build()));
        var result=gateway.generate("synthetic",GeminiGateway.Purpose.UNDERSTANDING,true).block();
        assertEquals("normal answer",result.text());assertTrue(result.searchToolAllowed());
        assertNull(result.groundingMetadata());assertFalse(result.searchObserved());
    }
    @Test void queriesWithoutSupportsAndOtherCandidatesCannotProveAnswerAttribution() {
        var env=baseEnvironment().withProperty("GEMINI_API_KEY","gemini-grounding-fixture")
                .withProperty("gemini.gateway.purpose.understanding.enabled","true");
        var gateway=gateway(env,request->Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("""
                {"candidates":[{"content":{"parts":[{"text":"normal answer"}]},"groundingMetadata":{"webSearchQueries":["fixture"]}},
                {"content":{"parts":[{"text":"other answer"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.org/other"}}]}}]}
                """).build()));
        var result=gateway.generate("synthetic",GeminiGateway.Purpose.UNDERSTANDING,true).block();
        assertEquals("normal answer",result.text());assertTrue(result.searchObserved());assertTrue(result.groundingMetadata().groundingSupports().isEmpty());
        assertTrue(result.groundingMetadata().groundingChunks().isEmpty());
    }
    @Test void errorAndSearchOffDiscardUnexpectedGroundingData() {
        for(boolean allowed:List.of(false,true)){
            var env=baseEnvironment().withProperty("GEMINI_API_KEY","gemini-grounding-fixture")
                    .withProperty("gemini.gateway.purpose.understanding.enabled","true");
            var gateway=gateway(env,request->Mono.just(ClientResponse.create(allowed?HttpStatus.UNAUTHORIZED:HttpStatus.OK)
                .header("Content-Type","application/json").body("""
                {"candidates":[{"content":{"parts":[{"text":"unexpected answer"}]},"groundingMetadata":{"webSearchQueries":["fixture"]}}]}
                """).build()));
            var result=gateway.generate("synthetic",GeminiGateway.Purpose.UNDERSTANDING,allowed).block();
            assertNull(result.groundingMetadata());assertFalse(result.searchObserved());
            assertEquals(allowed?"":"unexpected answer",result.text());
            assertEquals(allowed?401:200,result.status().statusCode());
        }
    }

    @Test
    void groundingOptInCarriesTheToolBlockToTheWireAndHonorsTheKillSwitch() throws Exception {
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            payload.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"grounded\"}]}}]}"
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
                    .withProperty("GEMINI_API_KEY", "gemini-grounding-test-value")
                    .withProperty("gemini.gateway.purpose.understanding.enabled", "true")
                    .withProperty("gemini.gateway.base-url",
                            "http://127.0.0.1:" + server.getAddress().getPort());
            GeminiGateway gateway = new GeminiGateway(WebClient.builder(),
                    new ProviderCredentialResolver(environment), environment);

            GeminiGateway.GenerationResult result = gateway
                    .generate("private prompt", GeminiGateway.Purpose.UNDERSTANDING, true).block();

            assertNotNull(result);
            assertEquals("grounded", result.text());
            assertTrue(payload.get().path("tools").get(0).has("google_search"));

            payload.set(null);
            MockEnvironment disabled = baseEnvironment()
                    .withProperty("GEMINI_API_KEY", "gemini-grounding-test-value")
                    .withProperty("gemini.gateway.purpose.understanding.enabled", "true")
                    .withProperty("gemini.gateway.grounding.enabled", "false")
                    .withProperty("gemini.gateway.base-url",
                            "http://127.0.0.1:" + server.getAddress().getPort());
            GeminiGateway offGateway = new GeminiGateway(WebClient.builder(),
                    new ProviderCredentialResolver(disabled), disabled);

            assertEquals("grounded", offGateway
                    .generate("private prompt", GeminiGateway.Purpose.UNDERSTANDING, true).block().text());
            assertFalse(payload.get().has("tools"), "kill switch strips the tool block");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void t1NativeMultipartConcatenatesOrdinaryTextInOrder() {
        assertNativeResponse("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"앞"},{"text":"뒤"}]}}]}
                """, "앞뒤");
    }

    @Test
    void t2NativeMultipartSkipsNullAndNonTextLeadingParts() {
        for (String leadingPart : List.of("null", "{}", "{\"text\":null}",
                "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AA==\"}}",
                "{\"functionCall\":{\"name\":\"synthetic\",\"args\":{}}}",
                "{\"executableCode\":{\"language\":\"PYTHON\",\"code\":\"pass\"}}")) {
            assertNativeResponse("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
                    + leadingPart + ",{\"text\":\"뒤쪽 본문\"}]}}]}", "뒤쪽 본문");
        }
    }

    @Test
    void t3NativeMultipartExcludesThoughtAndPreservesAbsentFalseOrNullThought() {
        assertNativeResponse("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"thought":true,"text":"SYNTHETIC_THOUGHT_MARKER"},
                  {"text":"앞"},null,{"thought":false,"text":"뒤"},{"thought":null,"text":"끝"}]}}]}
                """, "앞뒤끝");
    }

    @Test
    void t4NativeMultipartReadsOnlyTheFirstCandidate() {
        assertNativeResponse("""
                {"candidates":[
                  {"finishReason":"STOP","content":{"parts":[{"text":"앞"},{"text":"뒤"}]}},
                  {"finishReason":"STOP","content":{"parts":[{"text":"SECOND_CANDIDATE_MARKER"}]}}]}
                """, "앞뒤");
    }

    @Test
    void t5NativeMultipartEmptyShapesRemainFailSoftWithoutAdditionalCalls() {
        for (String payload : List.of("{}", "{\"candidates\":null}", "{\"candidates\":[]}",
                "{\"candidates\":[null]}", "{\"candidates\":[{}]}",
                "{\"candidates\":[{\"content\":null}]}", "{\"candidates\":[{\"content\":{}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":null}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[null,{}, {\"text\":null}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AA==\"}}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"SYNTHETIC_THOUGHT_MARKER\"}]}}]}",
                "{\"candidates\":[null,{\"content\":{\"parts\":[{\"text\":\"SECOND_CANDIDATE_MARKER\"}]}}]}")) {
            assertNativeResponse(payload, "");
        }
    }

    @Test
    void t6NativeMultipartPreservesSinglePartUnicodeCrLfWhitespaceAndJsonTokens() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String first = "  한국어 😀\r";
        String second = "\n{\"to";
        String third = "ken\":\"그대로\"}\r\n  ";
        String expected = first + second + third;
        assertNativeResponse("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":"
                + mapper.writeValueAsString(expected) + "}]}}]}", expected);
        assertNativeResponse("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":"
                + mapper.writeValueAsString(first) + "},{\"text\":" + mapper.writeValueAsString(second)
                + "},{\"text\":" + mapper.writeValueAsString(third) + "}]}}]}", expected);
    }

    private static void assertNativeResponse(String payload, String expected) {
        AtomicInteger exchanges = new AtomicInteger();
        GeminiGateway gateway = gateway(baseEnvironment()
                .withProperty("GEMINI_API_KEY", "gemini-multipart-test-value"), request -> {
            exchanges.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json").body(payload).build());
        });
        GeminiGateway.GenerationResult result = gateway
                .generate("synthetic multipart prompt", GeminiGateway.Purpose.SEARCH_EXPANSION).block();

        assertNotNull(result);
        assertEquals(expected, result.text());
        assertEquals(1, exchanges.get());
        assertEquals(1, result.status().attemptCount());
        assertEquals(200, result.status().statusCode());
        assertEquals("", result.status().fallbackReason());
        assertEquals("", result.status().errorClass());
    }

    private static MockEnvironment baseEnvironment() {
        return new MockEnvironment()
                .withProperty("gemini.gateway.grounding.usage-ledger", "")
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

    @Test
    void nativeCompletionMetadataRetainsFinishSafetyAndUnknownUsage() throws Exception {
        var result = rescueFixture("MAX_TOKENS", false, "", true, true);
        var completion = new ObjectMapper().valueToTree(result).path("completion");
        assertEquals("MAX_TOKENS", completion.path("finishReason").asText());
        assertEquals(31, completion.path("usage").path("totalTokenCount").asInt());
        assertEquals(7, completion.path("usage").path("thoughtsTokenCount").asInt());
        assertFalse(completion.path("safetyBlocked").asBoolean());
        var missing = new ObjectMapper().valueToTree(rescueFixture(null, true, "SAFETY", false, true)).path("completion");
        assertTrue(missing.path("finishReason").isNull());
        assertTrue(missing.path("usage").isNull());
        assertTrue(missing.path("safetyBlocked").asBoolean());
        assertEquals("SAFETY", missing.path("promptBlockReason").asText());
        assertEquals("서울🙂", result.text());
        assertEquals(List.of("", "서울🙂"), result.publicParts());
    }

    @Test
    void nativeRescueEligibilityRejectsIncompleteSafetyOrUnattributedResults() throws Exception {
        var method = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> GeminiGateway.GenerationResult.class.getMethod("rescueEligibilityReason"));
        assertEquals("READY_FOR_DISPLAY", method.invoke(rescueFixture("STOP", false, "", true, true)));
        for (String finish : List.of("MAX_TOKENS", "SAFETY", "RECITATION", "MALFORMED_FUNCTION_CALL")) {
            assertEquals("INCOMPLETE_RESPONSE", method.invoke(rescueFixture(finish, false, "", true, true)));
        }
        assertEquals("FINISH_UNKNOWN", method.invoke(rescueFixture(null, false, "", false, true)));
        assertEquals("SAFETY_BLOCKED", method.invoke(rescueFixture("STOP", true, "", true, true)));
        assertEquals("PROMPT_BLOCKED", method.invoke(rescueFixture("STOP", false, "SAFETY", true, true)));
        assertEquals("ATTRIBUTION_INVALID", method.invoke(rescueFixture("STOP", false, "", true, false)));
        // Token accounting is unknown, not zero; it is separate from display attribution.
        assertEquals("READY_FOR_DISPLAY", method.invoke(rescueFixture("STOP", false, "", false, true)));
    }

    private static GeminiGateway.GenerationResult rescueFixture(String finish, boolean blocked,
            String promptBlock, boolean withUsage, boolean withSupports) throws Exception {
        var payload = new ObjectMapper().createObjectNode();
        payload.put("modelVersion", "gemini-response-fixture");
        var candidate = payload.putArray("candidates").addObject();
        if (finish != null) candidate.put("finishReason", finish);
        candidate.putArray("safetyRatings").addObject().put("blocked", blocked);
        var parts = candidate.putObject("content").putArray("parts");
        parts.addObject().put("text", "hidden thought").put("thought", true);
        parts.addObject().put("text", "서울🙂");
        var metadata = candidate.putObject("groundingMetadata");
        metadata.putArray("webSearchQueries").add("synthetic query");
        metadata.putArray("groundingChunks").addObject().putObject("web")
                .put("uri", "https://source.invalid/item").put("title", "Synthetic source");
        if (withSupports) {
            var support = metadata.putArray("groundingSupports").addObject();
            support.putObject("segment").put("partIndex", 1).put("startIndex", 0).put("endIndex", 10).put("text", "서울🙂");
            support.putArray("groundingChunkIndices").add(0);
        }
        metadata.putObject("searchEntryPoint").put("renderedContent", "<div>Suggestions</div>");
        if (!promptBlock.isBlank()) payload.putObject("promptFeedback").put("blockReason", promptBlock);
        if (withUsage) payload.putObject("usageMetadata").put("promptTokenCount", 12)
                .put("candidatesTokenCount", 12).put("thoughtsTokenCount", 7).put("totalTokenCount", 31);
        String json = payload.toString();
        var environment = baseEnvironment().withProperty("GEMINI_API_KEY", "synthetic-rescue-fixture")
                .withProperty("gemini.gateway.purpose.understanding.enabled", "true");
        return gateway(environment, request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body(json).build()))
                .generate("synthetic", GeminiGateway.Purpose.UNDERSTANDING, true).block();
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
