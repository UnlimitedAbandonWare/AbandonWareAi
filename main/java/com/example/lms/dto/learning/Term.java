package com.example.lms.dto.learning;

import java.util.Objects;

public record Term(String value, String domain) {
    public Term {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
    }
}
