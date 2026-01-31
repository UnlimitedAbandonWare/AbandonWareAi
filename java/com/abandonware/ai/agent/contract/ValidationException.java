package com.abandonware.ai.agent.contract;

import java.util.Set;
import java.util.Collections;

public class ValidationException extends RuntimeException {
    private final Set<String> errors;

    public ValidationException(Set<String> errors) {
        super("Validation failed");
        this.errors = errors == null ? Collections.emptySet() : errors;
    }

    public Set<String> getErrors(){ return errors; }
}