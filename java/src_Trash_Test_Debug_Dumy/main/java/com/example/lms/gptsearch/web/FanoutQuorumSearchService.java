package com.example.lms.gptsearch.web;

import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service that performs web search by fan‑out across multiple providers and
 * applies a quorum threshold and circuit breaker. Azure is tried first
 * when available; if the number of results is below the quorum minimum
 * then the service fans out in parallel to the remaining providers. The
 * resulting documents are deduplicated by URL and truncated to the
 * configured top K. Consecutive failures open a circuit for a
 * configurable period to avoid repeated failing calls. Configuration
 * values are injected via application properties.
 */
@Service
public class FanoutQuorumSearchService {

    private final List<WebSearchProvider> providers;
    private final int quorumMin;
    private final int topK;
    private final long fanoutTimeoutMs;
    private final int failureThreshold;
    private final long circuitOpenMs;

    private int consecutiveFailures = 0;
    private long circuitOpenedAt = -1L;

    public FanoutQuorumSearchService(
            List<WebSearchProvider> providers,
            @Value("${websearch.quorum.min:3}") int quorumMin,
            @Value("${websearch.topK:6}") int topK,
            @Value("${websearch.fanout.timeoutMs:1500}") long fanoutTimeoutMs,
            @Value("${websearch.circuit.failureThreshold:3}") int failureThreshold,
            @Value("${websearch.circuit.openMs:30000}") long circuitOpenMs) {
        this.providers = providers == null ? List.of() : providers;
        this.quorumMin = quorumMin;
        this.topK = topK;
        this.fanoutTimeoutMs = fanoutTimeoutMs;
        this.failureThreshold = failureThreshold;
        this.circuitOpenMs = circuitOpenMs;
    }

    /**
     * Determine whether the circuit is currently open. When open the
     * service short‑circuits without invoking any providers. The circuit
     * automatically closes after the configured open period.
     */
    public synchronized boolean circuitOpen() {
        if (circuitOpenedAt < 0) return false;
        if (System.currentTimeMillis() - circuitOpenedAt > circuitOpenMs) {
            circuitOpenedAt = -1L;
            consecutiveFailures = 0;
            return false;
        }
        return true;
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    private synchronized void recordFailure() {
        if (++consecutiveFailures >= failureThreshold) {
            circuitOpenedAt = System.currentTimeMillis();
        }
    }

    /**
     * Perform a web search with the given query. Azure is attempted first
     * and if not enough results are returned the remaining providers are
     * invoked in parallel. Results are deduplicated by URL and limited
     * to the configured top K. Circuit breaker state is updated based on
     * success or failure.
     *
     * @param q the search query
     * @return a WebSearchResult containing the deduplicated documents
     */
    public WebSearchResult search(WebSearchQuery q) {
        if (circuitOpen()) {
            return new WebSearchResult("circuit-open", List.of());
        }
        List<WebDocument> acc = new ArrayList<>();
        // Attempt Azure first
        providers.stream()
                .filter(p -> p.id() != null && p.id().name().equalsIgnoreCase("azure"))
                .findFirst()
                .ifPresent(p -> acc.addAll(safeSearch(p, q)));
        // If quorum not met, fan out to other providers in parallel
        if (acc.size() < quorumMin) {
            List<WebSearchProvider> others = providers.stream()
                    .filter(p -> p.id() == null || !p.id().name().equalsIgnoreCase("azure"))
                    .collect(Collectors.toList());
            acc.addAll(fanoutParallel(others, q, fanoutTimeoutMs));
        }
        // Deduplicate by URL and trim to top K
        List<WebDocument> dedup = dedupByUrl(acc);
        List<WebDocument> top = dedup.stream().limit(topK).collect(Collectors.toList());
        if (top.isEmpty()) {
            recordFailure();
        } else {
            recordSuccess();
        }
        return new WebSearchResult("fanout", top);
    }

    private List<WebDocument> fanoutParallel(List<WebSearchProvider> ps, WebSearchQuery q, long timeout) {
        if (ps.isEmpty()) return List.of();
        var exec = Executors.newFixedThreadPool(Math.min(ps.size(), 4));
        try {
            List<Callable<List<WebDocument>>> tasks = ps.stream()
                    .map(p -> (Callable<List<WebDocument>>) () -> safeSearch(p, q))
                    .collect(Collectors.toList());
            List<Future<List<WebDocument>>> fs = exec.invokeAll(tasks, timeout, TimeUnit.MILLISECONDS);
            List<WebDocument> out = new ArrayList<>();
            for (Future<List<WebDocument>> f : fs) {
                try {
                    List<WebDocument> docs = f.get();
                    if (docs != null) out.addAll(docs);
                } catch (Exception ignore) {}
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            exec.shutdownNow();
        }
    }

    private static List<WebDocument> safeSearch(WebSearchProvider p, WebSearchQuery q) {
        try {
            WebSearchResult r = p.search(q);
            return (r == null || r.getDocuments() == null) ? List.of() : r.getDocuments();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<WebDocument> dedupByUrl(List<WebDocument> in) {
        LinkedHashMap<String, WebDocument> map = new LinkedHashMap<>();
        for (WebDocument d : in) {
            String key = normalizeUrl(d == null ? null : d.getUrl());
            if (key != null && !map.containsKey(key)) {
                map.put(key, d);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url);
            String host = u.getHost();
            String path = u.getPath();
            return (host == null ? url : host) + "|" + (path == null ? "" : path);
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Capture the current configuration and circuit state for diagnostics.
     *
     * @return a map describing quorum, fanout timeout and circuit status
     */
    public Map<String, Object> snapshot() {
        return Map.of(
                "quorum.min", quorumMin,
                "topK", topK,
                "fanout.timeoutMs", fanoutTimeoutMs,
                "circuit.failureThreshold", failureThreshold,
                "circuit.openMs", circuitOpenMs,
                "circuit.open", circuitOpen()
        );
    }
}