package com.example.lms.learning.gemini;

import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;



/**
 * Constructs prompts for Gemini curation using the general PromptBuilder where appropriate.
 * This class currently provides a simple wrapper; in a full implementation it would
 * assemble the system and user messages according to the structured output schema.
 */
@Component
public class GeminiCurationPromptBuilder {

    public String build(PromptContext context) {
        // For now, delegate to the default toString on PromptContext as a shim.
        // A real implementation would assemble the JSON schema and embed evidence.
        return context == null ? "" : context.toString();
    }
}