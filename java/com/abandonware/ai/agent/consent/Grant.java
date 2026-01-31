package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;



/**
 * Represents a grant of one or more scopes to a session.  Grants are
 * time-limited and will expire automatically after the specified
 * expiration time.  The consent service stores grants and looks them up
 * by session identifier when verifying permissions.
 */
public final class Grant {
    private final String sessionId;
    private final Set<ToolScope> scopes;
    private final Instant expiresAt;

    public Grant(String sessionId, Set<ToolScope> scopes, Instant expiresAt) {
        this.sessionId = sessionId;
        this.scopes = Collections.unmodifiableSet(new HashSet<>(scopes));
        this.expiresAt = expiresAt;
    }

    /** Returns the session identifier associated with this grant. */
    public String sessionId() {
        return sessionId;
    }

    /** Returns the set of granted scopes. */
    public Set<ToolScope> scopes() {
        return scopes;
    }

    /** Returns the instant at which this grant expires. */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** Returns true if this grant has expired. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}