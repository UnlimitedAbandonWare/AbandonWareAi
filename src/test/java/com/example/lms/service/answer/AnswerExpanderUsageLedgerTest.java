package com.example.lms.service.answer;

import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnswerExpanderUsageLedgerTest {

    private static final VerbosityProfile PROFILE = new VerbosityProfile(
            "standard", 20, 240, "enduser", "inline", List.of());
    private static final String DRAFT = "기존 초안은 안전한 계측 동작을 설명합니다.";

    @Test
    void fourArgumentOverloadRecordsEveryPostModelTerminalOutcomeAndProviderCost() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        AnswerExpanderService service = service(ledger, new PassthroughPromptBuilder());

        service.expandWithLc(DRAFT, PROFILE, usageModel(
                "기존 초안은 안전한 계측 동작을 더 자세하고 명확하게 설명합니다.", 11), List.of());
        assertNull(service.expandWithLc(DRAFT, PROFILE, usageModel("[NO_EVIDENCE]", 7), List.of()));
        assertNull(service.expandWithLc("값은 12입니다.", PROFILE,
                usageModel("값은 99이며 자세한 설명입니다.", 5), List.of()));
        assertNull(service.expandWithLc(DRAFT, PROFILE, usageModel("짧음", 3), List.of()));
        assertNull(service.expandWithLc(DRAFT, PROFILE, usageModel("   ", 2), List.of()));
        assertNull(service.expandWithLc(DRAFT, PROFILE, failingModel(
                new IllegalStateException("private provider failure")), List.of()));

        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertEquals(6L, number(expansion, "invocations"));
        assertEquals(6L, number(expansion, "modelInvocations"));
        assertEquals(1L, number(expansion, "accepted"));
        assertEquals(1L, number(expansion, "rejectedNumeric"));
        assertEquals(1L, number(expansion, "rejectedNoEvidence"));
        assertEquals(1L, number(expansion, "rejectedTooShort"));
        assertEquals(1L, number(expansion, "rejectedEmpty"));
        assertEquals(1L, number(expansion, "failedAfterModel"));
        assertEquals(5L, number(expansion, "providerUsageObservedAttemptCount"));
        assertEquals(28L, number(expansion, "providerOutputTokens"));
        assertEquals(17L, number(expansion, "rejectedProviderOutputTokens"));
        assertEquals(6L, number(expansion, "unknownBudgetAttemptCount"));
    }

    @Test
    void threeArgumentOverloadOwnsExactlyOneInvocationThroughDelegation() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        AnswerExpanderService service = service(ledger, new PassthroughPromptBuilder());

        service.expandWithLc(DRAFT, PROFILE, usageModel(
                "기존 초안은 안전한 계측 동작을 더 자세하고 명확하게 설명합니다.", 4));

        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertEquals(1L, number(expansion, "invocations"));
        assertEquals(1L, number(expansion, "modelInvocations"));
        assertEquals(1L, number(expansion, "accepted"));
    }

    @Test
    void cancellationIsCountedOnceWithoutLeakingTheFailurePayload() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        AnswerExpanderService service = service(ledger, new PassthroughPromptBuilder());

        assertNull(service.expandWithLc(DRAFT, PROFILE, failingModel(
                new CancellationException("private cancellation detail")), List.of()));

        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertEquals(1L, number(expansion, "invocations"));
        assertEquals(1L, number(expansion, "modelInvocations"));
        assertEquals(1L, number(expansion, "cancelled"));
        assertEquals(0L, number(expansion, "failedAfterModel"));
        assertEquals(false, ledger.snapshot().toString().contains("private cancellation detail"));
    }

    @Test
    void noBudgetExpectedFailureMarkerDoesNotBecomeSuccessfulExpansionCap() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        AnswerExpanderService service = service(ledger, new PassthroughPromptBuilder());

        assertNull(service.expandWithLc(
                DRAFT,
                PROFILE,
                usageModel("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH", 4),
                List.of()));

        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        Map<String, Object> expansion = nested(ledger.snapshot(), "answerExpansion");
        assertNull(model.get("lastSuccessfulCapState"));
        assertEquals(1L, number(model, "responseReceived"));
        assertEquals(1L, number(expansion, "failedAfterModel"));
    }

    private static AnswerExpanderService service(ChatUsageLedger ledger, PromptBuilder promptBuilder) {
        AnswerExpanderService service = new AnswerExpanderService(promptBuilder);
        ReflectionTestUtils.setField(service, "chatUsageLedger", ledger);
        return service;
    }

    private static ChatModel usageModel(String text, int outputTokens) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(text))
                        .tokenUsage(new TokenUsage(6, outputTokens, 6 + outputTokens))
                        .build();
            }
        };
    }

    private static ChatModel failingModel(RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw failure;
            }
        };
    }

    private static final class PassthroughPromptBuilder implements PromptBuilder {
        @Override
        public String build(List<PromptContext> contexts, String question) {
            return contexts.get(0).userQuery();
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
