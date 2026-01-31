package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents an induced rule with left hand side and right hand side
 * along with a confidence score. The type field expresses the rule category.
 */
public record Rule(
        String type,
        String lhs,
        String rhs,
        double confidence
) {
    public Rule {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(lhs, "lhs must not be null");
        Objects.requireNonNull(rhs, "rhs must not be null");
    }
}