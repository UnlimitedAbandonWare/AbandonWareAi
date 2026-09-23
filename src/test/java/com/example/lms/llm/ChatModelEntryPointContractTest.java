package com.example.lms.llm;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.llm.gateway.FallbackAwareChatModel;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LangChain4j 1.0.1 contract: every entry point funnels through chat(ChatRequest)
 * into doChat(ChatRequest). Wrappers must forward the full request, and a legacy
 * list-only delegate keeps working through the guarded fallback.
 */
class ChatModelEntryPointContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    /** Legacy foreign adapter: only chat(List) is implemented; chat(ChatRequest) throws "Not implemented". */
    private static final class ListOnlyChatModel implements ChatModel {
        private final String answer;
        private final AtomicInteger calls = new AtomicInteger();

        ListOnlyChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
        }
    }

    private static final class RequestCapturingChatModel implements ChatModel {
        private final AtomicReference<ChatRequest> seen = new AtomicReference<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            seen.set(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("captured")).build();
        }
    }

    private static FallbackAwareChatModel fallbackAware(ChatModel primary) {
        return new FallbackAwareChatModel(
                primary,
                () -> null,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3");
    }

    @Test
    void fallbackAwareForwardsAllEntryPointsToLegacyListDelegate() {
        ListOnlyChatModel delegate = new ListOnlyChatModel("legacy ok");
        FallbackAwareChatModel model = fallbackAware(delegate);

        assertEquals("legacy ok", model.chat(List.of(UserMessage.from("hi"))).aiMessage().text());
        assertEquals("legacy ok", model.chat(UserMessage.from("hi")).aiMessage().text());
        assertEquals("legacy ok", model.chat("hi"));
        assertEquals("legacy ok", model.chat(ChatRequest.builder()
                .messages(UserMessage.from("hi")).build()).aiMessage().text());
        assertEquals(4, delegate.calls.get());
    }

    @Test
    void fallbackAwarePreservesFullRequestParameters() {
        RequestCapturingChatModel delegate = new RequestCapturingChatModel();
        FallbackAwareChatModel model = fallbackAware(delegate);

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .temperature(0.7)
                .topP(0.9)
                .maxOutputTokens(123)
                .build();
        ChatResponse response = model.chat(request);

        assertEquals("captured", response.aiMessage().text());
        ChatRequest seen = delegate.seen.get();
        assertNotNull(seen);
        assertEquals(0.7, seen.temperature());
        assertEquals(0.9, seen.topP());
        assertEquals(123, seen.maxOutputTokens());
        assertEquals(1, seen.messages().size());
    }

    @Test
    void expectedFailureModelAnswersEveryEntryPoint() {
        ExpectedFailureChatModel model = new ExpectedFailureChatModel("expected failure detail", "test");
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("probe")).build();

        assertEquals("expected failure detail", model.chat(List.of(UserMessage.from("probe"))).aiMessage().text());
        assertEquals("expected failure detail", model.chat(UserMessage.from("probe")).aiMessage().text());
        assertEquals("expected failure detail", model.chat("probe"));
        assertEquals("expected failure detail", model.chat(request).aiMessage().text());
        assertEquals("expected failure detail", model.doChat(request).aiMessage().text());
    }

    @Test
    void expectedFailureTerminalRouteStillThrowsEverywhere() {
        ExpectedFailureChatModel model = ExpectedFailureChatModel.forRouteFailure(
                "test", new com.example.lms.llm.gateway.LlmGatewayException(
                        "route down", com.example.lms.llm.gateway.LlmFailureClass.MODEL_MISSING, "model_missing"));
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("probe")).build();

        assertThrows(RuntimeException.class, () -> model.chat(List.of(UserMessage.from("probe"))));
        assertThrows(RuntimeException.class, () -> model.chat("probe"));
        assertThrows(RuntimeException.class, () -> model.chat(request));
    }

    @Test
    void requestAttemptRecordingWrapperForwardsEveryEntryPoint() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        RequestCapturingChatModel delegate = new RequestCapturingChatModel();
        ChatModel wrapped = tracker.decorateRequestAttempt(
                delegate,
                "primary",
                tracker.redactedRequestAttemptRoute("route", "model", "endpoint", "ollama_native"),
                null);

        assertEquals("captured", wrapped.chat(List.of(UserMessage.from("hi"))).aiMessage().text());
        assertEquals("captured", wrapped.chat("hi"));
        assertEquals("captured", wrapped.chat(ChatRequest.builder()
                .messages(UserMessage.from("hi")).temperature(0.4).build()).aiMessage().text());
        assertTrue(delegate.seen.get().temperature() != null && delegate.seen.get().temperature() == 0.4);
    }

    @Test
    void requestAttemptRecordingWrapperKeepsLegacyListDelegate() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ListOnlyChatModel delegate = new ListOnlyChatModel("legacy ok");
        ChatModel wrapped = tracker.decorateRequestAttempt(
                delegate,
                "primary",
                tracker.redactedRequestAttemptRoute("route", "model", "endpoint", "ollama_native"),
                null);

        assertEquals("legacy ok", wrapped.chat(ChatRequest.builder()
                .messages(UserMessage.from("hi")).build()).aiMessage().text());
        assertEquals("legacy ok", wrapped.chat("hi"));
        assertEquals(2, delegate.calls.get());
    }

    @Test
    void emptyMessageListIsRejectedBeforeAnyDelegateCall() {
        ListOnlyChatModel delegate = new ListOnlyChatModel("legacy ok");
        FallbackAwareChatModel model = fallbackAware(delegate);

        assertThrows(IllegalArgumentException.class, () -> model.chat(List.of()));
        assertEquals(0, delegate.calls.get());
    }

    @Test
    void declaredDoChatProviderErrorNeverRefiresThroughTheListEntry() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                throw new RuntimeException("Not implemented");
            }
        };
        FallbackAwareChatModel model = fallbackAware(delegate);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> model.chat(List.of(UserMessage.from("hi"))));
        assertEquals("Not implemented", failure.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void cancelledRequestNeverReachesTheDelegate() {
        ListOnlyChatModel delegate = new ListOnlyChatModel("legacy ok");
        FallbackAwareChatModel model = fallbackAware(delegate);

        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> model.chat(List.of(UserMessage.from("hi"))));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, delegate.calls.get());
    }
}
