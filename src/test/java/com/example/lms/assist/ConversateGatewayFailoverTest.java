package com.example.lms.assist;

import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateGatewayFailoverTest {
    @Test void nativeCardFailureUsesRegisteredApisAndSameEvidenceBeforeTheExistingVerifier() throws Exception {
        String text="미개봉 제품은 7일 이내 환불이 가능합니다.";
        var json=new ObjectMapper();String answer=ConversateLocalCardGeneratorTest.compactReply(text,"[\"e1\"]","[]");
        var received=new ArrayList<String>();
        try(var local=server("/api/chat",500,"{\"error\":\"GPU is lost\"}",received);
            var a=server("/v1/chat/completions",503,"{\"error\":\"unavailable\"}",received);
            var b=server("/v1/chat/completions",200,json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content",answer))))),received);
            var context=new AnnotationConfigApplicationContext()){
            var env=new MockEnvironment().withProperty("conversate.enabled","true").withProperty("llmrouter.enabled","true")
                    .withProperty("llm.api-key","ollama").withProperty("FIXTURE_API_KEY","synthetic-registered-key");
            context.setEnvironment(env);
            var props=new LlmRouterProperties();
            var primary=route("local","qwen3:fixture",local.base());primary.setFallbackKey("cloud-a");primary.setDeviceRole("rtx3090");
            var first=route("openai","cloud-a",a.base());first.setFallbackKey("cloud-b");
            props.setModels(new LinkedHashMap<>(Map.of("local",primary,"cloud-a",first,"cloud-b",route("openai","cloud-b",b.base()))));
            var policy=new LlmGatewayProperties();policy.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
            policy.getLocalDeviceFailover().setEnabled(true);policy.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
            policy.getCloud().setEnabled(true);policy.getCloud().setRouteKey("cloud-a");
            var tracker=new ModelRuntimeHealthTracker();var probe=new HybridLlmGatewayProbeService(policy,tracker,null,new LlmRouteScorer(),env);
            @SuppressWarnings("unchecked") ObjectProvider<KeyResolver> keys=mock(ObjectProvider.class);
            var guard=new NovaModelGuardProperties();guard.setEnabled(false);
            var router=new LlmRouterAspect(env,props,new LlmRouterBandit(props),guard,keys,probe,null,new LlmGatewayFailureClassifier(),tracker,null);
            context.registerBean(LlmRouterAspect.class,()->router);
            context.registerBean(ConversateLocalCardGenerator.class,()->new ConversateLocalCardGenerator(true,local.base(),"qwen3:fixture",4000));
            context.refresh();
            var result=context.getBean(ConversateLocalCardGenerator.class).generate("환불 조건은?",ConversateLocalCardGeneratorTest.EVIDENCE,true,1000);
            assertEquals("GENERATED",result.reason());assertEquals(text,result.card().text());assertEquals(List.of("doc-1"),result.card().sourceIds());
            assertEquals(3,result.attempts());assertEquals(1,local.calls.get());assertEquals(1,a.calls.get());assertEquals(1,b.calls.get());
            assertEquals(3,received.size());var original=json.readTree(received.get(0)).path("messages");
            assertEquals(original,json.readTree(received.get(1)).path("messages"));assertEquals(original,json.readTree(received.get(2)).path("messages"));
            assertFalse(TraceStore.getAll().toString().contains(text));
        }finally{TraceStore.clear();}
    }
    private static LlmRouterProperties.ModelConfig route(String provider,String model,String url){
        var cfg=new LlmRouterProperties.ModelConfig();cfg.setEnabled(true);cfg.setProvider(provider);cfg.setName(model);cfg.setBaseUrl(url);cfg.setStage("chat");
        if(!provider.equals("local"))cfg.setCredentialEnv("FIXTURE_API_KEY");return cfg;
    }
    private static Server server(String path,int status,String response,List<String> requests) throws Exception {
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        http.createContext(path,e->{calls.incrementAndGet();requests.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] data=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();});
        http.start();return new Server(http,calls);
    }
    private record Server(HttpServer http,AtomicInteger calls) implements AutoCloseable {
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort()+"/v1";}
        public void close(){http.stop(0);}
    }
}
