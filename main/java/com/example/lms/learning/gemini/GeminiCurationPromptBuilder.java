package com.example.lms.learning.gemini;

import com.example.lms.prompt.PromptContext;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;



/**
 * Constructs prompts for Gemini curation using the general PromptBuilder where appropriate.
 * This class currently provides a simple wrapper; in a full implementation it would
 * assemble the system and user messages according to the structured output schema.
 */
@Component
public class GeminiCurationPromptBuilder {

    public String build(PromptContext context) {
        if (context == null) {
            return "";
        }
        StringBuilder out = new StringBuilder("### GEMINI CURATION CONTEXT\n");
        append(out, "userQuery", context.userQuery(), 1_200);
        append(out, "subject", context.subject(), 240);
        append(out, "domain", context.domain(), 120);
        append(out, "memory", context.memory(), 1_200);
        append(out, "history", context.history(), 1_200);
        return out.toString().trim();
    }

    private static void append(StringBuilder out, String label, String value, int maxLen) {
        String safe = SafeRedactor.safeMessage(value, maxLen);
        if (safe != null && !safe.isBlank()) {
            out.append(label).append(": ").append(safe).append('\n');
        }
    }
}
