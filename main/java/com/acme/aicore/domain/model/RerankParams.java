package com.acme.aicore.domain.model;


/**
 * shim for reranking parameters.  Currently unused in the shim
 * implementation but provided for API completeness.
 */
public class RerankParams {
    public int topK = 5;
    public double alpha = 0.5;  // weight for semantic
    public double beta = 0.5;   // weight for lexical

    public RerankParams() {}
    public RerankParams(int topK, double alpha, double beta) {
        this.topK = topK; this.alpha = alpha; this.beta = beta;
    }
}