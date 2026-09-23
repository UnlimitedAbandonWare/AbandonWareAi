package com.example.lms.prompt;
import com.example.lms.search.TraceStore;
import com.example.lms.rag.model.QueryDomain;

import java.util.List;



public interface PromptBuilder {
    String build(List<PromptContext> contexts, String question);

    // Backward-compatible single-context convenience
    default String build(PromptContext ctx) {
        if (ctx == null) {
            TraceStore.put("promptBuilder.nullCtx", true);
            throw new IllegalArgumentException("prompt context is required");
        }
        return build(List.of(ctx), ctx.userQuery() == null ? "" : ctx.userQuery());
    }

    // Minimal instruction block for legacy callers
    default String buildInstructions(PromptContext ctx) {
        return """
                ### INSTRUCTIONS
                - Ground claims in sources when available.
                - Prefer official/academic domains.
                - Be concise and cite inline.
                """;
    }
}
