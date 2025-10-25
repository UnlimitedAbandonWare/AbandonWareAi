package com.example.lms.prompt;

import java.util.List;
import java.util.Collections;



public interface PromptBuilder {
    String build(List<PromptContext> contexts, String question);

    // Backward-compatible single-context convenience
    default String build(PromptContext ctx) {
        return build(ctx == null ? Collections.emptyList() : List.of(ctx),
                     ctx == null ? "" : (ctx.userQuery() == null ? "" : ctx.userQuery()));
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