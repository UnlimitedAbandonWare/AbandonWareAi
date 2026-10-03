package ai.abandonware.nova.orch.llm;

import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmResponseTerminalException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ChatGptResponsesTransportContractTest {
    @Test void toolsRequiredAreRejectedBeforeCredentialOrHttpAndNeverStripped() throws Exception {
        try(var f=new Fixture(completed(),200,"text/event-stream")) {
            var resolutions=new AtomicInteger();
            var model=f.model(()->{resolutions.incrementAndGet();return "synthetic-oauth-a";},3000);
            var tool=dev.langchain4j.agent.tool.ToolSpecification.builder().name("fixture_tool").description("synthetic").build();
            var request=ChatRequest.builder().messages(List.of(UserMessage.from("fixture"))).toolSpecifications(List.of(tool)).build();
            var failure=assertThrows(com.example.lms.llm.gateway.LlmGatewayException.class,()->model.doChat(request));
            assertEquals("responses_tools_unsupported",failure.reasonCode());
            assertEquals(0,resolutions.get());assertEquals(0,f.calls.get());
        }
    }
    @Test void oauthTerminalCannotReplayOnApiBillingRoute() throws Exception {
        try(var f=new Fixture(event("response.failed","\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}"),200,"text/event-stream")) {
            var backups=new AtomicInteger();
            var fallback=org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
            var model=new com.example.lms.llm.gateway.FallbackAwareChatModel(f.model(()->"synthetic-oauth-a",3000),
                    ()->{backups.incrementAndGet();return fallback;},null,null,"chatgpt-oauth:fixture-gpt","api-fixture");
            assertThrows(LlmResponseTerminalException.class,()->model.chat(List.of(UserMessage.from("fixture"))));
            assertEquals(1,f.calls.get());assertEquals(0,backups.get());
            org.mockito.Mockito.verifyNoInteractions(fallback);
        }
    }
    static final ObjectMapper JSON = new ObjectMapper();
    static String event(String type, String fields) {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\"," + fields + "}\n\n";
    }
    static String completed() {
        return event("response.output_text.delta", "\"delta\":\"안녕\"")
                + event("response.completed", "\"response\":{\"id\":\"resp_fixture\",\"status\":\"completed\",\"model\":\"fixture-gpt\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}");
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> body = new AtomicReference<>();
        final AtomicReference<String> bearer = new AtomicReference<>(), accept = new AtomicReference<>();
        Fixture(String response, int status, String contentType) throws Exception {
            this(response, status, contentType, 0);
        }
        Fixture(String response, int status, String contentType, long lineDelayMs) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", exchange -> {
                calls.incrementAndGet();
                body.set(JSON.readTree(exchange.getRequestBody()));
                bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
                accept.set(exchange.getRequestHeaders().getFirst("Accept"));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, 0);
                try {
                    // Deliberately split UTF-8 characters and SSE event boundaries across chunks.
                    for (byte value : bytes) {
                        exchange.getResponseBody().write(value); exchange.getResponseBody().flush();
                        if (value == '\n' && lineDelayMs > 0) {
                            try { Thread.sleep(lineDelayMs); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                } finally { exchange.close(); }
            });
            server.start();
        }
        OpenAiResponsesChatModel model(Supplier<String> bearer, long timeout) throws Exception {
            return OpenAiResponsesChatModel.class
                    .getConstructor(String.class, String.class, long.class, Supplier.class)
                    .newInstance("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-gpt", timeout, bearer);
        }
        public void close() { server.stop(0); }
    }

    @Test void completedSsePreservesRolesMetadataAndOmittedOptions() throws Exception {
        try (var f = new Fixture(completed(), 200, "text/event-stream")) {
            var response = f.model(() -> "synthetic-oauth-a", 3000).doChat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("instructions"), UserMessage.from("question"), AiMessage.from("history")))
                    .maxOutputTokens(7).temperature(.4).topP(.8).build());
            assertEquals("안녕", response.aiMessage().text());
            assertEquals("resp_fixture", response.metadata().id());
            assertEquals("fixture-gpt", response.metadata().modelName());
            assertEquals(5, response.tokenUsage().totalTokenCount());
            assertEquals("Bearer synthetic-oauth-a", f.bearer.get());
            assertEquals("text/event-stream", f.accept.get());
            JsonNode body = f.body.get();
            assertEquals(4, body.size());
            assertFalse(body.path("store").asBoolean(true));
            assertTrue(body.path("stream").asBoolean());
            assertEquals("developer", body.path("input").get(0).path("role").asText());
            assertEquals("instructions", body.path("input").get(0).path("content").asText());
            assertEquals(1, f.calls.get());
        }
    }

    @Test void bearerIsResolvedAtDispatchAndNotCachedInModel() throws Exception {
        try (var f = new Fixture(completed(), 200, "text/event-stream")) {
            var bearer = new AtomicReference<>("synthetic-oauth-a");
            var model = f.model(bearer::get, 3000);
            bearer.set("synthetic-oauth-b");
            model.chat(List.of(UserMessage.from("fixture")));
            assertEquals("Bearer synthetic-oauth-b", f.bearer.get());
            assertFalse(model.toString().contains("synthetic-oauth"));
        }
    }

    @Test void failedIncompleteInterruptedAndDoneOnlyNeverBecomeSuccessfulAnswers() throws Exception {
        for (String response : List.of(
                event("response.output_text.delta", "\"delta\":\"partial\""),
                "data: [DONE]\n\n",
                "data: invalid-json\n\n",
                event("response.failed", "\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}"),
                event("response.incomplete", "\"response\":{\"status\":\"incomplete\"}"),
                event("error", "\"code\":\"subscription_sharing_user_not_eligible\""),
                event("response.completed", "\"response\":{\"status\":\"in_progress\",\"output_text\":\"bad\"}"))) {
            try (var f = new Fixture(response, 200, "text/event-stream")) {
                var model = f.model(() -> "synthetic-oauth-a", 3000);
                var error = assertThrows(LlmResponseTerminalException.class, () -> model.chat(List.of(UserMessage.from("fixture"))));
                assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(error));
                assertEquals(1, f.calls.get());
                assertFalse(error.toString().contains("synthetic-oauth"));
            }
        }
    }

    @Test void jsonHttp200IsNotAnOauthSseCompletion() throws Exception {
        try (var f = new Fixture("{\"status\":\"completed\",\"output_text\":\"bad\"}", 200, "application/json")) {
            var model = f.model(() -> "synthetic-oauth-a", 3000);
            assertThrows(LlmResponseTerminalException.class, () -> model.chat(List.of(UserMessage.from("fixture"))));
        }
    }

    @Test void admissionFailureAndUnavailableCredentialAreNonReplayable() throws Exception {
        try (var f = new Fixture("{\"error\":{\"code\":\"subscription_sharing_user_not_eligible\",\"message\":\"private diagnostic\"}}", 403, "application/json")) {
            var model = f.model(() -> "synthetic-oauth-a", 3000);
            var error = assertThrows(LlmResponseTerminalException.class, () -> model.chat(List.of(UserMessage.from("fixture"))));
            assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(error));
            assertEquals("subscription_sharing_user_not_eligible", error.providerCode());
            assertFalse(error.toString().contains("private diagnostic"));
            var missing = f.model(() -> null, 3000);
            assertThrows(LlmResponseTerminalException.class, () -> missing.chat(List.of(UserMessage.from("fixture"))));
            assertEquals(1, f.calls.get());
        }
    }

    @Test void expiredBudgetDuringCredentialResolutionPreventsHttpDispatch() throws Exception {
        try (var f = new Fixture(completed(), 200, "text/event-stream")) {
            var model = f.model(() -> {
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return "synthetic-oauth-a";
            }, 25);
            var error = assertThrows(LlmResponseTerminalException.class,
                    () -> model.chat(List.of(UserMessage.from("fixture"))));
            assertEquals("chatgpt_oauth_deadline_exhausted", error.reasonCode());
            assertEquals(0, f.calls.get());
        }
    }

    @Test void heartbeatsCannotRenewTheRequestDeadlineOrExposePartialText() throws Exception {
        String stream = event("response.output_text.delta", "\"delta\":\"unreleased\"")
                + ": keepalive\n\n".repeat(20) + completed();
        try (var f = new Fixture(stream, 200, "text/event-stream", 40)) {
            var model = f.model(() -> "synthetic-oauth-a", 400);
            long started = System.nanoTime();
            var error = assertThrows(LlmResponseTerminalException.class,
                    () -> model.chat(List.of(UserMessage.from("fixture"))));
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(com.example.lms.llm.gateway.LlmFailureClass.TIMEOUT_SOFT, error.failureClass());
            assertEquals("chatgpt_oauth_deadline_exhausted", error.reasonCode());
            assertEquals("awaiting_terminal_timeout", com.example.lms.search.TraceStore.get("chatgpt.oauth.phase"));
            assertEquals(true, com.example.lms.search.TraceStore.get("chatgpt.oauth.firstByteObserved"));
            assertEquals(true, com.example.lms.search.TraceStore.get("chatgpt.oauth.firstDeltaObserved"));
            assertNull(error.partialText());
            assertTrue(elapsedMs < 2000, "OAuth deadlines must not be raised to the legacy five-second minimum");
            assertEquals(1, f.calls.get());
        }
    }

    @Test void lateTerminalEventsRetainTheirStateAndNeverReleaseDeltas() throws Exception {
        for (String state : List.of("failed", "incomplete", "cancelled")) {
            String stream = event("response.output_text.delta", "\"delta\":\"unreleased\"")
                    + event("response." + state, "\"response\":{\"status\":\"" + state
                    + "\",\"incomplete_details\":{\"reason\":\"content_filter\"}}");
            try (var f = new Fixture(stream, 200, "text/event-stream")) {
                var model = f.model(() -> "synthetic-oauth-a", 3000);
                var error = assertThrows(LlmResponseTerminalException.class,
                        () -> model.chat(List.of(UserMessage.from("fixture"))));
                assertEquals(state, error.status());
                assertNull(error.partialText());
                assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(error));
            }
        }
    }
}
