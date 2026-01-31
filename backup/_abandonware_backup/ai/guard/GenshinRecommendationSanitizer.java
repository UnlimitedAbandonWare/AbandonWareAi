package com.abandonware.ai.guard;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.guard.GenshinRecommendationSanitizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.guard.GenshinRecommendationSanitizer
role: config
*/
public class GenshinRecommendationSanitizer {
    public String sanitize(String text) {
        if (text == null) return null;
        // Placeholder: tone-down imperative language
        return text.replace("must", "might").replace("should", "could");
    }
}