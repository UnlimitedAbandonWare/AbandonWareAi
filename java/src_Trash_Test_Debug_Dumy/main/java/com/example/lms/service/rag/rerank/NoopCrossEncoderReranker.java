package com.example.lms.service.rag.rerank;

import java.util.List;

/**
 * A no‑op cross‑encoder reranker.  This implementation simply returns the
 * provided list of candidates without modification and reports itself as
 * inactive.  It is registered under the bean name {@code noopCrossEncoderReranker}
 * and can be selected via the {@code abandonware.reranker.backend=noop}
 * configuration.  When selected, the application performs no extra scoring
 * and leaves the ranking order unchanged.
 */

public class NoopCrossEncoderReranker implements CrossEncoderReranker {

    @Override
    public java.util.List<dev.langchain4j.rag.content.Content> rerank(String query,
                                                                     java.util.List<dev.langchain4j.rag.content.Content> candidates,
                                                                     int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return java.util.List.of();
        }
        int n = Math.max(1, Math.min(topN, candidates.size()));
        return new java.util.ArrayList<>(candidates.subList(0, n));
    }

    @Override
    public java.util.List<dev.langchain4j.rag.content.Content> rerank(String query,
                                                                     java.util.List<dev.langchain4j.rag.content.Content> candidates) {
        return (candidates == null) ? java.util.List.of() : new java.util.ArrayList<>(candidates);
    }


    public RerankerStatus status() {
        return new RerankerStatus(false, "noop", "No re-ranking performed");
    }
}