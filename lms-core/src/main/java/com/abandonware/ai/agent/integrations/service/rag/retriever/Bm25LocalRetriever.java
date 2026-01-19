
package com.abandonware.ai.agent.integrations.service.rag.retriever;

import com.abandonware.ai.agent.integrations.index.Bm25LocalIndex;
import java.nio.file.Path;
import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.rag.retriever.Bm25LocalRetriever
 * Role: config
 * Dependencies: com.abandonware.ai.agent.integrations.index.Bm25LocalIndex
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.rag.retriever.Bm25LocalRetriever
role: config
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
        this.index = index;
        this.enabled = enabled;
    }

    public void loadFromTsv(Path p) {
        if (index != null) index.loadFromTsv(p);
    }

    public List<Candidate> retrieve(String query, int topK) {
        if (!enabled || index == null) return Collections.emptyList();
        List<Map.Entry<Bm25LocalIndex.Doc, Double>> hits = index.search(query, topK);
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<Bm25LocalIndex.Doc, Double> e : hits) {
            Bm25LocalIndex.Doc d = e.getKey();
            out.add(new Candidate(d.id, d.title, summarize(d.text), d.url, e.getValue()));
        }
        return out;
    }

    private static String summarize(String text) {
        if (text == null) return "";
        return (text.length() > 240) ? text.substring(0, 237) + "..." : text;
    }
}