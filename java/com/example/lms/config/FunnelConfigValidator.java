package com.example.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;



/**
 * Validates the relative ordering of search, fusion and rerank limits in the
 * hybrid retrieval pipeline.  A well-formed retrieval funnel collects a
 * sufficiently large set of documents from the search phase, fuses them
 * into a smaller candidate set and finally passes an even smaller subset
 * through the cross-encoder reranker.  When these values are misordered
 * (e.g. search.top-k < rerank.ce.topK) the system wastes compute or
 * discards potentially relevant documents.  This validator eagerly checks
 * the configured values on application startup and fails fast when the
 * funnel is inverted.
 */
@Component
public class FunnelConfigValidator {

    /**
     * Creates a new validator using values injected from the application
     * configuration.  If any invalid ordering is detected an
     * {@link IllegalStateException} is thrown during context initialisation,
     * preventing the application from starting with an inconsistent funnel.
     *
     * @param searchTopK    the number of documents retrieved per query
     * @param fuseCandidates the number of fused documents considered before reranking
     * @param ceTopK        the number of candidates passed to the cross-encoder
     */
    public FunnelConfigValidator(
            @Value("${rag.search.top-k:32}") int searchTopK,
            @Value("${rag.fuse.candidates:64}") int fuseCandidates,
            @Value("${ranking.rerank.ce.topK:12}") int ceTopK
    ) {
        // ensure positive values
        if (searchTopK <= 0 || fuseCandidates <= 0 || ceTopK <= 0) {
            throw new IllegalStateException(String.format(
                    "Invalid funnel sizes: search.top-k=%d, fuse.candidates=%d, ce.topK=%d", searchTopK, fuseCandidates, ceTopK));
        }
        // searchTopK must be >= ceTopK
        if (searchTopK < ceTopK) {
            throw new IllegalStateException(String.format(
                    "Search top-k (%d) must be greater than or equal to rerank.ce.topK (%d)", searchTopK, ceTopK));
        }
        // fuseCandidates must be >= searchTopK
        if (fuseCandidates < searchTopK) {
            throw new IllegalStateException(String.format(
                    "Fuse candidates (%d) must be greater than or equal to search.top-k (%d)", fuseCandidates, searchTopK));
        }
    }
}