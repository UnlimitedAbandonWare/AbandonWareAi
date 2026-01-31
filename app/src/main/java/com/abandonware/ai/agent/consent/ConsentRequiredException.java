package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.List;

public class ConsentRequiredException extends RuntimeException {
    private final List<ToolScope> missingScopes;

    public ConsentRequiredException(List<ToolScope> missingScopes) {
        super("Missing required scopes: " + missingScopes);
        this.missingScopes = List.copyOf(missingScopes);
    }

    public List<ToolScope> missingScopes() { return missingScopes; }
}