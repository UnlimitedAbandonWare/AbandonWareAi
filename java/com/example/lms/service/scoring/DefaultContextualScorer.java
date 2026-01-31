package com.example.lms.service.scoring;

import org.springframework.stereotype.Service;
import java.util.List;



/**
 * A minimal implementation of {@link ContextualScorer} that computes
 * conservative heuristics for overall confidence, coverage gap and
 * reranker quality.  This implementation is intended as a shim
 * until a more sophisticated scorer is introduced.  It makes no claims
 * about the actual quality of the retrieved documents.
 */
@Service
public class DefaultContextualScorer implements ContextualScorer {

    @Override
    public Report score(String query, List<?> rankedDocs, List<String> pastPath, List<String> currentSteps) {
        int n = (rankedDocs == null) ? 0 : rankedDocs.size();
        // Derive simple heuristics: more documents imply higher confidence and lower coverage gap.
        double confidence = Math.min(1.0, n / 10.0); // up to 10 docs scales to 1.0
        double coverageGap = n == 0 ? 1.0 : Math.max(0.0, 1.0 - confidence);
        double rerankerQuality = 0.5; // neutral default until a real metric is available
        return new SimpleReport(confidence, coverageGap, rerankerQuality);
    }

    /** Simple immutable report implementation. */
    private static class SimpleReport implements Report {
        private final double confidence;
        private final double coverageGap;
        private final double rerankerQuality;
        SimpleReport(double confidence, double gap, double quality) {
            this.confidence = confidence;
            this.coverageGap = gap;
            this.rerankerQuality = quality;
        }
        @Override public double overallConfidence() { return confidence; }
        @Override public double coverageGap() { return coverageGap; }
        @Override public double rerankerQuality() { return rerankerQuality; }
    }
}