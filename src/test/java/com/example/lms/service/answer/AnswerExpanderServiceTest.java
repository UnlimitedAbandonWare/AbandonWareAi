package com.example.lms.service.answer;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerExpanderServiceTest {

    @Test
    void rejectsExpansionThatIntroducesDifferentNumbers() {
        AnswerExpanderService service = new AnswerExpanderService(new RecordingPromptBuilder());
        VerbosityProfile profile = new VerbosityProfile(
                "standard", 5, 200, "enduser", "inline", List.of());

        String expanded = service.expandWithLc(
                "12와 8의 합은 20입니다.",
                profile,
                new StaticModel("12와 8의 합은 18입니다. 충분한 설명을 덧붙였습니다."),
                List.of());

        assertNull(expanded);
    }

    @Test
    void removesLeakedDraftScaffoldFromExpansion() {
        AnswerExpanderService service = new AnswerExpanderService(new RecordingPromptBuilder());
        VerbosityProfile profile = new VerbosityProfile(
                "standard", 5, 200, "enduser", "inline", List.of());

        String expanded = service.expandWithLc(
                "기존 설명입니다.",
                profile,
                new StaticModel("## DRAFT\n기존 설명입니다.\n## RESTRUCTURED\n정리된 기존 설명입니다."),
                List.of());

        assertEquals("정리된 기존 설명입니다.", expanded);
    }

    @Test
    void allowsEquivalentNumericFormattingAndStructuralListOrdinals() {
        AnswerExpanderService service = new AnswerExpanderService(new RecordingPromptBuilder());
        VerbosityProfile profile = new VerbosityProfile(
                "standard", 5, 200, "enduser", "inline", List.of());

        String expanded = service.expandWithLc(
                "처리량은 1,000건입니다. 다음으로 안정성을 확인합니다.",
                profile,
                new StaticModel("1. 처리량은 1000건입니다.\n2. 다음으로 안정성을 확인합니다."),
                List.of());

        assertNotNull(expanded);
        assertTrue(expanded.contains("1000건"));
    }

    @Test
    void evidenceSnippetsAreRedactedBeforePromptBuilderBoundary() {
        String rawKey = "sk-" + "abcdefghijklmnopqrstuvwxyz" + "123456";
        RecordingPromptBuilder promptBuilder = new RecordingPromptBuilder();
        AnswerExpanderService service = new AnswerExpanderService(promptBuilder);
        VerbosityProfile profile = new VerbosityProfile(
                "standard", 5, 200, "dev", "inline", List.of("요약"));

        String expanded = service.expandWithLc(
                "기존 초안입니다.",
                profile,
                new StaticModel("확장된 답변입니다. 충분한 길이입니다."),
                List.of("provider evidence " + rawKey));

        assertNotNull(expanded);
        assertFalse(promptBuilder.lastUserQuery.contains(rawKey));
    }

    @Test
    void requestBudgetHardBoundsExpansionCall() {
        RecordingPromptBuilder promptBuilder = new RecordingPromptBuilder();
        AnswerExpanderService service = new AnswerExpanderService(promptBuilder);
        VerbosityProfile profile = new VerbosityProfile(
                "standard", 250, 1000, "enduser", "inline", List.of());
        TimeBudgetContext.set(new TimeBudget(80));
        TraceStore.clear();
        long startedAt = System.nanoTime();

        try {
            String expanded = service.expandWithLc(
                    "기존 초안입니다.",
                    profile,
                    new SlowModel(400),
                    List.of());
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(System.nanoTime() - startedAt);

            assertNull(expanded);
            assertTrue(elapsedMs < 300, "request budget must bound expansion; elapsedMs=" + elapsedMs);
            assertTrue("answer_expansion".equals(TraceStore.get("llm.call.timeout.stage")));
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    private static final class RecordingPromptBuilder implements PromptBuilder {
        private String lastUserQuery = "";

        @Override
        public String build(List<PromptContext> contexts, String question) {
            PromptContext ctx = contexts.get(0);
            lastUserQuery = ctx.userQuery();
            return lastUserQuery;
        }
    }

    private record StaticModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private record SlowModel(long delayMs) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("확장된 답변입니다. 충분한 길이의 안전한 테스트 답변입니다."))
                    .build();
        }
    }
}
