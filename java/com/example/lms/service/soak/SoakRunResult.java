package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Result object returned from a soak test run. Contains the per-item
 * results along with aggregated metrics and a pre-rendered markdown table.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakRunResult {
    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Item {
        private String query;
        private String topic;
        private boolean success;
        private boolean hasEvidence;
        private long latencyMs;
        private Object detail;
    }

    private List<Item> items;
    private Map<String, SoakTopicMetrics> metrics;
    private String markdownTable;
}