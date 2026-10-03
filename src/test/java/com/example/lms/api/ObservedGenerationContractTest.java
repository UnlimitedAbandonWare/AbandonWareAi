package com.example.lms.api;

import com.example.lms.dto.*;
import com.example.lms.llm.*;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservedGenerationContractTest {
    @AfterEach void clear() { TraceStore.clear(); }
    private ChatModel route(String provider, String actual) {
        var tracker = new ModelRuntimeHealthTracker();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("synthetic answer"))
                        .metadata(ChatResponseMetadata.builder().modelName(actual).build()).build();
            }
        };
        return tracker.decorateRequestAttempt(delegate, "primary",
                tracker.redactedRequestAttemptRoute(provider, "requested-other", "https://example.invalid/v1", "openai_chat_completions"),
                ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(provider, "requested-other", "openai_chat_completions", Map.of()));
    }
    private void call(ChatModel model) throws Exception {
        TimedChatModelCaller.chat(model, List.of(UserMessage.from("synthetic")), Duration.ofSeconds(2), "fixture", "configured-other");
    }
    private void projections(String actual, String provider, int count) throws Exception {
        var frozen = GenerationObservation.current();
        frozen.publish();
        var trace = TraceStore.getAll();
        var json = new ObjectMapper().valueToTree(ChatStreamEvent.done("configured-other", false, 12L, null, 91L).withObservation(trace));
        if (actual == null) assertTrue(json.path("observedModel").isNull());
        else assertEquals(actual, json.path("observedModel").asText());
        assertEquals(provider, json.path("observedProvider").asText());
        assertEquals(count, json.path("fallbackCount").asInt());
        var durable = ChatTraceMetaMessageRestorer.projectDiagnostics(trace);
        if (actual == null) assertFalse(durable.containsKey("diag.observedModel"));
        else assertEquals("s:" + actual, durable.get("diag.observedModel"));
        assertEquals("s:" + provider, durable.get("diag.observedProvider"));
        assertEquals("n:" + count, durable.get("diag.fallbackCount"));
    }
    @Test void cloudASuccessUsesResponseIdentityAndFreezesIt() throws Exception {
        call(route("cloudA", "actual-cloud-a"));
        var frozen = GenerationObservation.current();
        call(route("aux", "actual-aux"));
        frozen.store();
        projections("actual-cloud-a", "cloudA", 0);
    }
    @Test void aFailureThenBSuccessRecordsOneFallback() throws Exception {
        ChatModel failed = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                throw new LlmGatewayException("synthetic admission", LlmFailureClass.HEALTH_DOWN, "provider_unavailable");
            }
        };
        call(new FallbackAwareChatModel(failed, () -> route("cloudB", "actual-cloud-b"),
                new LlmGatewayFailureClassifier(), null, "cloudA", "cloudB").withApiFirstPolicy());
        projections("actual-cloud-b", "cloudB", 1);
        assertEquals("health_down", GenerationObservation.current().fallbackReason());
    }
    @Test void missingResponseModelStaysNullAfterPreviousCall() throws Exception {
        call(route("cloudA", "old-observation"));
        call(route("cloudA", null));
        projections(null, "cloudA", 0);
        assertEquals("response_model_missing", GenerationObservation.current().observedReason());
    }
}
