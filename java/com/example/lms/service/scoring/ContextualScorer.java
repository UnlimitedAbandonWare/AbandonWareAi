package com.example.lms.service.scoring;

import java.util.List;



/**
 * Score RAG outputs with respect to the query and prior interactions.  The
 * contextual scorer produces a small set of signals that measure the
 * overall confidence in the retrieved information, the coverage gap
 * (proportion of information still missing) and the quality of the
 * reranker.  These signals are used downstream to determine whether to
 * promote a query to a higher tier model.
 */
public interface ContextualScorer {

    /**
     * Compute a set of metrics for a ranked list of documents.  Implementations
     * may incorporate historical paths or user actions to refine these
     * estimates.  This default interface does not prescribe the domain of
     * documents and thus accepts any type.
     *
     * @param query the original user query
     * @param rankedDocs the list of ranked documents
     * @param pastPath a list of identifiers representing the past decision path
     * @param currentSteps the current chain of retrieval steps
     * @return a report containing overall confidence, coverage gap and reranker quality
     */
    Report score(String query,
                 List<?> rankedDocs,
                 List<String> pastPath,
                 List<String> currentSteps);

    /**
     * Simple report exposing the metrics computed by the contextual scorer.
     */
    interface Report {
        double overallConfidence();
        double coverageGap();
        double rerankerQuality();
    }
}