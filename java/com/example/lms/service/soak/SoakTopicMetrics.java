package com.example.lms.service.soak;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



/**
 * Aggregated metrics for a single topic in the soak test.  Metrics include
 * success and evidence counts as well as latency statistics.  These
 * metrics are computed over all completed soak items for the topic.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SoakTopicMetrics {
    private String topic;
    private int total;
    private int successCount;
    private int evidenceCount;
    private double successRate;
    private double evidenceRate;
    private long p50LatencyMs;
    private long p95LatencyMs;
    private double avgLatencyMs;
}