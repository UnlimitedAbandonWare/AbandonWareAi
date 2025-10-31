package com.example.lms.service.soak;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Public default implementation to satisfy {@link com.example.lms.config.SoakConfig}.
 * Provides a lightweight aggregation over {@link SearchOrchestrator}.
 */
public class DefaultSoakTestService implements SoakTestService {
    private final SoakQueryProvider provider;
    private final SearchOrchestrator orchestrator;

    public DefaultSoakTestService(SoakQueryProvider provider, SearchOrchestrator orchestrator) {
        this.provider = provider;
        this.orchestrator = orchestrator;
    }

    @Override
    public SoakReport run(int k, String topic) {
        List<String> queries = provider.queries(topic);
        if (queries == null) queries = Collections.emptyList();
        int runs = 0;
        List<Long> latencies = new ArrayList<>();
        double ndcgSum = 0.0;

        for (String q : queries) {
            runs++;
            long t0 = System.currentTimeMillis();
            List<SearchOrchestrator.SearchResult> results = Collections.emptyList();
            try {
                results = orchestrator.search(q, k);
            } catch (Exception ignored) {
                // keep fail-soft
            }
            long took = System.currentTimeMillis() - t0;
            latencies.add(took);

            // very light-weight nDCG@k approximation: treat supportedByEvidence=true as relevance=1, else 0
            double dcg = 0.0;
            for (int i = 0; i < results.size(); i++) {
                boolean rel = results.get(i) != null && results.get(i).supportedByEvidence;
                if (rel) {
                    dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // log2(i+2)
                }
            }
            // ideal DCG when all top-k are relevant: sum_{i=0..k-1} 1/log2(i+2)
            double idcg = 0.0;
            for (int i = 0; i < Math.min(k, results.size()); i++) {
                idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
            ndcgSum += (idcg == 0.0 ? 0.0 : dcg / idcg);
        }

        SoakReport.Metrics m = new SoakReport.Metrics();
        Collections.sort(latencies);
        m.avgLatencyMs = avg(latencies);
        m.p50LatencyMs = percentile(latencies, 0.50);
        m.p95LatencyMs = percentile(latencies, 0.95);
        m.timeoutRate = 0.0; // this simple impl doesn't simulate timeouts
        m.nDCG10 = (runs == 0 ? 0.0 : ndcgSum / runs);

        return new SoakReport(k, topic == null ? "all" : topic, runs, m, Collections.emptyList(),
                Instant.now(), Instant.now());
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0L;
        int idx = (int) Math.ceil(p * values.size()) - 1;
        idx = Math.max(0, Math.min(idx, values.size() - 1));
        return values.get(idx);
    }

    private static double avg(List<Long> vals) {
        if (vals.isEmpty()) return 0.0;
        long sum = 0L;
        for (Long v : vals) sum += v;
        return ((double) sum) / vals.size();
    }
}
