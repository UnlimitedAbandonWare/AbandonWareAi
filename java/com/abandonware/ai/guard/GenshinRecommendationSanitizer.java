package com.abandonware.ai.guard;

import org.springframework.stereotype.Component;

@Component
public class GenshinRecommendationSanitizer {
    public String sanitize(String text) {
        if (text == null) return null;
        // Placeholder: tone-down imperative language
        return text.replace("must", "might").replace("should", "could");
    }
}