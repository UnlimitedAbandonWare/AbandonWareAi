package com.example.lms.dto.learning;

import java.util.Objects;

public record MemorySnippet(String text, String subject, double confidence) {
    public MemorySnippet {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }
}
