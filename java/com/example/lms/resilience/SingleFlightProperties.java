package com.example.lms.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;



@ConfigurationProperties(prefix = "cache.singleflight")
public class SingleFlightProperties {
    private boolean enabled = false;
    private long timeoutMs = 15000;
    private String keyStrategy = "METHOD_AND_ARGS";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getKeyStrategy() { return keyStrategy; }
    public void setKeyStrategy(String keyStrategy) { this.keyStrategy = keyStrategy; }
}