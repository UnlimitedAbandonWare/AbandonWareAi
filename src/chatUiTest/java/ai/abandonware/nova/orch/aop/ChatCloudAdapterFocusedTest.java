package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCloudAdapterFocusedTest {
    @Test void nativeTimeoutReachesVerifiedCloudSdkExactlyOnce() throws Exception {
        var wireCalls = new AtomicInteger();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            wireCalls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"synthetic\",\"model\":\"openai/gpt-oss-120b\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"READY\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var cloud = new LlmRouterProperties.ModelConfig();
            cloud.setEnabled(true); cloud.setProvider("groq"); cloud.setStage("chat");
            cloud.setName("openai/gpt-oss-120b");
            cloud.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            cloud.setFallbackOnly(true); cloud.setResponseModelVerificationRequired(true);
            var props = new LlmRouterProperties(); props.setEnabled(true);
            props.setModels(new LinkedHashMap<>(Map.of("api3", cloud)));
            var gateway = mock(HybridLlmGatewayProbeService.class);
            when(gateway.cloudFallbackEnabled()).thenReturn(true);
            when(gateway.localFailoverEnabled()).thenReturn(true);
            when(gateway.guardLocalModel(any(), anyString(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
            when(gateway.cloudRouteKey()).thenReturn("api3");
            when(gateway.isEnforce()).thenReturn(true);
            when(gateway.evaluate(anyString(), any(), anyString())).thenAnswer(inv -> {
                LlmRouterProperties.ModelConfig cfg = inv.getArgument(1);
                return RoutingEligibility.eligible(inv.getArgument(0), cfg.getProvider(),
                        cfg.getName(), inv.getArgument(2), 100, cfg.isFallbackOnly(), Map.of());
            });
            var environment = new MockEnvironment().withProperty("llm.api-key", "ollama")
                    .withProperty("llm.ollama-native.think-false.enabled", "false");
            @SuppressWarnings("unchecked")
            ObjectProvider<KeyResolver> keys = mock(ObjectProvider.class);
            var guard = new NovaModelGuardProperties(); guard.setEnabled(false);
            var router = new LlmRouterAspect(environment, props, new LlmRouterBandit(props),
                    guard, keys, gateway, null, new LlmGatewayFailureClassifier(),
                    new ModelRuntimeHealthTracker(), null);
            var localCalls = new AtomicInteger();
            ChatModel local = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    localCalls.incrementAndGet();
                    throw new LlmGatewayException("synthetic timeout", LlmFailureClass.TIMEOUT_SOFT, "synthetic_timeout");
                }
            };
            ChatResponse response = router.routeLocalInference(local, "http://127.0.0.1:19177/v1",
                    "smtek/Qwen3.8-27B:Q3_K_XL", 10000, 0.8, 0.85, 0.0, 0.0, 512, "ollama_native")
                    .chat(List.of(SystemMessage.from("Synthetic system"), SystemMessage.from("Synthetic context"),
                            UserMessage.from("Synthetic test")));
            assertEquals("READY", response.aiMessage().text());
            assertEquals("openai/gpt-oss-120b", response.modelName());
            assertEquals(1, localCalls.get()); assertEquals(1, wireCalls.get());
            assertEquals("openai/gpt-oss-120b", new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get()).path("model").asText());
        } finally { server.stop(0); TraceStore.clear(); }
    }
}
