
package com.abandonware.ai.agent.integrations;

import java.nio.file.*;
import java.util.*;



/**
 * Runtime reranker using a prebuilt ANN index (cosine).
 */
public class SbertPreindexedReranker implements EmbeddingReranker {

    private final AnnIndex index;
    private final Embedder embedder;

    public SbertPreindexedReranker() {
        String idxDir = System.getenv().getOrDefault("SBERT_ANN_INDEX", "./data/ann_index");
        String kind = System.getenv().getOrDefault("ANN_KIND", "ivf");
        if ("hnsw".equalsIgnoreCase(kind)) {
            this.index = new HnswIndex(Paths.get(idxDir));
        } else {
            this.index = new IvfFlatIndex(Paths.get(idxDir));
        }
        this.embedder = AnnIndexer.selectEmbedder();
    }

    @Override
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> items) {
        try {
            float[] q = embedder.embed(query);
            int k = Math.min(items.size(), 20);
            int ef = Integer.parseInt(System.getenv().getOrDefault("ANN_EF_OR_NPROBE", "64"));
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
            return items;
        }
    }

    private static class Scored {
        Map<String,Object> m; double s; Scored(Map<String,Object> m, double s){this.m=m;this.s=s;}
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number)o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }
}