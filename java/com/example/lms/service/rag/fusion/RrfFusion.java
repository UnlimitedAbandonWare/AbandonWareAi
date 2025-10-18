package com.example.lms.service.rag.fusion;

import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import java.util.*;




/**
 * A simple utility implementing weighted Reciprocal Rank Fusion (RRF) for
 * merging search results from multiple providers.  Each provider can be
 * assigned a relative weight which controls its influence on the final
 * ranking.  Documents are deduplicated by URL and scored according to
 * {@code weight / (k0 + rank)} where {@code k0} is a configurable offset
 * (commonly between 60 and 100) and {@code rank} is the 1‑based position
 * of the document within its provider's result list.  Higher scores
 * indicate more highly ranked documents.
 */
public final class RrfFusion {
    private com.example.lms.service.rag.fusion.FusionCalibrator calibrator;
    public void setCalibrator(com.example.lms.service.rag.fusion.FusionCalibrator c){ this.calibrator=c; }

// Injected: optional FusionCalibrator hook (Platt->Isotonic->Temp)


    private RrfFusion() {
        // utility class
    }

    /**
     * Combine multiple provider search results into a single ranked list.
     *
     * @param results list of search result objects, one per provider
     * @param weights mapping from provider name to a weight (defaults to 1.0 when missing)
     * @param k0 base offset used in the RRF formula (must be positive)
     * @param k the maximum number of documents to return
     * @return fused list of documents ordered by descending RRF score
     */
    public static List<WebDocument> combineWeighted(List<WebSearchResult> results,
                                                    Map<String, Double> weights,
                                                    int k0,
                                                    int k) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        // Map from unique document key to aggregate score and representative doc
        Map<String, ScoredDoc> aggregate = new LinkedHashMap<>();
        for (WebSearchResult res : results) {
            if (res == null) continue;
            List<WebDocument> docs = res.getDocuments();
            if (docs == null) continue;
            String provider = res.getProviderId();
            double w = weights.getOrDefault(provider, 1.0);
            int rank = 1;
            for (WebDocument doc : docs) {
                if (doc == null) {
                    rank++;
                    continue;
                }
                // Use URL as the deduplication key when present; fall back to title
                String key = (doc.getUrl() != null && !doc.getUrl().isBlank())
                        ? doc.getUrl()
                        : doc.getTitle() != null
                            ? doc.getTitle().trim()
                            : String.valueOf(doc.hashCode());
                double scoreIncrement = w / (double) (k0 + rank);
                aggregate.merge(key, new ScoredDoc(doc, scoreIncrement), (oldVal, newVal) -> {
                    oldVal.score += newVal.score;
                    return oldVal;
                });
                rank++;
            }
        }
        // Sort by descending score and pick top k
        return aggregate.values().stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(k)
                .map(sd -> sd.doc)
                .toList();
    }

    /**
     * Internal holder for a document and its accumulated score.
     */
    private static class ScoredDoc {
        final WebDocument doc;
        double score;
        ScoredDoc(WebDocument d, double s) {
            this.doc = d;
            this.score = s;
        }
    }
}