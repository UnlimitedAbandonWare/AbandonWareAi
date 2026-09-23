package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;



/**
 * In-memory implementation of the {@link ConsentService}.  Grants are stored
 * by session identifier and automatically expire once their TTL elapses.
 * This implementation is suitable for demonstration and testing purposes
 * and should be replaced by a distributed store (e.g. Redis) in a real
 * deployment.
 */
public class BasicConsentService implements ConsentService {
    private final ConcurrentMap<String, Grant> grants = new ConcurrentHashMap<>();

    @Override
    public Grant issue(String sessionId, Set<ToolScope> scopes, long ttlSeconds) {
        String safeSessionId = sessionId(sessionId);
        if (safeSessionId == null) {
            throw new IllegalArgumentException("session_id_required");
        }
        Instant expiry = Instant.now().plus(Duration.ofSeconds(ttlSeconds));
        Grant grant = new Grant(safeSessionId, scopes == null ? Set.of() : scopes, expiry);
        grants.put(safeSessionId, grant);
        return grant;
    }

    @Override
    public boolean has(ConsentToken token, ToolScope... required) {
        if (required == null || required.length == 0) {
            return true;
        }
        String safeSessionId = token == null ? null : sessionId(token.sessionId());
        if (safeSessionId == null) {
            return false;
        }
        Grant grant = grants.get(safeSessionId);
        if (grant == null || grant.isExpired()) {
            return false;
        }
        Set<ToolScope> needed = EnumSet.noneOf(ToolScope.class);
        needed.addAll(Arrays.asList(required));
        return grant.scopes().containsAll(needed);
    }

    @Override
    public void ensureGranted(ConsentToken token, ToolScope[] required, ConsentContext ctx) throws ConsentRequiredException {
        if (required == null || required.length == 0) {
            return;
        }
        if (!has(token, required)) {
            throw new ConsentRequiredException(Arrays.asList(required));
        }
    }

    private static String sessionId(String value) {
        if (value == null) {
            return null;
        }
        String safe = value.trim();
        return safe.isBlank() ? null : safe;
    }
}
