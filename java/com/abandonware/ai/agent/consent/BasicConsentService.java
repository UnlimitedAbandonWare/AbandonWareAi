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
 * In‑memory implementation of the {@link ConsentService}.  Grants are stored
 * by session identifier and automatically expire once their TTL elapses.
 * This implementation is suitable for demonstration and testing purposes
 * and should be replaced by a distributed store (e.g. Redis) in a real
 * deployment.
 */
public class BasicConsentService implements ConsentService {
    private final ConcurrentMap<String, Grant> grants = new ConcurrentHashMap<>();

    @Override
    public Grant issue(String sessionId, Set<ToolScope> scopes, long ttlSeconds) {
        Instant expiry = Instant.now().plus(Duration.ofSeconds(ttlSeconds));
        Grant grant = new Grant(sessionId, scopes, expiry);
        grants.put(sessionId, grant);
        return grant;
    }

    @Override
    public boolean has(ConsentToken token, ToolScope... required) {
        if (token == null || required == null || required.length == 0) {
            return true;
        }
        Grant grant = grants.get(token.sessionId());
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
}