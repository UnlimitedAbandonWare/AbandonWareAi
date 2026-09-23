package com.example.lms.dto.learning;

import java.util.Objects;

public record Triple(String s, String p, String o, String sourceUrl) {
    public Triple {
        Objects.requireNonNull(s, "subject must not be null");
        Objects.requireNonNull(p, "predicate must not be null");
        Objects.requireNonNull(o, "object must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
    }
}
