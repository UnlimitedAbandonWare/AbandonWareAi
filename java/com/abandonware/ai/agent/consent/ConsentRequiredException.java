package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.Arrays;
import java.util.List;



/**
 * Exception thrown when a tool invocation is attempted without having
 * sufficient scopes granted.  Upstream controllers can catch this
 * exception and return a consent card to the user based on the missing
 * scopes and their associated TTL values.  The exception carries the list
 * of required scopes that were not satisfied.
 */
public class ConsentRequiredException extends RuntimeException {
    private final List<ToolScope> missingScopes;

    public ConsentRequiredException(List<ToolScope> missingScopes) {
        super("Missing required scopes: " + Arrays.toString(missingScopes.toArray()));
        this.missingScopes = missingScopes;
    }

    public List<ToolScope> getMissingScopes() {
        return missingScopes;
    }
}