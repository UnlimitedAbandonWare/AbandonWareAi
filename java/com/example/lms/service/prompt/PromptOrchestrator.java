package com.example.lms.service.prompt;

import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;



/**
 * Encapsulates orchestration of prompt construction from disparate context
 * sources.  The {@link PromptBuilder} encapsulates the low-level
 * formatting of sections such as web evidence, vector RAG, memory and
 * history.  This orchestrator is responsible for assembling the
 * {@link PromptContext} and delegating to the builder.  Extracting this
 * responsibility out of {@code ChatService} improves testability and
 * separation of concerns.
 */
@Component
public class PromptOrchestrator {

    private final PromptBuilder promptBuilder;

    public PromptOrchestrator(PromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder;
    }

    /**
     * Build a prompt string using the provided {@link PromptContext}.  The
     * underlying {@link PromptBuilder} is null-safe and will produce an
     * appropriate section ordering.  Callers should construct a context
     * object with the desired web, RAG and memory fields populated.
     *
     * @param ctx the context carrying input sections (may be {@code null})
     * @return a formatted prompt string ready for LLM consumption
     */
    public String buildPrompt(PromptContext ctx) {
        return promptBuilder.build(ctx);
    }
}