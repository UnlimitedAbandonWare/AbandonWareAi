package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents an alias for an entity. Useful for normalising different names
 * referring to the same entity.
 */
public record Alias(
        String entity,
        String alias
) {
    public Alias {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(alias, "alias must not be null");
    }
}