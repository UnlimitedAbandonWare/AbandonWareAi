package com.example.lms.search.extract;


/**
 * Extraction mode for the keyword extractor.
 *
 * <p>The extractor may operate purely via rule based augmentation (RULE),
 * rely solely on a language model (LLM), merge both sources (HYBRID)
 * or decide automatically based on the complexity of the user prompt (AUTO).
 */
public enum QueryExtractionMode {
    /**
     * Only rule based expansion via {@link com.example.lms.service.QueryAugmentationService}.
     */
    RULE,
    /**
     * Only LLM based extraction via {@link com.example.lms.transform.QueryTransformer}.
     */
    LLM,
    /**
     * Use both rule and LLM sources and then merge the results.
     */
    HYBRID,
    /**
     * Decide automatically based on prompt complexity and other heuristics.
     */
    AUTO;
}