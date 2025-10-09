package com.example.lms.service.soak;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * Service responsible for executing soak tests.  It samples a set of
 * queries from a {@link SoakQueryProvider}, executes them against the
 * system under test and records simple metrics.  This baseline
 * implementation does not perform any real LLM calls; instead it
 * synthesises trivial results.  This allows the soak infrastructure
 * (metrics aggregation and API) to compile even when the provider has
 * no backing query source.
 */
@Service
@RequiredArgsConstructor
public class SoakTestService {
    private static final Logger log = LoggerFactory.getLogger(SoakTestService.class);
    private final SoakQueryProvider queryProvider;

    /**
     * Run a soak test with the specified number of queries and an optional
     * topic filter.  When the topic is "all" or null, queries from all
     * domains should be used.  The returned result contains per-item
     * details and aggregated metrics.
     */
    public SoakRunResult runSoak(int k, String topicFilter) {
        int limit = Math.max(1, k);
        String topic = (topicFilter == null || topicFilter.isBlank() || "all".equalsIgnoreCase(topicFilter)) ? null : topicFilter;
        List<String> queries = queryProvider.sample(limit, Optional.ofNullable(topic));
        List<SoakRunResult.Item> items = new ArrayList<>();
        for (String q : queries) {
            String t = topic != null ? topic : "default";
            SoakRunResult.Item item = SoakRunResult.Item.builder()
                    .query(q)
                    .topic(t)
                    .success(false)
                    .hasEvidence(false)
                    .latencyMs(0L)
                    .detail(new LinkedHashMap<>())
                    .build();
            items.add(item);
        }
        // Aggregate metrics even when no items exist
        Map<String, SoakTopicMetrics> metrics = SoakMetricsAgg.aggregate(items);
        String table = SoakMetricsAgg.toMarkdown(metrics);
        
try {
    String sid = org.slf4j.MDC.get("sessionId");
    String xrid = org.slf4j.MDC.get("x-request-id");
    log.info("SOAK_FILTER_LABEL topic={} items={} sessionId={} xrid={}", (topic != null ? topic : "all"), items.size(), sid, xrid);
    metrics.values().forEach(m -> {
        log.info("SOAK_METRIC_LABEL topic={} n={} success_rate={} evidence_rate={} p50={} p95={} avg={} sessionId={} xrid={}",
                m.getTopic(), m.getTotal(),
                String.format(java.util.Locale.ROOT, "%.2f", (m.getSuccessRate() * 100.0)),
                String.format(java.util.Locale.ROOT, "%.2f", (m.getEvidenceRate() * 100.0)),
                m.getP50LatencyMs(), m.getP95LatencyMs(),
                String.format(java.util.Locale.ROOT, "%.2f", m.getAvgLatencyMs()),
                sid, xrid);
    });
} catch (Exception ignore) {}
return SoakRunResult.builder().items(items).metrics(metrics).markdownTable(table).build();
    }
}