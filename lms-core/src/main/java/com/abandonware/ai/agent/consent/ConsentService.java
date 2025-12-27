package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.Set;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.consent.ConsentService
 * Role: config
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.agent.tool.ToolScope
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.consent.ConsentService
role: config
flags: [sse]
*/
public interface ConsentService {

    /**
     * Creates a new grant for the given session and scopes with the
     * specified TTL (in seconds).  Previous grants for the session are
     * replaced.  The returned {@link ConsentToken} can be attached to
     * subsequent tool requests.
     */
    Grant issue(String sessionId, Set<ToolScope> scopes, long ttlSeconds);

    /**
     * Returns true if the provided token covers all of the required
     * scopes.  Expired grants are treated as absent.
     */
    boolean has(ConsentToken token, ToolScope... required);

    /**
     * Ensures that the provided token covers all required scopes.  If any
     * scopes are missing this method throws a {@link ConsentRequiredException}
     * describing which scopes are needed.  The supplied context may be used
     * to generate a richer consent card, although it is ignored in this
     * implementation.
     */
    void ensureGranted(ConsentToken token, ToolScope[] required, ConsentContext ctx) throws ConsentRequiredException;
}