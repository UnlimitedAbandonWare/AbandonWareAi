package ai.abandonware.nova.orch.llm;

import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.aop.OpenAiChatModelGuardAspect;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResponsesWireContractTest {
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> body = new AtomicReference<>();
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", e -> {
                calls.incrementAndGet();
                body.set(new ObjectMapper().readTree(e.getRequestBody()));
                byte[] out = "{\"status\":\"completed\",\"model\":\"gpt-5-pro\",\"output_text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, out.length);
                e.getResponseBody().write(out); e.close();
            });
            server.start();
        }
        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        OpenAiResponsesChatModel model() { return new OpenAiResponsesChatModel(base(), "loopback-fixture", "gpt-5-pro", 5000); }
        public void close() { server.stop(0); }
    }

    @Test void structuredRolesPreserveOrderAndText() throws Exception {
        try (var f = new Fixture()) {
            var result = f.model().chat(List.of(SystemMessage.from("SYSTEM"),
                    UserMessage.from("한글\n\"user\""), AiMessage.from("assistant"), SystemMessage.from("LATE")));
            assertEquals("ok", result.aiMessage().text());
            JsonNode input = f.body.get().path("input");
            assertTrue(input.isArray());
            assertEquals(List.of("system", "user", "assistant", "system"),
                    java.util.stream.StreamSupport.stream(input.spliterator(), false).map(n -> n.path("role").asText()).toList());
            assertEquals("SYSTEM", input.get(0).path("content").asText());
            assertEquals("한글\n\"user\"", input.get(1).path("content").get(0).path("text").asText());
            assertEquals("assistant", input.get(2).path("content").asText());
            assertEquals("LATE", input.get(3).path("content").asText());
        }
    }

    @Test void toolDefinitionsAreRejectedBeforeHttp() throws Exception {
        try (var f = new Fixture()) {
            var request = ChatRequest.builder().messages(UserMessage.from("lookup"))
                    .toolSpecifications(ToolSpecification.builder().name("lookup").build()).build();
            var error = assertThrows(LlmGatewayException.class, () -> f.model().doChat(request));
            assertEquals("responses_tools_unsupported", error.reasonCode());
            assertEquals(0, f.calls.get());
        }
    }

    @Test void toolHistoryIsRejectedBeforeHttp() throws Exception {
        try (var f = new Fixture()) {
            var error = assertThrows(LlmGatewayException.class, () -> f.model().chat(List.of(
                    UserMessage.from("lookup"), ToolExecutionResultMessage.from("call-1", "lookup", "result"))));
            assertEquals("responses_tools_unsupported", error.reasonCode());
            assertEquals(0, f.calls.get());
        }
    }

    @Test @SuppressWarnings("unchecked")
    void guardOverloadsSendResolvedOutputCap() throws Throwable {
        try (var f = new Fixture()) {
            NovaModelGuardProperties p = new NovaModelGuardProperties();
            p.setMode(NovaModelGuardProperties.Mode.ROUTE_RESPONSES); p.setOpenAiBaseOnly(false);
            KeyResolver key = mock(KeyResolver.class);
            when(key.getPropertyOrEnvOpenAiKey()).thenReturn("loopback-fixture");
            ObjectProvider<KeyResolver> keys = mock(ObjectProvider.class);
            when(keys.getIfAvailable()).thenReturn(key);
            var guard = new OpenAiChatModelGuardAspect(p, new MockEnvironment().withProperty("llm.base-url", f.base()), keys);
            for (Object[] args : List.of(
                    new Object[]{"gpt-5-pro", 1.0, 1.0, 256, 17},
                    new Object[]{"gpt-5-pro", 1.0, 1.0, 0.0, 0.0, 256, 19},
                    new Object[]{"gpt-5-pro", 1.0, 1.0, 0.0, 0.0, 256, 23, 2})) {
                var jp = mock(ProceedingJoinPoint.class); when(jp.getArgs()).thenReturn(args);
                ((ChatModel) guard.guardLcWithTimeout(jp)).chat(List.of(UserMessage.from("cap")));
                assertEquals(256, f.body.get().path("max_output_tokens").asInt());
            }
        }
    }
}
