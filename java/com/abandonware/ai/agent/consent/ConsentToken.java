package com.abandonware.ai.agent.consent;


/**
 * Opaque token representing a user's consent grant.  The token is tied to a
 * particular session and is used to look up the underlying {@link Grant}
 * from the {@link ConsentService}.  This class intentionally exposes only
 * the session identifier to discourage tools or services from persisting
 * additional user state.
 */
public final class ConsentToken {
    private final String sessionId;

    public ConsentToken(String sessionId) {
        this.sessionId = sessionId;
    }

    /** Returns the session identifier associated with this token. */
    public String sessionId() {
        return sessionId;
    }
}