package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;



/**
 * A no-op cross-encoder reranker.
 * Returns the provided candidates unchanged (or truncated to topN) and reports itself inactive.
 */
@Component("noopCrossEncoderReranker")
public class NoopCrossEncoderReranker implements CrossEncoderReranker {

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        int n = Math.max(1, Math.min(topN, candidates.size()));
        return new ArrayList<>(candidates.subList(0, n));
    }

    /** Convenience overload: identity without topN cut. */
    public List<Content> rerank(String query, List<Content> candidates) {
        return (candidates == null) ? List.of() : new ArrayList<>(candidates);
    }

    /** Status endpoint used by diagnostics. */
    public RerankerStatus status() {
        return new RerankerStatus(false, "noop", "No re-ranking performed");
    }
}