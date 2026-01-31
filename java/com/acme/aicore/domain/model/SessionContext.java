package com.acme.aicore.domain.model;

import java.time.Instant;
import java.util.List;



/**
 * Encapsulates immutable session metadata.  A session is identified by a
 * unique sessionId and may optionally be associated with a user identifier.
 * The {@code createdAt} timestamp is recorded when the session context is
 * initialised.  Consumers may choose to enforce TTL policies based on this
 * timestamp.
 */
public record SessionContext(String sessionId, String userId, Instant createdAt) {
    public static SessionContext of(String sessionId) {
        return new SessionContext(sessionId, null, Instant.now());
    }

    /**
     * Create a new SessionContext with an updated history.  For this
     * simplified implementation history is ignored; callers can maintain
     * conversation history externally.
     */
    public SessionContext withHistory(List<Message> history) {
        return this;
    }

    /**
     * Returns the session identifier.  Provided for convenience when using
     * method references.
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Retrieve the last user query.  For compatibility with the prompt
     * builder this method returns the final message from the provided
     * history if it exists.  In this simplified implementation it returns
     * {@code null}.
     */
    public UserQuery lastUserQuery() {
        return null;
    }
}