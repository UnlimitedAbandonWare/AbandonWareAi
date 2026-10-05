package com.example.lms.prompt;
import com.example.lms.search.TraceStore;
import com.example.lms.rag.model.QueryDomain;

import java.util.List;



public interface PromptBuilder {
    String build(List<PromptContext> contexts, String question);

    /** Render public preferences separately so the caller places them at user authority. */
    default String buildUserPreferences(PromptContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("prompt context is required");
        if (ctx.responsePreferences().isEmpty()) return "";
        StringBuilder text = new StringBuilder("User response preferences. The current question and its explicit corrections take precedence.\n");
        for (String key : List.of("responseTone", "responseLength", "responseLanguage", "customInstructions")) {
            String value = ctx.responsePreferences().get(key);
            if (value != null && !value.isBlank()) text.append(key).append(": ").append(value).append('\n');
        }
        return text.toString();
    }

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
