package com.example.lms.service.rag.pre;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.llm.DynamicChatModelFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * A lightweight service responsible for distilling large user inputs before they
 * enter the expensive retrieval‑augmented generation (RAG) pipeline.  When
 * enabled, this service uses a low‑cost language model to extract the core
 * questions or key points from a long input.  The resulting summary can then
 * be presented to the user for confirmation prior to engaging the full RAG
 * pipeline, reducing token usage and cost.
 */
@Service
public class InputDistillationService {

    private final DynamicChatModelFactory chatModelFactory;

    /**
     * Name of the inexpensive model used for input distillation.  Configurable via
     * {@code abandonware.input.distillation.model-name} in application properties.
     */
    private final String modelName;

    public InputDistillationService(DynamicChatModelFactory chatModelFactory,
                                    @Value("${abandonware.input.distillation.model-name:gemini-1.5-flash}") String modelName) {
        this.chatModelFactory = chatModelFactory;
        this.modelName = modelName;
    }

    /**
     * Summarize the given long input using a concise prompt.  The prompt asks
     * the language model to extract the core questions or key points and to
     * remain concise.  A low temperature and high top‑p are used to favor
     * deterministic, cost‑effective behaviour.
     *
     * @param longInput the raw user input to distill
     * @return a {@link Mono} emitting the distilled summary
     */
    public Mono<String> distill(String longInput) {
        return Mono.fromCallable(() -> {
            // Build a chat model instance with conservative sampling parameters for summarisation.
            // We deliberately bypass any higher‑tier model routing by using the configured model name.
            ChatModel model = chatModelFactory.lc(modelName, /*temperature*/ 0.2, /*topP*/ 1.0, /*maxTokens*/ null);
            var msgs = java.util.List.of(
                    SystemMessage.from(
                            "Summarize the following text into its core questions or key points. Be concise."),
                    UserMessage.from("Text: " + longInput)
            );
            try {
                return model.chat(msgs).aiMessage().text();
            } catch (Exception e) {
                // Propagate exceptions so that callers can handle failure appropriately
                throw new RuntimeException("Distillation failed", e);
            }
        });
    }
}