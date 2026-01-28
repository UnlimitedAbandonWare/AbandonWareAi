package com.example.lms.guard.rulebreak;

import java.time.Instant;
import java.util.Objects;

/**
 * RuleBreakContext
 *
 * <p>
 * NOTE:
 * We intentionally avoid Lombok {@code @Builder} here.
 *
 * <p>
 * Why: a previous merge boundary produced runtime failures where the
 * generated inner builder class
 * ({@code RuleBreakContext$RuleBreakContextBuilder})
 * was missing from the packaged JAR, causing
 * {@code ClassNotFoundException}/{@code NoClassDefFoundError}
 * during request processing.
 *
 * <p>
 * This POJO keeps the request path stable and makes failures observable via
 * debug probes rather than crashing the servlet pipeline.
 */
public class RuleBreakContext {

    private boolean active;
    private RuleBreakPolicy policy;
    private String tokenHash;
    private Instant expiresAt;
    private String requestId;
    private String sessionId;

    public RuleBreakContext() {
    }

    public static RuleBreakContext inactive() {
        RuleBreakContext c = new RuleBreakContext();
        c.active = false;
        c.policy = null;
        c.expiresAt = Instant.EPOCH;
        return c;
    }

    public static RuleBreakContext active(RuleBreakPolicy policy, String tokenHash, Instant expiresAt, String requestId,
            String sessionId) {
        RuleBreakContext c = new RuleBreakContext();
        c.active = true;
        c.policy = policy;
        c.tokenHash = tokenHash;
        c.expiresAt = expiresAt;
        c.requestId = requestId;
        c.sessionId = sessionId;
        return c;
    }

    public boolean isValid() {
        return active && policy != null && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    // ---------------------------------------------------------------------
    // Getters / setters (explicit to avoid Lombok/runtime surprises)
    // ---------------------------------------------------------------------

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public RuleBreakPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(RuleBreakPolicy policy) {
        this.policy = policy;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String toString() {
        return "RuleBreakContext{" +
                "active=" + active +
                ", policy=" + (policy == null ? null : policy.name()) +
                ", expiresAt=" + expiresAt +
                ", requestId='" + requestId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RuleBreakContext that = (RuleBreakContext) o;
        return active == that.active
                && Objects.equals(policy, that.policy)
                && Objects.equals(tokenHash, that.tokenHash)
                && Objects.equals(expiresAt, that.expiresAt)
                && Objects.equals(requestId, that.requestId)
                && Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(active, policy, tokenHash, expiresAt, requestId, sessionId);
    }
}
