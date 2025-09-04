package com.example.lms.service.rag.model;

/**
 * Enumeration indicating which retrieval strategy should be used by
 * the hybrid retriever.
 *
 * <p>The {@code RetrievalAccumulator} stores a {@link RouteDecision} to
 * capture the overall routing choice for a query.  Although currently only
 * {@link #HYBRID} is used, additional values may be introduced to support
 * specialised routing such as web‑only or vector‑only retrieval.</p>
 */
public enum RouteDecision {
    /** Use both web search and vector search, merging the results. */
    HYBRID,
    /** Use only the web search stage. */
    WEB,
    /** Use only the vector database search stage. */
    VECTOR
}