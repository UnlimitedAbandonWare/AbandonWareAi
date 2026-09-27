package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateApiRouteTest {
    @Test void failedApiMakesOneWireAttemptAndNeverFollowsConfiguredLocalFallback()throws Exception{
        var calls=new AtomicInteger();var delay=new AtomicInteger();var payload=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();payload.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));try{Thread.sleep(delay.get());}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}byte[] body="{\"error\":\"fixture_unavailable\"}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();});http.start();
        try{
            var props=new LlmRouterProperties();var api=new LlmRouterProperties.ModelConfig();api.setProvider("openai");api.setName("openai/gpt-oss-120b");api.setCredentialEnv("FIXTURE_API_KEY");api.setBaseUrl("http://127.0.0.1:"+http.getAddress().getPort()+"/v1");api.setStage("chat");api.setFallbackKey("local");props.getModels().put("api",api);
            var local=new LlmRouterProperties.ModelConfig();local.setProvider("local");local.setName("fixture-local");local.setBaseUrl(api.getBaseUrl());props.getModels().put("local",local);
            var env=new MockEnvironment().withProperty("FIXTURE_API_KEY","synthetic-key");var policy=new LlmGatewayProperties();policy.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);policy.getCloud().setEnabled(true);policy.getCloud().setRouteKey("local");
            var tracker=new ModelRuntimeHealthTracker();var probe=new HybridLlmGatewayProbeService(policy,tracker,null,new LlmRouteScorer(),env);
            @SuppressWarnings("unchecked") ObjectProvider<KeyResolver> keys=mock(ObjectProvider.class);var guard=new NovaModelGuardProperties();guard.setEnabled(false);
            var router=new LlmRouterAspect(env,props,new LlmRouterBandit(props),guard,keys,probe,null,new LlmGatewayFailureClassifier(),tracker,null);
            assertThrows(RuntimeException.class,()->router.apiAttempt("local",1000,128));
            assertEquals(0,calls.get());assertThrows(RuntimeException.class,()->router.apiAttempt("api",1000,128).chat(List.of(UserMessage.from("Synthetic cue"))));assertEquals(1,calls.get());
            assertEquals("json_object",payload.get().path("response_format").path("type").asText());assertEquals("low",payload.get().path("reasoning_effort").asText());
            assertEquals(128,payload.get().path("max_completion_tokens").asInt(payload.get().path("max_tokens").asInt()));
            api.setName("gpt-5.6-luna");
            assertThrows(RuntimeException.class,()->router.apiAttempt("api",1000,128).chat(List.of(UserMessage.from("Synthetic cue"))));
            assertEquals(2,calls.get());assertEquals("default",payload.get().path("service_tier").asText());
            assertEquals("low",payload.get().path("reasoning_effort").asText());
            delay.set(1500);long began=System.nanoTime();
            assertThrows(RuntimeException.class,()->router.apiAttempt("api",100,128).chat(List.of(UserMessage.from("Synthetic deadline"))));
            assertTrue((System.nanoTime()-began)/1_000_000<900,"subsecond cue budget must not round up to one second");
            assertEquals(3,calls.get());
        }finally{http.stop(0);com.example.lms.search.TraceStore.clear();}
    }
}
