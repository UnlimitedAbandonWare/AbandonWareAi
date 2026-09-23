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
