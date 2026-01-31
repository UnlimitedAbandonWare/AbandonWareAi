package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents a subject-predicate-object triple extracted from an answer,
 * along with the source URL supporting it.
 */
public record Triple(
        String s,
        String p,
        String o,
        String sourceUrl
) {
    public Triple {
        Objects.requireNonNull(s, "subject must not be null");
        Objects.requireNonNull(p, "predicate must not be null");
        Objects.requireNonNull(o, "object must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
    }
}