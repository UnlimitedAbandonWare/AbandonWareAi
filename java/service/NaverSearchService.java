package service;

import trace.TraceContext;
/**
 * Minimal placeholder for hedged requests configuration.
 */
public class NaverSearchService {
    private boolean hedgeEnabled = true;
    private int hedgeDelayMs = 120;
    private int timeoutMs = 1800;

    public void configure(boolean hedgeEnabled, int hedgeDelayMs, int timeoutMs) {
        this.hedgeEnabled = hedgeEnabled;
        this.hedgeDelayMs = hedgeDelayMs;
        this.timeoutMs = timeoutMs;
    }
}