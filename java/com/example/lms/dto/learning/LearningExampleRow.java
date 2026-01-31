package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents a single row in a learning dataset used for batch processing or tuning.
 */
public record LearningExampleRow(
        String prompt,
        String context,
        String target,
        String[] citations,
        String label
) {
    public LearningExampleRow {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(target, "target must not be null");
        citations = citations == null ? new String[0] : citations.clone();
        Objects.requireNonNull(label, "label must not be null");
    }
}