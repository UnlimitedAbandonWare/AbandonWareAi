package com.example.lms.service.verification;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactStatusClassifierMalformedLabelTest {

    @Test
    void unrecognizedNonblankJudgeLabelFallsBackToHeuristic() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("judgeChatModel", new FixedModel("NOT_A_STATUS"));
        FactStatusClassifier classifier =
                new FactStatusClassifier(beanFactory.getBeanProvider(ChatModel.class));
        TraceStore.clear();

        try {
            FactVerificationStatus status = classifier.classify(
                    "zebra policy",
                    "unrelated supporting context ".repeat(4),
                    "draft",
                    "local-model");

            assertEquals(FactVerificationStatus.CORRECTED, status);
            assertEquals(
                    "judge_malformed_response",
                    TraceStore.get("factStatusClassifier.judge.disabledReason"));
        } finally {
            TraceStore.clear();
        }
    }

    private record FixedModel(String response) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return fixedResponse();
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return fixedResponse();
        }

        private ChatResponse fixedResponse() {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }
}
