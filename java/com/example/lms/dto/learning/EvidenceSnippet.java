package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Evidence snippet used as input for Gemini curation. Each snippet contains
 * the source URL, title, raw text and a credibility tier indicator.
 */
public record EvidenceSnippet(
        String url,
        String title,
        String text,
        String credibilityTier
) {
    public EvidenceSnippet {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(credibilityTier, "credibilityTier must not be null");
    }
}