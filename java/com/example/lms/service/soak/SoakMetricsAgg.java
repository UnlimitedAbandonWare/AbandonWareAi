package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for aggregating soak test metrics by topic and
 * converting them into a human-friendly markdown table.
 */
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakMetricsAgg {

    public static Map<String, SoakTopicMetrics> aggregate(List<SoakRunResult.Item> items) {
        Map<String, List<SoakRunResult.Item>> byTopic = items.stream()
                .collect(Collectors.groupingBy(i -> i.getTopic() == null ? "default" : i.getTopic()));
        Map<String, SoakTopicMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SoakRunResult.Item>> e : byTopic.entrySet()) {
            String topic = e.getKey();
            List<SoakRunResult.Item> list = e.getValue();
            List<Long> lats = list.stream().map(SoakRunResult.Item::getLatencyMs).sorted().collect(Collectors.toList());
            int total = list.size();
            int succ = (int) list.stream().filter(SoakRunResult.Item::isSuccess).count();
            int ev = (int) list.stream().filter(SoakRunResult.Item::isHasEvidence).count();
            SoakTopicMetrics m = SoakTopicMetrics.builder()
                    .topic(topic)
                    .total(total)
                    .successCount(succ)
                    .evidenceCount(ev)
                    .successRate(total == 0 ? 0.0 : succ * 1.0 / total)
                    .evidenceRate(total == 0 ? 0.0 : ev * 1.0 / total)
                    .p50LatencyMs(percentile(lats, 0.50))
                    .p95LatencyMs(percentile(lats, 0.95))
                    .avgLatencyMs(avg(lats))
                    .build();
            result.put(topic, m);
        }
        return result;
    }

    public static String toMarkdownTable(Map<String, SoakTopicMetrics> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Topic | Total | Success | Evidence | Succ% | Evid% | P50(ms) | P95(ms) | Avg(ms) |\n");
        sb.append("|-|-:|-:|-:|-:|-:|-:|-:|-:|\n");
        for (SoakTopicMetrics m : metrics.values()) {
            sb.append("| ").append(m.getTopic()).append(" | ")
              .append(m.getTotal()).append(" | ")
              .append(m.getSuccessCount()).append(" | ")
              .append(m.getEvidenceCount()).append(" | ")
              .append(String.format(Locale.ROOT, "%.1f", m.getSuccessRate()*100)).append(" | ")
              .append(String.format(Locale.ROOT, "%.1f", m.getEvidenceRate()*100)).append(" | ")
              .append(m.getP50LatencyMs()).append(" | ")
              .append(m.getP95LatencyMs()).append(" | ")
              .append(String.format(Locale.ROOT, "%.1f", m.getAvgLatencyMs())).append(" |\n");
        }
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