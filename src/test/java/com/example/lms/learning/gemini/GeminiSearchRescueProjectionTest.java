package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.dto.ChatRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GeminiSearchRescueProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @org.junit.jupiter.api.AfterEach void clearRequestContext() { com.example.lms.search.TraceStore.clear(); }
    private static final String VALID = """
        {"modelVersion":"gemini-response-fixture","candidates":[{"finishReason":"STOP",
          "content":{"parts":[{"thought":true,"text":"synthetic thought"},{"text":"서울🙂"}]},
          "groundingMetadata":{"webSearchQueries":["synthetic query"],
            "groundingChunks":[{"web":{"uri":"https://source.invalid/item","title":"Synthetic source"}}],
            "groundingSupports":[{"segment":{"partIndex":1,"startIndex":0,"endIndex":10,"text":"서울🙂"},"groundingChunkIndices":[0]}],
            "searchEntryPoint":{"renderedContent":"<div>Suggestions</div>"}}}]}
        """;
    @Test void exhaustedSharedHttpBudgetDispatchesNothing() throws Exception {
        com.example.lms.service.rag.SelfAskSearchBudget.beginRequest(com.example.lms.domain.enums.ExecutionMode.AUTO);
        for (int i=0;i<6;i++) assertTrue(com.example.lms.service.rag.SelfAskSearchBudget.tryReserveHttp(com.example.lms.search.TraceStore.context(),"synthetic final"));
        var calls=new AtomicInteger();var gateway=gateway(r->{calls.incrementAndGet();return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).header("Content-Type","application/json").body(VALID).build());});
        assertEquals("SEARCH_BUDGET",receipt(gateway,true,true,20000).path("reasonCode").asText());
        assertEquals(0,calls.get());
    }
    @Test void optionalAccountingFailureCannotDiscardValidGrounding() throws Exception {
        var env=environment().withProperty("gemini.gateway.grounding.usage-ledger","invalid"+(char)0);
        var calls=new AtomicInteger();var gateway=gateway(env,r->{calls.incrementAndGet();return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).header("Content-Type","application/json").body(VALID).build());});
        var result=receipt(gateway,true,true,20000);
        assertEquals("READY_FOR_DISPLAY",result.path("reasonCode").asText());
        assertEquals("서울🙂",result.path("answer").path("originalText").asText());assertEquals(1,calls.get());
    }
    @Test void nativeWireAndSyncStreamProjectionsKeepAuxiliaryOriginalAndMainModelSeparate() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();var calls=new AtomicInteger();
        server.createContext("/v1beta/models/gemini-3.8-flash:generateContent",exchange->{
            calls.incrementAndGet();body.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes=VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            var env=environment().withProperty("gemini.gateway.base-url","http://127.0.0.1:"+server.getAddress().getPort()).withProperty("gemini.gateway.search-rescue.timeout-ms","3000");
            var gateway=new GeminiGateway(WebClient.builder(),new ProviderCredentialResolver(env),env,(com.example.lms.agent.FreeTierApiThrottleService)null);
            var result=gateway.searchRescue("synthetic original","synthetic final",true,true,20000).block();
            assertEquals("READY_FOR_DISPLAY",result.reasonCode());assertEquals(1,calls.get());
            assertTrue(body.get().path("tools").get(0).has("google_search"));
            assertEquals("synthetic original",body.get().path("contents").get(0).path("parts").get(0).path("text").asText());
            assertEquals(1024,body.get().path("generationConfig").path("maxOutputTokens").asInt());
            assertEquals("low",body.get().path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());
            var sync=new com.example.lms.dto.ChatResponseDto("Main original",7L,"main-model-fixture",false).withGoogleSearchRescue(result);
            var stream=com.example.lms.dto.ChatStreamEvent.doneWithAnswer("Main original","main-model-fixture",false,7L,"FACT",8L,null,java.util.List.of(),null).withGoogleSearchRescue(result).withObservation(java.util.Map.of());
            for(var projected:java.util.List.<com.fasterxml.jackson.databind.JsonNode>of(mapper.valueToTree(sync),mapper.valueToTree(stream))){
                assertEquals("main-model-fixture",projected.path("modelUsed").asText());assertTrue(projected.path("evidence").isEmpty());
                assertEquals("서울🙂",projected.path("googleSearchRescue").path("answer").path("originalText").asText());
                assertFalse(projected.path("googleSearchRescue").path("bodyFetched").asBoolean());assertFalse(projected.path("googleSearchRescue").path("mainPromptPermission").asBoolean());
                assertFalse(projected.path("googleSearchRescue").path("memoryEligible").asBoolean());
            }
            var stored=mapper.valueToTree(new com.example.lms.service.ChatResult("Main original","main-model-fixture",false,java.util.Set.of(),java.util.List.of(),null,result));
            assertFalse(stored.has("googleSearchRescue"));
            var on=mapper.readValue("{\"googleSearchRescueEnabled\":true}",ChatRequestDto.class);
            assertTrue(on.toBuilder().build().isGoogleSearchRescueEnabled());
        } finally {server.stop(0);}
    }
    @Test void defaultOffAndSufficientEvidenceNeverDispatch() throws Exception {
        assertFalse(mapper.valueToTree(new ChatRequestDto()).path("googleSearchRescueEnabled").asBoolean());
        var calls = new AtomicInteger();
        var gateway = gateway(request -> { calls.incrementAndGet(); return Mono.error(new IllegalStateException()); });
        assertEquals("OPT_OUT", receipt(gateway, false, true, 20000).path("reasonCode").asText());
        assertEquals("EVIDENCE_SUFFICIENT", receipt(gateway, true, false, 20000).path("reasonCode").asText());
        assertEquals("REQUEST_BUDGET", receipt(gateway, true, true, 5000).path("reasonCode").asText());
        assertEquals(0, calls.get());
    }
    @Test void enabledRescueUsesOneNativeCallAndNeverRetriesOrReplays() throws Exception {
        var calls = new AtomicInteger(); var nativePath = new AtomicReference<String>();
        var gateway = gateway(request -> { calls.incrementAndGet();nativePath.set(request.url().getPath());
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).build()); });
        Mono<?> request = operation(gateway, true, true, 20000);
        assertEquals("HTTP_429", mapper.valueToTree(request.block()).path("reasonCode").asText());
        assertEquals("ALREADY_ATTEMPTED", mapper.valueToTree(request.block()).path("reasonCode").asText());
        assertTrue(nativePath.get().endsWith("/models/gemini-3.8-flash:generateContent"));
        assertEquals(1,calls.get());
    }
    @Test void timeoutCancelsNativeTransportAndKeepsSpecificReason() throws Exception {
        var cancelled = new AtomicBoolean();
        var gateway = gateway(request -> Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true)));
        assertEquals("TIMEOUT", receipt(gateway,true,true,20000).path("reasonCode").asText());
        assertTrue(cancelled.get());
    }
    private com.fasterxml.jackson.databind.JsonNode receipt(GeminiGateway g,boolean on,boolean needed,long remaining) throws Exception {
        return mapper.valueToTree(operation(g,on,needed,remaining).block(Duration.ofSeconds(5)));
    }
    private Mono<?> operation(GeminiGateway g,boolean on,boolean needed,long remaining) throws Exception {
        var method=GeminiGateway.class.getMethod("searchRescue",String.class,String.class,boolean.class,boolean.class,long.class);
        return (Mono<?>)method.invoke(g,"synthetic original","synthetic final",on,needed,remaining);
    }
    private GeminiGateway gateway(ExchangeFunction exchange) {
        return gateway(environment(),exchange);
    }
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("GEMINI_API_KEY","synthetic-rescue-fixture")
            .withProperty("gemini.gateway.grounding.usage-ledger","")
            .withProperty("gemini.gateway.max-attempts","3").withProperty("gemini.gateway.preflight.enabled","true")
            .withProperty("gemini.gateway.search-rescue.timeout-ms","80");
    }
    private GeminiGateway gateway(MockEnvironment env,ExchangeFunction exchange) {
        return new GeminiGateway(WebClient.builder().exchangeFunction(exchange),new ProviderCredentialResolver(env),env,(com.example.lms.agent.FreeTierApiThrottleService)null);
    }
}
