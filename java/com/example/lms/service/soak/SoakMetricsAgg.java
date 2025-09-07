package com.example.lms.service.soak;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utility class for aggregating soak test metrics by topic and
 * converting them into a human-friendly markdown table.
 */
public final class SoakMetricsAgg {
    private SoakMetricsAgg() {}

    /**
     * Aggregate a list of soak test items into metrics keyed by topic.
     */
    public static Map<String, SoakTopicMetrics> aggregate(List<SoakRunResult.Item> items) {
        Map<String, List<SoakRunResult.Item>> byTopic = items.stream()
                .collect(Collectors.groupingBy(i -> Optional.ofNullable(i.getTopic()).orElse("default"), LinkedHashMap::new, Collectors.toList()));
        Map<String, SoakTopicMetrics> out = new LinkedHashMap<>();
        byTopic.forEach((topic, list) -> {
            int total = list.size();
            int succ = (int) list.stream().filter(SoakRunResult.Item::isSuccess).count();
            int evid = (int) list.stream().filter(SoakRunResult.Item::isHasEvidence).count();
            List<Long> latencies = list.stream().map(SoakRunResult.Item::getLatencyMs).sorted().collect(Collectors.toList());
            SoakTopicMetrics metrics = SoakTopicMetrics.builder()
                    .topic(topic)
                    .total(total)
                    .successCount(succ)
                    .evidenceCount(evid)
                    .successRate(total > 0 ? ((double) succ) / total : 0.0)
                    .evidenceRate(total > 0 ? ((double) evid) / total : 0.0)
                    .p50LatencyMs(percentile(latencies, 0.50))
                    .p95LatencyMs(percentile(latencies, 0.95))
                    .avgLatencyMs(avg(latencies))
                    .build();
            out.put(topic, metrics);
        });
        return out;
    }

    /**
     * Render a markdown table summarising the metrics.  The columns include
     * topic, number of items, success rate, evidence rate, p50 latency,
     * p95 latency and average latency.
     */
    public static String toMarkdown(Map<String, SoakTopicMetrics> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("| topic | n | success% | evidence% | p50(ms) | p95(ms) | avg(ms) |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
        metrics.values().forEach(m -> sb.append(String.format(
                "| %s | %d | %.1f | %.1f | %d | %d | %.1f |\n",
                m.getTopic(), m.getTotal(), m.getSuccessRate() * 100.0,
                m.getEvidenceRate() * 100.0, m.getP50LatencyMs(), m.getP95LatencyMs(),
                m.getAvgLatencyMs())));
        return sb.toString();
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0L;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private static double avg(List<Long> vals) {
        if (vals.isEmpty()) return 0.0;
        long sum = 0;
        for (long v : vals) sum += v;
        return ((double) sum) / vals.size();
    }
}