package com.example.lms.llm;

import com.example.lms.config.LocalLlmProcessManager;
import com.example.lms.service.embedding.OllamaEmbeddingModel;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ManagedEndpointTransportTest {
    @Test void alreadyBuiltSdkNativeAndEmbeddingClientsFollowManagedAddressAtDispatch() throws Exception {
        AtomicInteger oldCalls = new AtomicInteger(), managedCalls = new AtomicInteger();
        HttpServer old = server(oldCalls), managed = server(managedCalls);
        try {
            String origin = "http://127.0.0.1:" + old.getAddress().getPort();
            String destination = "http://127.0.0.1:" + managed.getAddress().getPort();
            class Manager extends LocalLlmProcessManager {
                volatile String target = origin;
                public String resolveServiceUrl(String requested) {
                    return requested != null && requested.startsWith(origin + "/") ? target + requested.substring(origin.length()) : requested;
                }
            }
            Manager manager = new Manager();
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("localLlmProcessManager", manager);
            var injection = new AutowiredAnnotationBeanPostProcessor();
            injection.setAutowiredAnnotationType(org.springframework.beans.factory.annotation.Autowired.class);
            injection.setBeanFactory(beans);
            var tracker = new ModelRuntimeHealthTracker(); injection.processInjection(tracker);
            var sdk = tracker.observedHttpClientBuilder("primary").connectTimeout(Duration.ofSeconds(3))
                    .readTimeout(Duration.ofSeconds(3)).build();
            var nativeModel = new OllamaNativeChatModel(origin + "/v1", "qwen3:8b", Duration.ofSeconds(3),
                    32, 0.0, null, tracker);
            var embedding = new OllamaEmbeddingModel(WebClient.builder().build()); injection.processInjection(embedding);
            ReflectionTestUtils.setField(embedding, "apiUrl", origin + "/api/embed");
            ReflectionTestUtils.setField(embedding, "provider", "ollama");
            ReflectionTestUtils.setField(embedding, "model", "fixture-embed");
            ReflectionTestUtils.setField(embedding, "dimensions", 2);
            ReflectionTestUtils.setField(embedding, "timeoutSec", 3);
            ReflectionTestUtils.setField(embedding, "normalizationMode", "SLICE_TO_CONFIGURED_DIM");
            manager.target = destination; // All clients were created while the old endpoint was selected.
            sdk.execute(HttpRequest.builder().method(HttpMethod.POST).url(origin + "/v1/chat/completions").body("{}").build());
            assertThat(nativeModel.chat(java.util.List.of(dev.langchain4j.data.message.UserMessage.from("fixture")))
                    .aiMessage().text()).isEqualTo("ok");
            assertThat(embedding.embed("fixture").content().vector()).hasSize(2);
            assertThat(oldCalls).hasValue(0);
            assertThat(managedCalls).hasValue(3);
        } finally { old.stop(0); managed.stop(0); com.example.lms.search.TraceStore.clear(); }
    }

    @Test void everyLlmConfigChatBeanDispatchesToTheReboundManagedEndpoint() throws Exception {
        AtomicInteger oldCalls = new AtomicInteger(), managedCalls = new AtomicInteger();
        HttpServer old = server(oldCalls), managed = server(managedCalls);
        try {
            String origin = "http://127.0.0.1:" + old.getAddress().getPort();
            String destination = "http://127.0.0.1:" + managed.getAddress().getPort();
            class Manager extends LocalLlmProcessManager {
                volatile String target = origin;
                public String resolveServiceUrl(String requested) {
                    return requested != null && requested.startsWith(origin + "/") ? target + requested.substring(origin.length()) : requested;
                }
            }
            Manager manager = new Manager();
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("localLlmProcessManager", manager);
            var injection = new AutowiredAnnotationBeanPostProcessor();
            injection.setAutowiredAnnotationType(org.springframework.beans.factory.annotation.Autowired.class);
            injection.setBeanFactory(beans);
            var tracker = new ModelRuntimeHealthTracker(); injection.processInjection(tracker);
            var env = new org.springframework.mock.env.MockEnvironment().withProperty("llm.api-key", "ollama");
            var keyResolver = new com.example.lms.guard.KeyResolver(env);
            var config = new com.example.lms.config.LlmConfig();
            String baseUrl = origin + "/v1";
            // Each bean was built while the old endpoint was the managed selection.
            java.util.List<dev.langchain4j.model.chat.ChatModel> built = java.util.List.of(
                    config.chatModel(baseUrl, keyResolver, "fixture:plain", 0.3d, 3L, 512, 0, tracker),
                    config.miniModel(baseUrl, keyResolver, "fixture:plain", 0.2d, 3L, 0, tracker),
                    config.fastChatModel(baseUrl, keyResolver, "fixture:plain", 0.0d, 3L, 0, 256, tracker),
                    config.exploreChatModel(baseUrl, keyResolver, "fixture:plain", 0.85d, 3L, 0, 512, tracker),
                    config.judgeChatModel(baseUrl, keyResolver, "fixture:plain", 3L, 0, 512, tracker),
                    config.highModel(baseUrl, keyResolver, "fixture:plain", 0.3d, 3L, 0, 1024, tracker));
            manager.target = destination;
            for (var model : built) {
                assertThat(model.chat("hi")).isEqualTo("ok");
            }
            assertThat(oldCalls).hasValue(0);
            assertThat(managedCalls).hasValue(6);
        } finally { old.stop(0); managed.stop(0); com.example.lms.search.TraceStore.clear(); }
    }

    private static HttpServer server(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String json = path.equals("/api/embed")
                    ? "{\"embeddings\":[[0.6,0.8]],\"prompt_eval_count\":1}"
                    : path.endsWith("/chat/completions")
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    : "{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start(); return server;
    }
}
