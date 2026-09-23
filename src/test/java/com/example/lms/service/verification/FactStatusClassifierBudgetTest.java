package com.example.lms.service.verification;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactStatusClassifierBudgetTest {

    @Test
    void requestBudgetHardBoundsJudgeAndFallsBackToHeuristic() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("judgeChatModel", new SlowModel(400, "PASS"));
        FactStatusClassifier classifier =
                new FactStatusClassifier(beanFactory.getBeanProvider(ChatModel.class));
        TimeBudgetContext.set(new TimeBudget(80));
        TraceStore.clear();
        long startedAt = System.nanoTime();

        try {
            FactVerificationStatus status = classifier.classify(
                    "library policy",
                    "library policy " + "supporting context ".repeat(8),
                    "supported draft",
                    "local-model");
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMs < 300, "request budget must bound classifier judge; elapsedMs=" + elapsedMs);
            assertEquals(FactVerificationStatus.PASS, status);
            assertEquals("fact_status_classifier_judge", TraceStore.get("llm.call.timeout.stage"));
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    private record SlowModel(long delayMs, String response) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return delayedResponse();
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return delayedResponse();
        }

        private ChatResponse delayedResponse() {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }
}
