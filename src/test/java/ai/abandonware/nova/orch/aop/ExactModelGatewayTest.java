package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelSelectionException;
import com.example.lms.llm.RequestedModelSelection;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExactModelGatewayTest {
    @AfterEach void clear() { TraceStore.clear(); }
    @Test void ineligibleManualRouteCannotChooseHealthyBackupEvenInObserveMode() throws Throwable {
        var primary = config("expected-model", "http://127.0.0.1:19101/v1");
        var backup = config("other-model", "http://127.0.0.1:19102/v1");
        var gateway = gateway(primary, backup);
        when(gateway.evaluate("primary", primary, "chat")).thenReturn(RoutingEligibility.blocked(
                "primary", "local", primary.getName(), "chat", 0, false,
                List.of(LlmFailureClass.HEALTH_DOWN), Map.of()));
        var aspect = aspect(primary, backup, gateway);
        RequestedModelSelection.begin("llmrouter.primary");
        assertThrows(ModelSelectionException.class, () -> aspect.aroundLcWithTimeout(call()));
        verify(gateway, never()).evaluate("cloud", backup, "chat");
    }
    @Test void manualWireUsesExactEndpointAndModel() throws Throwable { wire(false); }
    @Test void manualUpstreamFailureMakesOneAttemptAndNeverCallsBackup() throws Throwable { wire(true); }
    @Test void exactLocalSelectionNeverArmsCloudFallbackViaRouteLocalInference() throws Throwable {
        AtomicInteger backupCalls = new AtomicInteger();
        var backupServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backupServer.createContext("/", exchange -> {
            backupCalls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        backupServer.start();
        try {
            var primary = config("qwen3.5:9b", "http://127.0.0.1:19103/v1");
            var backup = config("backup-synthetic-model",
                    "http://127.0.0.1:" + backupServer.getAddress().getPort() + "/v1");
            var gateway = gateway(primary, backup);
            when(gateway.localFailoverEnabled()).thenReturn(true);
            var aspect = aspect(primary, backup, gateway);
            RequestedModelSelection.begin("qwen3.5:9b");
            ChatModel failing = new ChatModel() {
                @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(
                        dev.langchain4j.model.chat.request.ChatRequest request) {
                    throw new RuntimeException(new java.util.concurrent.TimeoutException("synthetic soft timeout"));
                }
            };
            ChatModel wrapped = aspect.routeLocalInference(failing, "http://127.0.0.1:19103/v1",
                    "qwen3.5:9b", 5000, null, null, null, null, 128, "openai_chat_completions");
            assertThrows(RuntimeException.class, () -> wrapped.chat("probe"));
            assertEquals(0, backupCalls.get());
        } finally { backupServer.stop(0); }
    }

    private void wire(boolean fail) throws Throwable {
        AtomicInteger primaryCalls = new AtomicInteger(), backupCalls = new AtomicInteger();
        AtomicReference<String> wireModel = new AtomicReference<>();
        var primaryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var backupServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primaryServer.createContext("/v1/chat/completions", exchange -> {
            primaryCalls.incrementAndGet();
            wireModel.set(new ObjectMapper().readTree(exchange.getRequestBody()).path("model").asText());
            byte[] body = (fail ? "{\"error\":{\"message\":\"synthetic failure\"}}" :
                    "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"exact-synthetic-model\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"verified\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        backupServer.createContext("/", exchange -> {
            backupCalls.incrementAndGet(); exchange.sendResponseHeaders(503,-1); exchange.close();
        });
        primaryServer.start(); backupServer.start();
        try {
            var primary = config("exact-synthetic-model", "http://127.0.0.1:" + primaryServer.getAddress().getPort() + "/v1");
            var backup = config("backup-synthetic-model", "http://127.0.0.1:" + backupServer.getAddress().getPort() + "/v1");
            var aspect = aspect(primary, backup, gateway(primary, backup));
            RequestedModelSelection.begin("llmrouter.primary");
            ChatModel model = (ChatModel) aspect.aroundLcWithTimeout(call());
            if (fail) assertThrows(RuntimeException.class, () -> model.chat("synthetic routing probe"));
            else assertEquals("verified", model.chat("synthetic routing probe"));
            assertEquals("exact-synthetic-model", wireModel.get());
            assertEquals(1, primaryCalls.get());
            assertEquals(0, backupCalls.get());
        } finally { primaryServer.stop(0); backupServer.stop(0); }
    }
    private static LlmRouterProperties.ModelConfig config(String name,String base) {
        var config = new LlmRouterProperties.ModelConfig();
        config.setName(name); config.setBaseUrl(base); config.setEnabled(true);
        config.setProvider("local"); config.setStage("chat"); config.setFallbackKey("cloud");
        return config;
    }
    private static HybridLlmGatewayProbeService gateway(LlmRouterProperties.ModelConfig primary,
            LlmRouterProperties.ModelConfig backup) {
        var gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.cloudFallbackEnabled()).thenReturn(true);
        when(gateway.cloudRouteKey()).thenReturn("cloud");
        when(gateway.evaluate("primary",primary,"chat")).thenReturn(
                RoutingEligibility.eligible("primary","local",primary.getName(),"chat",100,false,Map.of()));
        when(gateway.evaluate("cloud",backup,"chat")).thenReturn(
                RoutingEligibility.eligible("cloud","local",backup.getName(),"chat",100,false,Map.of()));
        return gateway;
    }
    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(LlmRouterProperties.ModelConfig primary,
            LlmRouterProperties.ModelConfig backup, HybridLlmGatewayProbeService gateway) {
        var props = new LlmRouterProperties(); props.setEnabled(true);
        props.setModels(Map.of("primary",primary,"cloud",backup));
        var guard = new NovaModelGuardProperties(); guard.setEnabled(false);
        ObjectProvider<KeyResolver> keys = mock(ObjectProvider.class);
        return new LlmRouterAspect(new MockEnvironment().withProperty("llm.api-key","ollama"),
                props,new LlmRouterBandit(props),guard,keys,gateway,null,new LlmGatewayFailureClassifier(),null,null);
    }
    private static ProceedingJoinPoint call() {
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{"llmrouter.primary",0.0,null,null,null,128,5,0});
        return pjp;
    }
}
