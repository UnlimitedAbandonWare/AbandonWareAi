
package com.example.lms.planning;


public class StrategyTelemetry {
    public final long recentFailures;
    public final double avgLatencyMs;
    public final boolean webAvailable;

    public StrategyTelemetry(long recentFailures, double avgLatencyMs, boolean webAvailable) {
        this.recentFailures = recentFailures;
        this.avgLatencyMs = avgLatencyMs;
        this.webAvailable = webAvailable;
    }
}