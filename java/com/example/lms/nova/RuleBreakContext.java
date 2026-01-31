package com.example.lms.nova;

import java.time.Instant;



public class RuleBreakContext {
    public enum Policy { FAST, WIDE, ALL_DOMAIN }

    private final boolean enabled;
    private final Policy policy;
    private final String token;
    private final Instant expiresAt;

    public RuleBreakContext(boolean enabled, Policy policy, String token, Instant expiresAt) {
        this.enabled = enabled;
        this.policy = policy;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public static RuleBreakContext disabled() {
        return new RuleBreakContext(false, Policy.FAST, null, Instant.EPOCH);
    }

    public boolean enabled() { return enabled && (expiresAt == null || expiresAt.isAfter(Instant.now())); }
    public Policy policy() { return policy; }
    public String token() { return token; }
}