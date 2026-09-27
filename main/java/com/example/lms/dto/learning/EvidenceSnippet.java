package com.example.lms.dto.learning;

import java.util.Objects;

public record EvidenceSnippet(String url, String title, String text, String credibilityTier) {
    public EvidenceSnippet {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(credibilityTier, "credibilityTier must not be null");
    }
}
