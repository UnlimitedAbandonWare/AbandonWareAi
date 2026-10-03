package ai.abandonware.nova.orch.llm;

import com.example.lms.llm.gateway.LlmGatewayException;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResponsesTerminalStatusContractTest {
    @Test void nullMetadataBecomesEmptyNotNull() {
        var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException("fixture_failed",
                com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, null, null, "failed", null, null);
        assertNotNull(terminal.metadata());
        assertNull(terminal.metadata().tokenUsage());
        assertNull(terminal.metadata().modelName());
    }

    @ParameterizedTest @CsvSource({"401,invalid_api_key", "429,subscription_sharing_usage_limit_exceeded"})
    void oauthAuthFailureSurfacesOriginalReasonNotNpe(int status, String code) throws Exception {
        try (var f = new ChatGptOAuthRedTeamContractTest.Fixture(
                "{\"error\":{\"code\":\"" + code + "\"}}", status, "application/json")) {
            var terminal = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> f.oauthModel(() -> "synthetic-credential").chat(List.of(UserMessage.from("fixture"))));
            var dto = assertDoesNotThrow(() -> com.example.lms.dto.ChatResponseDto.terminal(terminal, 1L));
            assertEquals("chatgpt_oauth_http_" + status, dto.getGenerationTermination().reason());
            assertEquals(code, dto.getGenerationTermination().providerCode());
            assertNull(dto.getGenerationTermination().inputTokens());
            assertEquals("failed", dto.getGenerationTermination().status());
            assertEquals(1, f.calls.get());
        }
    }

    @ParameterizedTest @CsvSource({"failed,", "incomplete,max_output_tokens", "cancelled,"})
    void failedSseUsageIsPreserved(String status, String incomplete) throws Exception {
        String event = ChatGptOAuthRedTeamContractTest.sseEvent("response." + status,
                "\"response\":{\"id\":\"resp_fixture\",\"status\":\"" + status + "\",\"model\":\"fixture-model\","
                + "\"incomplete_details\":{\"reason\":\"" + (incomplete == null ? "" : incomplete) + "\"},"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}");
        try (var f = new ChatGptOAuthRedTeamContractTest.Fixture(event, 200, "text/event-stream")) {
            var terminal = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> f.oauthModel(() -> "synthetic-credential").chat(List.of(UserMessage.from("fixture"))));
            var dto = com.example.lms.dto.ChatResponseDto.terminal(terminal, 1L);
            assertEquals(status, dto.getGenerationTermination().status());
            assertEquals("chatgpt_oauth_response_" + status, dto.getGenerationTermination().reason());
            assertEquals(3, dto.getGenerationTermination().inputTokens());
            assertEquals(2, dto.getGenerationTermination().outputTokens());
            assertEquals(5, dto.getGenerationTermination().totalTokens());
            assertEquals("resp_fixture", dto.getGenerationTermination().responseId());
            assertEquals("fixture-model", dto.getModelUsed());
            assertNull(terminal.partialText());
            assertEquals(1, f.calls.get());
        }
    }

    @Test void unknownUsageStaysNullNotZero() {
        var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException("fixture_failed",
                com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, null,
                dev.langchain4j.model.chat.response.ChatResponseMetadata.builder().build(), "failed", null, null);
        var dto = com.example.lms.dto.ChatResponseDto.terminal(terminal, 1L);
        assertNull(dto.getGenerationTermination().inputTokens());
        assertNull(dto.getGenerationTermination().outputTokens());
        assertNull(dto.getGenerationTermination().totalTokens());
    }

    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        Fixture(String response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", e -> {
                e.getRequestBody().readAllBytes(); calls.incrementAndGet();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
            }); server.start();
        }
        OpenAiResponsesChatModel model() { return new OpenAiResponsesChatModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture", "gpt-5-pro", 5000); }
        public void close() { server.stop(0); }
    }
    @Test void completedCarriesStopAndUsage() throws Exception {
        try (var f = new Fixture("{\"status\":\"completed\",\"model\":\"gpt-5-pro\",\"output_text\":\"done\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}")) {
            var r = f.model().chat(List.of(UserMessage.from("fixture")));
            assertEquals(FinishReason.STOP, r.finishReason());
            assertEquals("done", r.aiMessage().text()); assertEquals(5, r.tokenUsage().totalTokenCount());
            assertEquals(1, f.calls.get());
        }
    }
    @ParameterizedTest @CsvSource({
        "incomplete,max_output_tokens,partial,output_limit_reached",
        "incomplete,max_output_tokens,,output_limit_reached",
        "incomplete,content_filter,,content_filter",
        "failed,,,responses_failed", "cancelled,,,responses_cancelled",
        "queued,,,responses_not_complete", "in_progress,,,responses_not_complete",
        "unknown,,,responses_contract_error", "incomplete,unknown,,responses_contract_error"
    }) void nonCompletedIsNeverOrdinarySuccess(String status, String incomplete, String text, String reason) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "status", status, "incomplete_details", java.util.Map.of("reason", incomplete == null ? "" : incomplete),
                "output_text", text == null ? "" : text));
        try (var f = new Fixture(body)) {
            var e = assertThrows(LlmGatewayException.class, () -> f.model().chat(List.of(UserMessage.from("fixture"))));
            assertEquals(reason, e.reasonCode()); assertEquals(1, f.calls.get());
        }
    }
    @Test void refusalIsNotBlankProviderFailure() throws Exception {
        try (var f = new Fixture("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"blocked\"}]}]}")) {
            var e = assertThrows(LlmGatewayException.class, () -> f.model().chat(List.of(UserMessage.from("fixture"))));
            assertEquals("refusal", e.reasonCode());
        }
    }
    @Test void functionOutputIsExplicitlyUnsupported() throws Exception {
        try (var f = new Fixture("{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"lookup\",\"arguments\":\"{}\"}]}")) {
            var e = assertThrows(LlmGatewayException.class, () -> f.model().chat(List.of(UserMessage.from("fixture"))));
            assertEquals("responses_tools_unsupported", e.reasonCode());
        }
    }
    @Test void missingStatusAndMalformedJsonAreContractFailures() throws Exception {
        for (String body : List.of("{\"output_text\":\"not proven completed\"}", "{broken", "{\"status\":\"completed\"}")) {
            try (var f = new Fixture(body)) {
                var e = assertThrows(LlmGatewayException.class, () -> f.model().chat(List.of(UserMessage.from("fixture"))));
                assertEquals("responses_contract_error", e.reasonCode());
            }
        }
    }
}
