package com.abandonware.ai.agent.contract;

import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;

public class ValidationException extends RuntimeException {
    private final Set<String> errors;

    public ValidationException(Set<String> errors) {
        super("Validation failed");
        this.errors = errors == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(errors));
    }

    public Set<String> getErrors(){ return errors; }
}
