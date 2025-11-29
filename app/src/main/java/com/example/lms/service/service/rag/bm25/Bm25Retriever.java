package com.example.lms.service.service.rag.bm25;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.service.rag.bm25.Bm25Retriever
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.service.rag.bm25.Bm25Retriever
role: config
*/
public class Bm25Retriever {
    public static class Result {
        public final String id;
        public final double score;
        public Result(String id, double score) { this.id = id; this.score = score; }
    }

    private final Bm25Index index;

    public Bm25Retriever(Bm25Index index) { this.index = index; }

    public List<Result> retrieve(String query, int topK) {
        List<Map.Entry<String, Double>> hits = index.search(query, topK);
        List<Result> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : hits) {
            out.add(new Result(e.getKey(), e.getValue()));
        }
        return out;
    }
}