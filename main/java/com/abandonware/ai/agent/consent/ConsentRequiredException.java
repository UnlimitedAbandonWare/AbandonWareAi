package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
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
        this(copyScopes(missingScopes), true);
    }

    public List<ToolScope> getMissingScopes() {
        return missingScopes;
    }

    private ConsentRequiredException(List<ToolScope> missingScopes, boolean ignored) {
        super("Missing required scopes: " + missingScopes);
        this.missingScopes = missingScopes;
    }

    private static List<ToolScope> copyScopes(List<ToolScope> missingScopes) {
        return missingScopes == null ? List.of() : List.copyOf(missingScopes);
    }
}
