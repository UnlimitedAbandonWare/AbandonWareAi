package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;
import java.util.Map;

/**
 * Defines a lightweight scorer that produces normalized scores in the range [0.0, 1.0] for each document.
 * Implementations should avoid heavy operations and instead rank or reweight existing candidates.
 */
public interface NormalizedScorer {
    /**
     * Compute a normalized score map for the given list of candidates.
     *
     * @param candidates documents to score
     * @param query the user query (may be null)
     * @return a mapping from content ID to normalized score between 0 and 1
     */
    Map<String, Double> scoreMap(List<Content> candidates, String query);
}