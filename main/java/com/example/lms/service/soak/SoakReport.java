package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakReport {
    private final int k;
    private final String topic;
    private final int runs;
    private final Metrics metrics;
    private final List<SoakError> errors;
    private final Instant startedAt;
    private final Instant finishedAt;

    public static class Metrics {
        public double nDCG10;
        public long p50LatencyMs;
        public long p95LatencyMs;
        public double avgLatencyMs;
        public double timeoutRate;
    }

    public static class SoakError {
        public String query;
        public String message;
        public long latencyMs;

        public SoakError(String query, String message, long latencyMs) {
            this.query = query; this.message = message; this.latencyMs = latencyMs;
        }
    }

    public SoakReport(int k, String topic, int runs, Metrics m, List<SoakError> errors,
                      Instant startedAt, Instant finishedAt) {
        this.k = k; this.topic = topic; this.runs = runs; this.metrics = m;
        this.errors = Collections.unmodifiableList(errors);
        this.startedAt = startedAt; this.finishedAt = finishedAt;
    }

    public int getK() { return k; }
    public String getTopic() { return topic; }
    public int getRuns() { return runs; }
    public Metrics getMetrics() { return metrics; }
    public List<SoakError> getErrors() { return errors; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}