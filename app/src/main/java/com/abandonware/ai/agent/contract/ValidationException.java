package com.abandonware.ai.agent.contract;

import com.networknt.schema.ValidationMessage;
import java.util.Set;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.contract.ValidationException
 * Role: config
 * Dependencies: com.networknt.schema.ValidationMessage
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.contract.ValidationException
role: config
*/
public class ValidationException extends RuntimeException {
    private final Set<ValidationMessage> errors;
    public ValidationException(Set<ValidationMessage> errors) {
        super("Schema validation failed: " + errors);
        this.errors = errors;
    }
    public Set<ValidationMessage> getErrors() { return errors; }
}