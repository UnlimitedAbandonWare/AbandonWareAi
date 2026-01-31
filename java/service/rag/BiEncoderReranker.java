package service.rag;

/**
 * Minimal bi-encoder fallback reranker; deterministic hash-based score to avoid NPEs.
 */
public class BiEncoderReranker {
    public double score(String query, String passage) {
        int h = Math.abs((query + "||" + passage).hashCode());
        return (h % 10000) / 10000.0;
    }
}