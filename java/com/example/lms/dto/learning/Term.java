package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents a protected term that should not be altered or removed during
 * subsequent processing. Each term belongs to a particular domain.
 */
public record Term(
        String value,
        String domain
) {
    public Term {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
    }
}