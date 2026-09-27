package com.example.lms.service.soak;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Public default implementation to satisfy {@link com.example.lms.config.SoakConfig}.
 * Provides a lightweight aggregation over {@link SearchOrchestrator}.
 */
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultSoakTestService implements SoakTestService {
    private static final System.Logger LOG = System.getLogger(DefaultSoakTestService.class.getName());

    private final SoakQueryProvider provider;
    private final SearchOrchestrator orchestrator;
    private final SoakDatasetIngestService datasetIngest;
    private final SoakQuickJsonlExporter jsonlExporter;

    public DefaultSoakTestService(SoakQueryProvider provider,
                                 SearchOrchestrator orchestrator,
                                 SoakDatasetIngestService datasetIngest,
                                 SoakQuickJsonlExporter jsonlExporter) {
        this.provider = provider;
        this.orchestrator = orchestrator;
        this.datasetIngest = datasetIngest;
        this.jsonlExporter = jsonlExporter;
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
                if (results == null) results = Collections.emptyList();
            } catch (Exception error) {
                traceSuppressed("runSearch", "run_search", error);
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

    @Override
    public SoakQuickReport runQuick(int k, String topic) {
        SoakQuickReport rep = new SoakQuickReport();
        rep.startedAt = Instant.now();
        rep.k = k;
        rep.topic = (topic == null || topic.isBlank()) ? "all" : topic;

        List<String> queries = provider.queries(topic);
        if (queries == null) {
            queries = Collections.emptyList();
        }

        List<Long> latencies = new ArrayList<>();
        int total = 0;
        int success = 0;
        int evidence = 0;
        int timeouts = 0;

        for (String q : queries) {
            total++;
            long t0 = System.currentTimeMillis();
            List<SearchOrchestrator.SearchResult> results = Collections.emptyList();
            String note = null;
            try {
                results = orchestrator.search(q, Math.max(1, k));
            } catch (Exception e) {
                traceSuppressed("quickSearch", "quick_search", e);
                String failureClass = soakFailureClass(e);
                note = "error:" + failureClass;
                if (note.toLowerCase().contains("timeout")) {
                    timeouts++;
                }
            }
            long took = System.currentTimeMillis() - t0;
            latencies.add(took);

            boolean ok = results != null && !results.isEmpty();
            boolean ev = false;
            if (ok) {
                for (SearchOrchestrator.SearchResult r : results) {
                    if (r != null && r.supportedByEvidence) {
                        ev = true;
                        break;
                    }
                }
            }

            if (ok) {
                success++;
            }
            if (ev) {
                evidence++;
            }

            SoakQuickReport.Item item = new SoakQuickReport.Item();
            item.query = q;
            item.success = ok;
            item.evidence = ev;
            item.latencyMs = took;
            item.note = note;
            // capture top evidence snippet/url for Soak→Dataset accumulation
            if (ok) {
                SearchOrchestrator.SearchResult top = results.get(0);
                if (top != null) {
                    item.topSnippet = top.snippet;
                    item.topUrl = top.url;
                    item.topSource = top.source;
                }
            }
            rep.items.add(item);
        }

        Collections.sort(latencies);
        rep.metrics.total = total;
        rep.metrics.success = success;
        rep.metrics.evidence = evidence;
        rep.metrics.successRate = (total == 0 ? 0.0 : (success * 1.0 / total));
        rep.metrics.evidenceRate = (total == 0 ? 0.0 : (evidence * 1.0 / total));
        // requested extra quick metrics (kept stable as optional fields)
        rep.metrics.ragHitRate = rep.metrics.successRate;
        rep.metrics.naverSoftTimedOutRate = (total == 0 ? 0.0 : (timeouts * 1.0 / total));
        rep.metrics.avgLatencyMs = (long) avg(latencies);
        rep.metrics.p95LatencyMs = percentile(latencies, 0.95);

        rep.finishedAt = Instant.now();

        // Soak → Dataset 선순환: best-effort ingest + jsonl append (fail-soft)
        try {
            if (datasetIngest != null) datasetIngest.ingestQuick(rep);
        } catch (Exception error) {
            traceSuppressed("datasetIngest", "dataset_ingest", error);
        }
        try {
            if (jsonlExporter != null) jsonlExporter.append(rep);
        } catch (Exception error) {
            traceSuppressed("jsonlExport", "jsonl_export", error);
        }
        return rep;
    }

    private static String soakFailureClass(Throwable e) {
        NightmareBreaker.FailureKind kind = NightmareBreaker.classify(e);
        if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
            return "cancelled";
        }
        if (kind == NightmareBreaker.FailureKind.TIMEOUT) {
            return "timeout";
        }
        if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
            return "rate-limit";
        }
        return "provider-error";
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

    private static void traceSuppressed(String traceStage, String logStage, Throwable error) {
        String type = suppressedErrorType(error);
        TraceStore.put("soak.default.suppressed." + traceStage, true);
        TraceStore.put("soak.default.suppressed." + traceStage + ".errorType", type);
        TraceStore.put("soak.default.suppressed.stage", traceStage);
        TraceStore.put("soak.default.suppressed.errorType", type);
        traceSoakSkipped(logStage, error);
    }

    private static void traceSoakSkipped(String stage, Throwable error) {
        LOG.log(System.Logger.Level.DEBUG,
                "Default soak fail-soft stage=" + stage + " errorType=" + errorType(error));
    }

    private static String suppressedErrorType(Throwable error) {
        String failureClass = soakFailureClass(error);
        return "provider-error".equals(failureClass) ? errorType(error) : failureClass;
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
