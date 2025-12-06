package com.abandonware.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rerank.guard")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.config.RerankGuardProperties
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.config.RerankGuardProperties
role: config
*/
public class RerankGuardProperties {
    private boolean enabled = true;
    private int maxConcurrency = 2;
    private int acquireTimeoutMs = 80;
    private int minTokens = 12;
    private int minCandidates = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getAcquireTimeoutMs() { return acquireTimeoutMs; }
    public void setAcquireTimeoutMs(int acquireTimeoutMs) { this.acquireTimeoutMs = acquireTimeoutMs; }
    public int getMinTokens() { return minTokens; }
    public void setMinTokens(int minTokens) { this.minTokens = minTokens; }
    public int getMinCandidates() { return minCandidates; }
    public void setMinCandidates(int minCandidates) { this.minCandidates = minCandidates; }
}