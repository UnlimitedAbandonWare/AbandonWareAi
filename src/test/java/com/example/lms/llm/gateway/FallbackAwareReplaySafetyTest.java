package com.example.lms.llm.gateway;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackAwareReplaySafetyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void spendLimitErrorCodesDoNotReplaySameBillingScopeFallback() {
        for (String code : LlmGatewayFailureClassifier.QUOTA_ERROR_CODES) {
            String body = "{\"error\":{\"code\":\"" + code + "\"}}";
            AtomicInteger fallbackCalls = new AtomicInteger();
            FallbackAwareChatModel model = wrapper(
                    ignored -> { throw httpFailure(400, body); },
                    ignored -> answer("unexpected", fallbackCalls));
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> model.chat(messages()));
            assertEquals(0, fallbackCalls.get(), code);
            assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(thrown), code);
        }
    }

    @Test
    void clientBadRequestNeverReplaysOnFallbackBackend() {
        WebClientResponseException badRequest = httpFailure(400, "invalid request payload");
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw badRequest; },
                ignored -> answer("unexpected", fallbackCalls));

        WebClientResponseException thrown = assertThrows(
                WebClientResponseException.class,
                () -> model.chat(messages()));

        assertSame(badRequest, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void toolSideEffectStartedReasonNeverReplaysOnFallbackBackend() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException sideEffect = new LlmGatewayException(
                "tool execution already started",
                LlmFailureClass.PROVIDER_ERROR,
                "tool_side_effect_started");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw sideEffect; },
                ignored -> answer("unexpected", fallbackCalls));

        LlmGatewayException thrown = assertThrows(
                LlmGatewayException.class,
                () -> model.chat(messages()));

        assertSame(sideEffect, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void failureAfterPartialOutputNeverAppendsAnotherBackend() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException partial = new LlmGatewayException(
                "stream epoch invalidated",
                LlmFailureClass.STREAM_ERROR,
                "stream_error_after_partial");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw partial; },
                ignored -> answer("unexpected", fallbackCalls));

        LlmGatewayException thrown = assertThrows(
                LlmGatewayException.class,
                () -> model.chat(messages()));

        assertSame(partial, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void contextCapabilityMismatchNeverSilentlyReplays() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException mismatch = new LlmGatewayException(
                "context exceeds configured route",
                LlmFailureClass.CONTEXT_TOO_SMALL,
                "context_limit_exceeded");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw mismatch; },
                ignored -> answer("unexpected", fallbackCalls));

        LlmGatewayException thrown = assertThrows(
                LlmGatewayException.class,
                () -> model.chat(messages()));

        assertSame(mismatch, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void transportFailureBeforeAnyOutputUsesFallbackExactlyOnce() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw httpFailure(503, "runner unavailable"); },
                ignored -> answer("BACKUP", fallbackCalls));

        ChatResponse response = model.chat(messages());

        assertEquals("BACKUP", response.aiMessage().text());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void typedCancellationStillNeverBuildsFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        CancellationException cancelled = new CancellationException("stop");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw cancelled; },
                ignored -> answer("unexpected", fallbackCalls));

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> model.chat(messages()));

        assertSame(cancelled, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void streamFailureBeforeFirstTokenUsesFallbackExactlyOnce() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException beforeFirstToken = new LlmGatewayException(
                "stream ended before output",
                LlmFailureClass.STREAM_ERROR,
                "stream_error_before_first_token");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw beforeFirstToken; },
                ignored -> answer("BACKUP", fallbackCalls));

        ChatResponse response = model.chat(messages());

        assertEquals("BACKUP", response.aiMessage().text());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void timeoutAfterPartialOutputNeverReplaysOnFallbackBackend() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException partialTimeout = new LlmGatewayException(
                "deadline after output",
                LlmFailureClass.TIMEOUT_SOFT,
                "timeout_after_partial");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw partialTimeout; },
                ignored -> answer("unexpected", fallbackCalls));

        LlmGatewayException thrown = assertThrows(
                LlmGatewayException.class,
                () -> model.chat(messages()));

        assertSame(partialTimeout, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void capabilityMismatchNeverReplaysOnFallbackBackend() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException mismatch = new LlmGatewayException(
                "vision capability unavailable",
                LlmFailureClass.PROVIDER_ERROR,
                "capability_mismatch");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw mismatch; },
                ignored -> answer("unexpected", fallbackCalls));

        LlmGatewayException thrown = assertThrows(
                LlmGatewayException.class,
                () -> model.chat(messages()));

        assertSame(mismatch, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"model not found", "connection refused", "out of memory"})
    void wrappedCancellationNeverConstructsOrInvokesFallback(String outerMessage) {
        assertWrappedCancellationIsNotReplayed(outerMessage, new CancellationException("caller cancelled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"model not found", "connection refused", "out of memory"})
    void wrappedInterruptionNeverConstructsOrInvokesFallback(String outerMessage) {
        assertWrappedCancellationIsNotReplayed(outerMessage, new InterruptedException("caller interrupted"));
    }

    private static void assertWrappedCancellationIsNotReplayed(String outerMessage, Throwable cause) {
        AtomicInteger fallbackBuilds = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        RuntimeException wrapped = new RuntimeException(outerMessage, cause);
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                model(ignored -> { throw wrapped; }),
                () -> {
                    fallbackBuilds.incrementAndGet();
                    return model(ignored -> answer("unexpected", fallbackCalls));
                }, new LlmGatewayFailureClassifier(), null, "primary", "backup");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> model.chat(messages()));

        assertSame(wrapped, thrown);
        assertEquals(0, fallbackBuilds.get());
        assertEquals(0, fallbackCalls.get());
    }

    private static FallbackAwareChatModel wrapper(
            Function<List<ChatMessage>, ChatResponse> primary,
            Function<List<ChatMessage>, ChatResponse> fallback) {
        return new FallbackAwareChatModel(
                model(primary),
                () -> model(fallback),
                new LlmGatewayFailureClassifier(),
                null,
                "primary",
                "backup");
    }

    private static ChatModel model(Function<List<ChatMessage>, ChatResponse> invocation) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return invocation.apply(messages);
            }
        };
    }

    private static ChatResponse answer(String text, AtomicInteger calls) {
        calls.incrementAndGet();
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static List<ChatMessage> messages() {
        return List.of(UserMessage.from("bounded request"));
    }

    private static WebClientResponseException httpFailure(int status, String body) {
        return WebClientResponseException.create(
                status,
                "stub",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
