package com.example.lms.dto.learning;

import java.util.Objects;

public record Alias(String entity, String alias) {
    public Alias {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(alias, "alias must not be null");
    }
}
