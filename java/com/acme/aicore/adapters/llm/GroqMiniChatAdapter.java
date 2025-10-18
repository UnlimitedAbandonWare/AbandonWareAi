package com.acme.aicore.adapters.llm;

import com.acme.aicore.domain.ports.LightweightNlpPort;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Adapter that routes lightweight NLP tasks to a Groq hosted model.  Uses
 * LangChain4j’s {@link OpenAiChatModel} with a custom base URL pointing at
 * the Groq API.  The model identifier and API key are injected from
 * configuration.  When invoked the adapter wraps the input into a single
 * {@link UserMessage} and extracts the returned text.  Any exceptions
 * during model invocation are logged and surfaced as errors.
 */
@Component
public class GroqMiniChatAdapter implements LightweightNlpPort {
    private static final Logger log = LoggerFactory.getLogger(GroqMiniChatAdapter.class);
    private final ChatModel mini;

    public GroqMiniChatAdapter(
            @Value("${groq.api.key:}") String key,
            @Value("${groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${groq.mini-model:llama-3.1-8b-instant}") String model
    ) {
        this.mini = OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(baseUrl)
                .modelName(model)
                .build();
    }

    @Override
    public Mono<String> rewrite(String input) {
        return Mono.fromCallable(() -> {
            try {
                return mini.chat(List.of(UserMessage.from(input))).aiMessage().text();
            } catch (Exception e) {
                log.warn("Groq mini model invocation failed", e);
                throw e;
            }
        });
    }
}