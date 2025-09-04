package com.example.lms.service.rag.tune;

import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;

/**
 * Strategy interface for tuning the relative weights of web and vector
 * retrieval.  Implementations may examine the user query and its
 * associated metadata to adjust the balance between the two sources.
 * Returning null or an array of length other than two indicates that
 * default weighting (0.5, 0.5) should be used.
 */
public interface StrategyWeightTuner {
    /**
     * Suggest a pair of weights for web and vector retrieval given the
     * original query and associated metadata.  Weights should be in
     * the range 0.0–1.0 and may be normalised by the caller; if the
     * sum of returned weights is zero the caller should fall back to
     * equal weighting.  Implementations should avoid heavy
     * computations as this method is invoked for every query.
     *
     * @param query the user query
     * @param meta  the metadata accompanying the query (may be null)
     * @return an array of length 2 containing web and vector weights or null
     */
    double[] tune(Query query, Metadata meta);
}