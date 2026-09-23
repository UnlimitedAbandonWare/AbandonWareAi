package com.example.lms.llm;

import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.abandonware.ai.addons.budget.*;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectLocalFactoryFailoverTest {
    @AfterEach void clear(){TimeBudgetContext.clear();TraceStore.clear();}
    @Test void directNativeNameUsesCloudChainWithoutChangingMessagesOrOptions() throws Exception { verify(true); }
    @Test void directCompatibleNameUsesCloudChainWithoutChangingMessagesOrOptions() throws Exception { verify(false); }

    private void verify(boolean nativeMode) throws Exception {
        var payloads=new ArrayList<JsonNode>();var json=new ObjectMapper();
        try(var local=new Server(500,"{\"error\":\"GPU is lost\"}",payloads);
            var first=new Server(503,"{\"error\":\"temporarily unavailable\"}",payloads);
            var last=new Server(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK [source-7]\"}}]}",payloads);
            var context=new AnnotationConfigApplicationContext()){
            var env=new MockEnvironment().withProperty("llm.chat-model","qwen3:8b").withProperty("llm.base-url",local.base())
                .withProperty("llm.fast.base-url",local.base()).withProperty("llm.api-key","ollama")
                .withProperty("llm.ollama-native.think-false.enabled",Boolean.toString(nativeMode))
                .withProperty("FIXTURE_API_KEY","fixture-registered-key");
            context.setEnvironment(env);
            var gp=new LlmGatewayProperties();gp.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
            gp.getLocalDeviceFailover().setEnabled(true);gp.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
            gp.getCloud().setEnabled(true);gp.getCloud().setRouteKey("api-a");
            var props=new LlmRouterProperties();props.setEnabled(true);
            var localCfg=route("local","qwen3:8b",local.base());localCfg.setFallbackKey("api-a");
            var a=route("openai","cloud-a",first.base());a.setFallbackKey("api-b");
            props.setModels(new LinkedHashMap<>(Map.of("local",localCfg,"api-a",a,"api-b",route("openai","cloud-b",last.base()))));
            var tracker=new ModelRuntimeHealthTracker();
            var probe=new HybridLlmGatewayProbeService(gp,tracker,null,new LlmRouteScorer(),env);
            @SuppressWarnings("unchecked") ObjectProvider<KeyResolver> keys=mock(ObjectProvider.class);
            var router=new LlmRouterAspect(env,props,new LlmRouterBandit(props),new NovaModelGuardProperties(),keys,probe,null,new LlmGatewayFailureClassifier(),tracker,null);
            context.registerBean(LlmRouterAspect.class,()->router);context.registerBean(HybridLlmGatewayProbeService.class,()->probe);
            context.registerBean(LlmRouterProperties.class,()->props);
            context.registerBean(DynamicChatModelFactory.class,()->new DynamicChatModelFactory(env,new KeyResolver(env),tracker,gp));
            context.refresh();TimeBudgetContext.set(new TimeBudget(2400));
            var messages=List.<ChatMessage>of(SystemMessage.from("Keep source IDs"),UserMessage.from("prior"),AiMessage.from("prior reply"),UserMessage.from("source-7: synthetic evidence"));
            var model=context.getBean(DynamicChatModelFactory.class).lcWithTimeout("qwen3:8b",0.3,0.8,null,null,37,120);
            assertEquals("OK [source-7]",model.chat(messages).aiMessage().text());
            assertEquals(1,local.calls);assertEquals(1,first.calls);assertEquals(1,last.calls);assertEquals(3,payloads.size());
            assertEquals(payloads.get(0).path("messages"),payloads.get(1).path("messages"));
            assertEquals(payloads.get(0).path("messages"),payloads.get(2).path("messages"));
            var wire=payloads.get(2);assertEquals(0.3,wire.path("temperature").asDouble(),0.001);
            assertEquals(37,wire.has("max_tokens")?wire.path("max_tokens").asInt():wire.path("max_completion_tokens").asInt());
            assertTrue(TimeBudgetContext.get().remainingMillis()>0);
        }
    }
    private static LlmRouterProperties.ModelConfig route(String provider,String name,String url){
        var c=new LlmRouterProperties.ModelConfig();c.setEnabled(true);c.setProvider(provider);c.setName(name);c.setBaseUrl(url);c.setStage("chat");
        if(!provider.equals("local"))c.setCredentialEnv("FIXTURE_API_KEY");return c;
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server;int calls;
        Server(int status,String response,List<JsonNode> bodies)throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var handler=(com.sun.net.httpserver.HttpHandler)e->{calls++;bodies.add(new ObjectMapper().readTree(e.getRequestBody()));
                byte[] b=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);e.close();};
            server.createContext("/api/chat",handler);server.createContext("/v1/chat/completions",handler);server.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
        public void close(){server.stop(0);}
    }
}
