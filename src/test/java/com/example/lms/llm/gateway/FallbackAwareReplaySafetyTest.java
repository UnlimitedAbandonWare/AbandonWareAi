package com.example.lms.llm.gateway;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
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
    void httpFailureWithoutOutputStillHasUncertainExecution() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw httpFailure(503,"runner unavailable"); },
                ignored -> answer("unexpected",fallbackCalls));
        RuntimeException failure=assertThrows(RuntimeException.class,()->model.chat(messages()));
        assertEquals(0,fallbackCalls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
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
    void zeroTokensNeverProveRequestWasNotDispatched() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmGatewayException beforeFirstToken = new LlmGatewayException(
                "stream ended before output",LlmFailureClass.STREAM_ERROR,"stream_error_before_first_token");
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw beforeFirstToken; }, ignored -> answer("unexpected",fallbackCalls));
        RuntimeException failure=assertThrows(RuntimeException.class,()->model.chat(messages()));
        assertEquals(0,fallbackCalls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
    }

    @Test
    void ambiguousTimeoutWithoutProviderReceiptNeverReplays() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        TraceStore.put("providerReceiptObserved", false);
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw new LlmGatewayException("timeout before receipt",
                        LlmFailureClass.TIMEOUT_SOFT, "timeout_before_first_token"); },
                ignored -> answer("unexpected", fallbackCalls));

        LlmGatewayException failure = assertThrows(LlmGatewayException.class, () -> model.chat(messages()));
        assertEquals("provider_execution_uncertain", failure.reasonCode());
        assertEquals(0, fallbackCalls.get());
        assertEquals(false, TraceStore.get("providerReceiptObserved"));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attemptedFallbackFailurePreventsOuterReplayAndKeepsCause(boolean bounded) {
        var primaryCalls = new AtomicInteger();
        var fallbackCalls = new AtomicInteger();
        RuntimeException cause = new IllegalArgumentException("synthetic rejected request");
        ChatModel primary = model(ignored -> {
            primaryCalls.incrementAndGet();
            throw new LlmGatewayException("Local admission blocked",LlmFailureClass.GPU_DEVICE_LOST,"local_endpoint_open");
        });
        ChatModel fallback = model(ignored -> { fallbackCalls.incrementAndGet(); throw cause; });
        var routed = bounded
                ? new FallbackAwareChatModel(primary, (failure, used) ->
                        new FallbackAwareChatModel.ResolvedFallback(fallback, "backup", null),
                        null, null, "primary", null, null, null, 1)
                : new FallbackAwareChatModel(primary, () -> fallback, null, null, "primary", "backup");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> routed.chat(messages()));
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
        assertSame(cause, failure.getCause());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedFallbackCancellationKeepsExactIdentity(boolean bounded) {
        var calls = new AtomicInteger();
        var cancellation = new CancellationException("synthetic cancellation");
        ChatModel primary = model(ignored -> { throw new LlmGatewayException("Local admission blocked",LlmFailureClass.GPU_DEVICE_LOST,"local_endpoint_open"); });
        ChatModel fallback = model(ignored -> { calls.incrementAndGet(); throw cancellation; });
        var routed = bounded
                ? new FallbackAwareChatModel(primary, (failure, used) ->
                        new FallbackAwareChatModel.ResolvedFallback(fallback, "backup", null),
                        null, null, "primary", null, null, null, 1)
                : new FallbackAwareChatModel(primary, () -> fallback, null, null, "primary", "backup");
        assertSame(cancellation, assertThrows(CancellationException.class, () -> routed.chat(messages())));
        assertEquals(1, calls.get());
    }

    @Test
    void absentProviderReceiptDoesNotMakeAmbiguousTimeoutReplayable() {
        TraceStore.put("providerReceiptObserved", false);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw new LlmGatewayException("No receipt yet", LlmFailureClass.TIMEOUT_SOFT,
                        "timeout_before_first_token"); },
                ignored -> answer("unexpected", fallbackCalls));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> model.chat(messages()));
        assertEquals(0, fallbackCalls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"error\":\"GPU is lost\"}",
            "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}",
            "{\"error\":{\"code\":\"vram_oom\",\"message\":\"CUDA out of memory\"}}"})
    void confirmedDeviceRejectionUsesFallbackExactlyOnce(String body) {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw httpFailure(500, body); },
                ignored -> answer("backup", fallbackCalls));
        assertEquals("backup", model.chat(messages()).aiMessage().text());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void structuredQueueOverloadUsesFallbackExactlyOnce() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw httpFailure(503, "{\"error\":\"overloaded\"}"); },
                ignored -> answer("backup", fallbackCalls));
        assertEquals("backup", model.chat(messages()).aiMessage().text());
        assertEquals(1, fallbackCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"overloaded", "{\"error\":\"overloaded\",\"message\":\"partial\"}",
            "{\"error\":\"overloaded\",\"error\":\"overloaded\"}", "{\"error\":\"overloaded\"} trailing"})
    void ambiguousOverloadPayloadStillDoesNotReplay(String body) {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw httpFailure(503, body); },
                ignored -> answer("unexpected", fallbackCalls));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> model.chat(messages()));
        assertEquals(0, fallbackCalls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
    }

    @Test
    void sliceExhaustedPrimaryTimeoutLeavesBudgetForFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                model(ignored -> {
                    TimeBudget slice = TimeBudgetContext.get();
                    assertTrue(slice != null, "primary는 슬라이스된 예산을 관측해야 한다");
                    while (!slice.expired()) {
                        Thread.yield();
                    }
                    throw new LlmGatewayException("slice deadline reached",
                            LlmFailureClass.TIMEOUT_SOFT, "timeout_before_first_token");
                }),
                () -> model(ignored -> answer("backup", fallbackCalls)),
                new LlmGatewayFailureClassifier(), null, "primary", "backup");
        TimeBudgetContext.set(new TimeBudget(1000));
        try {
            assertEquals("backup", model.chat(messages()).aiMessage().text());
        } finally {
            TimeBudgetContext.clear();
        }
        assertEquals(1, fallbackCalls.get());
        Object sliceMs = TraceStore.get("llm.gateway.fallback.timeSliceAllocatedMs");
        assertTrue(sliceMs instanceof Number && ((Number) sliceMs).longValue() > 0L);
        assertEquals(Boolean.TRUE, TraceStore.get("llm.gateway.fallbackAware.primarySliceExhausted"));
    }

    @Test
    void primaryTimeSliceIsScopedAndRestoredForCaller() {
        AtomicInteger primaryCalls = new AtomicInteger();
        TimeBudget parent = new TimeBudget(5000);
        FallbackAwareChatModel model = wrapper(
                ignored -> {
                    primaryCalls.incrementAndGet();
                    TimeBudget slice = TimeBudgetContext.get();
                    assertTrue(slice != null && slice != parent,
                            "primary 호출 구간에는 슬라이스 예산이 보여야 한다");
                    long sliceRemaining = slice.remainingMillis();
                    assertTrue(sliceRemaining > 0L && sliceRemaining <= parent.remainingMillis(),
                            "슬라이스는 부모 잔여 예산 이내여야 한다");
                    return ChatResponse.builder().aiMessage(AiMessage.from("primary")).build();
                },
                ignored -> answer("unexpected", new AtomicInteger()));
        TimeBudgetContext.set(parent);
        try {
            assertEquals("primary", model.chat(messages()).aiMessage().text());
            assertSame(parent, TimeBudgetContext.get(), "호출 후 부모 예산 객체가 복원되어야 한다");
        } finally {
            TimeBudgetContext.clear();
        }
        assertEquals(1, primaryCalls.get());
        Object sliceMs = TraceStore.get("llm.gateway.fallback.timeSliceAllocatedMs");
        assertTrue(sliceMs instanceof Number && ((Number) sliceMs).longValue() > 0L
                && ((Number) sliceMs).longValue() < 5000L);
    }

    @Test
    void earlyPrimaryTimeoutInsideUnusedSliceStillNeverReplays() {
        TraceStore.put("providerReceiptObserved", false);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackAwareChatModel model = wrapper(
                ignored -> { throw new LlmGatewayException("provider timeout before slice",
                        LlmFailureClass.TIMEOUT_SOFT, "timeout_before_first_token"); },
                ignored -> answer("unexpected", fallbackCalls));
        TimeBudgetContext.set(new TimeBudget(30000));
        try {
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(messages()));
            assertEquals("provider_execution_uncertain", failure.reasonCode());
        } finally {
            TimeBudgetContext.clear();
        }
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
