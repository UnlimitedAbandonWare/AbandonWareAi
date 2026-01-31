package com.acme.aicore.app;

import com.acme.aicore.domain.model.SessionContext;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;




/**
 * Provides simple session lifecycle management.  A new {@link SessionContext}
 * can be initialised for a given session ID and user identifier.  The
 * resulting context carries a timestamp which can be used by clients to
 * enforce an expiry or TTL.  The default TTL is 45 minutes, reflecting the
 * typical duration of a user conversation.
 */
@Component
public class SessionManager {
    private final Duration ttl = Duration.ofMinutes(45);

    public SessionContext init(String sessionId, String userId) {
        return new SessionContext(sessionId, userId, Instant.now());
    }

    public Duration getTtl() {
        return ttl;
    }
}