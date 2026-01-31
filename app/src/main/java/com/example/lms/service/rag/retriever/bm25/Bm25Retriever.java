package com.example.lms.service.rag.retriever.bm25;

import java.util.List;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.retriever.bm25.Bm25Retriever
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.retriever.bm25.Bm25Retriever
role: config
*/
public interface Bm25Retriever {
    record Candidate(String id, String title, String snippet, String source, double score, int rank, String url) {}
    List<Candidate> search(String query, int k);
}