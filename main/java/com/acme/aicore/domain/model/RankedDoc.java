package com.acme.aicore.domain.model;


/**
 * Encapsulates a document identifier and its relevance score.  Instances are
 * produced by ranking algorithms and passed to the prompt builder when
 * constructing the evidence section of a prompt.
 */
public record RankedDoc(String id, double score) {
    public RankedDoc {
        if (!Double.isFinite(score)) {
            score = 0.0;
        }
    }

    public static RankedDoc of(String id, double score) {
        return new RankedDoc(id, score);
    }
}
