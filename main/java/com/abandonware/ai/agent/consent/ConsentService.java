package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.Set;



/**
 * Manages creation and validation of consent grants.  A consent grant
 * expresses a user's willingness to allow certain scopes to be used for a
 * limited time.  Tools consult the consent service via the
 * {@link com.abandonware.ai.agent.tool.aspect.ToolScopeAspect} before
 * execution.
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