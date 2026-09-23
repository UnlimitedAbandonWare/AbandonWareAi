
package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.nio.file.*;
import java.util.*;



/**
 * Runtime reranker using a prebuilt ANN index (cosine).
 */
public class SbertPreindexedReranker implements EmbeddingReranker {

    private final AnnIndex index;
    private final Embedder embedder;

    public SbertPreindexedReranker() {
        this(createIndex(), AnnIndexer.selectEmbedder());
    }

    SbertPreindexedReranker(AnnIndex index, Embedder embedder) {
        this.index = index;
        this.embedder = embedder;
    }

    private static AnnIndex createIndex() {
        String idxDir = System.getenv().getOrDefault("SBERT_ANN_INDEX", "./data/ann_index");
        String kind = System.getenv().getOrDefault("ANN_KIND", "ivf");
        if ("hnsw".equalsIgnoreCase(kind)) {
            return new HnswIndex(Paths.get(idxDir));
        } else {
            return new IvfFlatIndex(Paths.get(idxDir));
        }
    }

    @Override
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> items) {
        try {
            float[] q = embedder.embed(query);
            int k = Math.min(items.size(), 20);
            int ef = parseEfOrNprobe(System.getenv().getOrDefault("ANN_EF_OR_NPROBE", "64"));
            List<AnnIndex.AnnHit> hits = index.search(q, k, ef);
            Map<String, Double> byId = new HashMap<>();
            int r = 1;
            for (AnnIndex.AnnHit h : hits) {
                byId.put(h.docId(), 1.0 / Math.max(1, r++));
            }
            List<Scored> tmp = new ArrayList<>();
            for (Map<String,Object> m : items) {
                String id = String.valueOf(m.getOrDefault("id", ""));
                double base = toDouble(m.get("score"));
                double ann = byId.getOrDefault(id, 0.0);
                double finalScore = 0.6 * ann + 0.4 * Math.log1p(Math.max(0.0, base));
                tmp.add(new Scored(m, finalScore));
            }
            tmp.sort((a,b)-> Double.compare(b.s, a.s));
            List<Map<String,Object>> out = new ArrayList<>();
            int rank = 1;
            for (Scored s : tmp) {
                Map<String,Object> m = new LinkedHashMap<>(s.m);
                m.put("score", s.s);
                m.put("rank", rank++);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            traceSuppressed("rerank", e, query);
            return items;
        }
    }

    private static class Scored {
        Map<String,Object> m; double s; Scored(Map<String,Object> m, double s){this.m=m;this.s=s;}
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return finiteOrFallback(((Number)o).doubleValue(), o);
        try {
            return finiteOrFallback(Double.parseDouble(String.valueOf(o)), o);
        } catch (NumberFormatException e) {
            traceSuppressed("score.parseFallback", o, e);
            return 0.0;
        }
    }

    private static double finiteOrFallback(double value, Object raw) {
        if (Double.isFinite(value)) {
            return value;
        }
        traceSuppressed("score.parseFallback", raw, new NumberFormatException("non_finite"));
        return 0.0;
    }

    static int parseEfOrNprobe(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 64;
        }
        try {
            return Math.max(1, Integer.parseInt(rawValue.trim()));
        } catch (NumberFormatException error) {
            traceSuppressedEf(rawValue, error);
            return 64;
        }
    }

    private static void traceSuppressed(String stage, Throwable error, String query) {
        TraceStore.put("agent.sbertPreindexed.suppressed", true);
        TraceStore.put("agent.sbertPreindexed.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.sbertPreindexed.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.sbertPreindexed.suppressed.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("agent.sbertPreindexed.suppressed.queryLength", query == null ? 0 : query.length());
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String key = "agent.sbertPreindexed." + stage;
        String raw = String.valueOf(value);
        TraceStore.put(key, true);
        TraceStore.put(key + ".errorType", "invalid_number");
        TraceStore.put(key + ".valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put(key + ".valueLength", raw.length());
    }

    private static void traceSuppressedEf(String rawValue, Throwable error) {
        TraceStore.put("agent.sbertPreindexed.efOrNprobe.suppressed", true);
        TraceStore.put("agent.sbertPreindexed.efOrNprobe.suppressed.stage", "ANN_EF_OR_NPROBE");
        TraceStore.put("agent.sbertPreindexed.efOrNprobe.suppressed.errorType", "invalid_number");
        TraceStore.put("agent.sbertPreindexed.efOrNprobe.suppressed.valueHash", SafeRedactor.hashValue(rawValue));
        TraceStore.put("agent.sbertPreindexed.efOrNprobe.suppressed.valueLength",
                rawValue == null ? 0 : rawValue.length());
    }
}
