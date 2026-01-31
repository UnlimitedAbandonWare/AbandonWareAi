package com.example.lms.service.rag.pre;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.llm.DynamicChatModelFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;



@Service("longInputDistillationService") // <-- 명시적 빈 이름
public class LongInputDistillationService {

    private final DynamicChatModelFactory chatModelFactory;
    private final String modelName;

    public LongInputDistillationService(
            DynamicChatModelFactory chatModelFactory,
            @Value("${abandonware.input.distillation.model-name:gemini-1.5-flash}") String modelName) {
        this.chatModelFactory = chatModelFactory;
        this.modelName = modelName;
    }

    public Mono<String> distill(String longInput) {
        return Mono.fromCallable(() -> {
            ChatModel model = chatModelFactory.lc(modelName, 0.2, 1.0, null);
            var msgs = java.util.List.of(
                    SystemMessage.from("Summarize the following text into its core questions or key points. Be concise."),
                    UserMessage.from("Text: " + longInput)
            );
            try {
                return model.chat(msgs).aiMessage().text();
            } catch (Exception e) {
                throw new RuntimeException("Distillation failed", e);
            }
        });
    }
}