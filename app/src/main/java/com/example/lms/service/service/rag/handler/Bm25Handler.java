package com.example.lms.service.service.rag.handler;

import com.example.lms.service.service.rag.bm25.Bm25Index;
import com.example.lms.service.service.rag.bm25.Bm25Retriever;

import java.util.List;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.service.rag.handler.Bm25Handler
 * Role: config
 * Dependencies: com.example.lms.service.service.rag.bm25.Bm25Index, com.example.lms.service.service.rag.bm25.Bm25Retriever
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.service.service.rag.handler.Bm25Handler
role: config
*/
public class Bm25Handler {
    private final Bm25Retriever retriever;
    private boolean enabled = false;
    private int topK = 10;

    public Bm25Handler(Bm25Index index) {
        this.retriever = new Bm25Retriever(index);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setTopK(int topK) { this.topK = topK; }

    public List<Bm25Retriever.Result> maybeRetrieve(String query) {
        if (!enabled || query == null || query.isBlank()) return List.of();
        return retriever.retrieve(query, topK);
    }
}