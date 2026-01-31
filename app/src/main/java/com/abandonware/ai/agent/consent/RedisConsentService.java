package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.Set;

/**
 * Minimal facade to keep build simple. In production you'd back with Redis.
 */
public class RedisConsentService extends BasicConsentService implements ConsentService {
    @Override
    public ConsentToken grant(String identity, Set<ToolScope> scopes, long ttlSeconds) {
        // Could serialize to Redis here; we just delegate to the in-memory parent.
        return super.grant(identity, scopes, ttlSeconds);
    }
}