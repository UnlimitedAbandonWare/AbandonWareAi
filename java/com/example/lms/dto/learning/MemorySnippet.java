package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents a snippet of text to be inserted into the translation memory
 * or vector store. The subject field may provide context about the snippet.
 */
public record MemorySnippet(
        String text,
        String subject,
        double confidence
) {
    public MemorySnippet {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }
}