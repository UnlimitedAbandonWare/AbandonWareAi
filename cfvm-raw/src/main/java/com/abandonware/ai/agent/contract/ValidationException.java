package com.abandonware.ai.agent.contract;

import java.util.Set;
import java.util.Collections;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.contract.ValidationException
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.contract.ValidationException
role: config
*/
public class ValidationException extends RuntimeException {
    private final Set<String> errors;

    public ValidationException(Set<String> errors) {
        super("Validation failed");
        this.errors = errors == null ? Collections.emptySet() : errors;
    }

    public Set<String> getErrors(){ return errors; }
}