package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.Arrays;
import java.util.List;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.consent.ConsentRequiredException
 * Role: config
 * Dependencies: com.abandonware.ai.agent.tool.ToolScope
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.consent.ConsentRequiredException
role: config
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