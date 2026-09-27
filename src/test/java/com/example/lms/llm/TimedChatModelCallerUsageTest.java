package com.example.lms.llm;

import com.example.lms.debug.ai.ChatUsageLedger;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimedChatModelCallerUsageTest {

    @Test
    void incompleteResponseRetainsUsageWithoutSuccessfulCap() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        var attempt = ledger.beginModelInvocation(ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        var metadata = dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(3, 2, 5)).finishReason(dev.langchain4j.model.output.FinishReason.LENGTH).build();
        var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException(
                "output_limit_reached", com.example.lms.llm.gateway.LlmFailureClass.NONE,
                "partial", metadata, "incomplete", "max_output_tokens", null);
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) { throw terminal; }
        };
        assertEquals(terminal, assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                () -> TimedChatModelCaller.chat(model, List.of(UserMessage.from("fixture")),
                        Duration.ofSeconds(2), "chat_draft", "fixture-model", attempt)));
        Map<String, Object> stats = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(stats, "responseReceived"));
        assertEquals(5L, number(stats, "providerTotalTokens"));
        assertNull(stats.get("lastSuccessfulCapState"));
    }

    @Test
    void recordsProviderUsageBeforeReturningTheAiMessage() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        160,
                        2_048,
                        2_048,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));

        AiMessage answer = TimedChatModelCaller.chat(
                new UsageModel("ok", new TokenUsage(12, 8, 20)),
                List.of(UserMessage.from("private prompt")),
                Duration.ofSeconds(1),
                "chat_draft",
                "private-model-id",
                attempt);

        assertEquals("ok", answer.text());
        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "attempts"));
        assertEquals(1L, number(model, "responseReceived"));
        assertEquals(12L, number(model, "providerInputTokens"));
        assertEquals(8L, number(model, "providerOutputTokens"));
        assertEquals(20L, number(model, "providerTotalTokens"));
    }

    @Test
    void timeoutRemainsTheOnlyTerminalOutcomeWhenTheWorkerReturnsLate() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        assertThrows(TimeoutException.class, () -> TimedChatModelCaller.chat(
                new InterruptIgnoringUsageModel(entered, release),
                List.of(UserMessage.from("private prompt")),
                Duration.ofMillis(50),
                "chat_draft",
                "private-model-id",
                attempt));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        release.countDown();
        Thread.sleep(100L);

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "attempts"));
        assertEquals(0L, number(model, "inFlight"));
        assertEquals(0L, number(model, "responseReceived"));
        assertEquals(1L, number(model, "timedOut"));
        assertEquals(0L, number(model, "providerUsageObservedAttemptCount"));
    }

    @Test
    void blankResponseCountsProviderUsageButDoesNotBecomeLastSuccessfulCap() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        160, 64, 64,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));

        assertThrows(RuntimeException.class, () -> TimedChatModelCaller.chat(
                new UsageModel("   ", new TokenUsage(10, 5, 15)),
                List.of(UserMessage.from("private prompt")),
                Duration.ofSeconds(1),
                "chat_draft",
                "private-model-id",
                attempt));

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "responseReceived"));
        assertEquals(1L, number(model, "providerUsageObservedAttemptCount"));
        assertEquals(5L, number(model, "providerOutputTokens"));
        assertNull(model.get("lastSuccessfulCapState"));
        assertNull(model.get("lastSuccessfulConfiguredCap"));
    }

    @Test
    void expectedFailureMarkerCountsUsageButDoesNotBecomeLastSuccessfulCap() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.RESPONSES_FALLBACK,
                ChatUsageLedger.ConfiguredCap.omitted(null, null, ChatUsageLedger.CapSource.RESPONSES_FALLBACK));

        assertThrows(RuntimeException.class, () -> TimedChatModelCaller.chat(
                new UsageModel("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH", new TokenUsage(10, 5, 15)),
                List.of(UserMessage.from("private prompt")),
                Duration.ofSeconds(1),
                "chat_draft",
                "private-model-id",
                attempt));

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(1L, number(model, "responseReceived"));
        assertEquals(5L, number(model, "providerOutputTokens"));
        assertNull(model.get("lastSuccessfulCapState"));
    }

    @Test
    void ordinaryResponsesRouteTermInAnswerRemainsSuccessful() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt attempt = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null));

        AiMessage answer = TimedChatModelCaller.chat(
                new UsageModel("ROUTE_RESPONSES 설정을 설명합니다", new TokenUsage(10, 5, 15)),
                List.of(UserMessage.from("private prompt")),
                Duration.ofSeconds(1),
                "chat_draft",
                "private-model-id",
                attempt);

        assertEquals("ROUTE_RESPONSES 설정을 설명합니다", answer.text());
        assertEquals("provider_default_unknown",
                nested(ledger.snapshot(), "modelInvocations").get("lastSuccessfulCapState"));
    }

    private record UsageModel(String text, TokenUsage usage) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .tokenUsage(usage)
                    .build();
        }
    }

    private record InterruptIgnoringUsageModel(
            CountDownLatch entered,
            CountDownLatch release) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            entered.countDown();
            while (release.getCount() > 0L) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Intentionally ignore interruption to exercise the late-worker path.
                }
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("late"))
                    .tokenUsage(new TokenUsage(1, 2, 3))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}
