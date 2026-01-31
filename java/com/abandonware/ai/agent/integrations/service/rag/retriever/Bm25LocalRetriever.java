package com.abandonware.ai.agent.integrations.service.rag.retriever;



import com.abandonware.ai.agent.integrations.index.Bm25LocalIndex;
import java.nio.file.Path;
import java.util.*;
/**
 * BM25 Local Retriever
 * Toggle: retrieval.bm25.enabled
 * Fail-soft: returns empty list if disabled or index unavailable.
 * SSE: emitting is delegated to upstream chain; this stub focuses on compile-time readiness.
 */
public class Bm25LocalRetriever {
    private final Bm25LocalIndex index;
    private final boolean enabled;

    public static class Candidate {
        public final String id, title, snippet, source;
        public final double score;
        public Candidate(String id, String title, String snippet, String source, double score) {
            this.id = id; this.title = title; this.snippet = snippet; this.source = source; this.score = score;
        }
    }

    public Bm25LocalRetriever(Bm25LocalIndex index, boolean enabled) {
        this.index = index; this.enabled = enabled;
    }

    public List<Candidate> retrieve(String query, int topK) {
        if (!enabled || index == null) return Collections.emptyList();
        List<Map.Entry<Bm25LocalIndex.Doc, Double>> hits = index.search(query, topK);
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<Bm25LocalIndex.Doc, Double> e : hits) {
            Bm25LocalIndex.Doc d = e.getKey();
            out.add(new Candidate(d.id, d.title, truncate(d.text, 240), d.url, e.getValue()));
        }
        return out;
    }

    private String truncate(String s, int n){
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "/* ... *&#47;";
    }
}