package ai.abandonware.nova.orch.llm;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExpectedFailureChatModelTest {

    @Test
    void terminalRouteFailurePreservesReasonAcrossSyncAndStreamingEntryPoints() {
        var failure = new com.example.lms.llm.gateway.LlmGatewayException(
                "synthetic route OFF", com.example.lms.llm.gateway.LlmFailureClass.DISABLED, "route_disabled");
        ExpectedFailureChatModel model = ExpectedFailureChatModel.forRouteFailure("synthetic-bean", failure);
        var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(List.of(UserMessage.from("synthetic OFF probe"))).build();
        List<org.junit.jupiter.api.function.Executable> calls = List.of(
                () -> model.chat(List.of(UserMessage.from("synthetic OFF probe"))),
                () -> model.chat(request), () -> model.doChat(request),
                () -> model.chat("synthetic OFF probe"),
                () -> model.chat(UserMessage.from("synthetic OFF probe")));
        for (var call : calls) {
            org.junit.jupiter.api.Assertions.assertSame(failure,
                    org.junit.jupiter.api.Assertions.assertThrows(
                            com.example.lms.llm.gateway.LlmGatewayException.class, call));
        }
        java.util.concurrent.atomic.AtomicInteger partials = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger completions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) { partials.incrementAndGet(); }
            @Override public void onCompleteResponse(ChatResponse response) { completions.incrementAndGet(); }
            @Override public void onError(Throwable error) {
                org.junit.jupiter.api.Assertions.assertSame(failure, error);
                errors.incrementAndGet();
            }
        };
        model.chat(List.<ChatMessage>of(), handler);
        model.chat("synthetic OFF probe", handler);
        model.doChat(request, handler);
        assertEquals(0, partials.get());
        assertEquals(0, completions.get());
        assertEquals(3, errors.get());
    }

    @Test
    void expectedFailureEvidenceNeverTreatsTheLocalUxMessageAsProviderResponse() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("expected-disabled-request", "expected-disabled-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "disabled-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        ModelRuntimeHealthTracker.ExpectedFailureAttemptEvidence evidence =
                tracker.expectedFailureAttemptEvidence(
                        "primary",
                        tracker.redactedRequestAttemptRoute(
                                "expected_failure", "disabled-model", null, "openai_responses"),
                        ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                                "openai", "disabled-model", "openai_responses", Map.of()));
        ExpectedFailureChatModel model = new ExpectedFailureChatModel(
                "local UX message that is not a provider response", "hash:abc", evidence);

        model.chat(List.of(UserMessage.from("disabled request")));

        Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertEquals("hash:unknown", row.get("responseHash"));
        assertEquals(0, row.get("responseCharCount"));
        assertEquals(0, row.get("responseUtf8ByteCount"));
        assertEquals(false, row.get("responseObserved"));
        assertEquals(13, row.get("optionItemCount"));
        TraceStore.clear();
    }

    @Test
    void expectedFailureCanBeConsumedThroughStreamingChatModel() {
        ExpectedFailureChatModel model = new ExpectedFailureChatModel("expected failure", "hash:abc");
        StreamingChatModel streaming = assertInstanceOf(StreamingChatModel.class, model);
        AtomicReference<String> partial = new AtomicReference<>();
        AtomicReference<ChatResponse> complete = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        streaming.chat(List.<ChatMessage>of(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partial.set(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                complete.set(completeResponse);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
            }
        });

        assertEquals("expected failure", partial.get());
        assertEquals("expected failure", complete.get().aiMessage().text());
        assertNull(error.get());
    }
}
