package com.example.lms.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.db-context")
public class AgentDbContextProperties {

    /**
     * Enables the read-only agent DB context endpoint. Disabled by default so
     * production profiles do not expose the surface unless explicitly opted in.
     */
    private boolean enabled = false;

    private int maxLedgerFailures = 20;

    private int maxMemoryTopN = 10;

    private long snapshotCacheTtlMillis = 30_000L;

    private int queryTimeoutSeconds = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxLedgerFailures() {
        return maxLedgerFailures;
    }

    public void setMaxLedgerFailures(int maxLedgerFailures) {
        this.maxLedgerFailures = maxLedgerFailures;
    }

    public int getMaxMemoryTopN() {
        return maxMemoryTopN;
    }

    public void setMaxMemoryTopN(int maxMemoryTopN) {
        this.maxMemoryTopN = maxMemoryTopN;
    }

    public long getSnapshotCacheTtlMillis() {
        return snapshotCacheTtlMillis;
    }

    public void setSnapshotCacheTtlMillis(long snapshotCacheTtlMillis) {
        this.snapshotCacheTtlMillis = snapshotCacheTtlMillis;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }
}
