package com.example.lms.service.answer;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnswerExpanderMessageRoleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realCanonicalBuilderKeepsDraftAndOptionalEvidenceInUserMessage(boolean evidence) {
        TimeBudgetContext.clear();
        TraceStore.clear();
        try {
            String draft = "The copper garden is a local fictional draft.";
            String marker = "expander-evidence-fixture";
            List<List<ChatMessage>> captured = new ArrayList<>();
            ChatModel model = new ChatModel() {
                @Override public ChatResponse chat(List<ChatMessage> messages) {
                    captured.add(List.copyOf(messages));
                    return ChatResponse.builder().aiMessage(AiMessage.from(draft)).build();
                }
            };
            var service = new AnswerExpanderService(new StandardPromptBuilder());
            var profile = new VerbosityProfile("standard", 5, 200, "enduser", "inline", List.of());
            String result = evidence ? service.expandWithLc(draft, profile, model, List.of(marker))
                    : service.expandWithLc(draft, profile, model);
            assertEquals(draft, result);
            assertEquals(1, captured.size());
            assertEquals(1, captured.get(0).size());
            UserMessage message = assertInstanceOf(UserMessage.class, captured.get(0).get(0));
            assertTrue(message.singleText().contains(draft));
            assertEquals(evidence, message.singleText().contains(marker));
            assertTrue(message.singleText().contains("DO NOT add any new facts"));
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }
}
