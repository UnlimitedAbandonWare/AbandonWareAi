package com.example.risk;

import org.springframework.stereotype.Component;

/**
 * Computes a risk decision index (RDI) for a given listing context.  The
 * RDI ranges from 0 to 100 representing the probability that the context
 * corresponds to a risk signal.  When no classifier has been trained
 * the scorer returns 0 to reflect a neutral, conservative stance.
 */
@Component
public class RiskScorer {
    private final RiskFeatureExtractor featureExtractor;
    private final RiskModelProvider modelProvider;

    public RiskScorer(RiskFeatureExtractor featureExtractor, RiskModelProvider modelProvider) {
        this.featureExtractor = featureExtractor;
        this.modelProvider = modelProvider;
    }

    /**
     * Compute the risk decision index for the provided context.
     *
     * @param ctx the context to score; may be null
     * @return integer RDI in [0,100]; returns 0 if no classifier is ready
     */
    public int computeRdi(ListingContext ctx) {
        if (ctx == null) {
            return 0;
        }
        double[] x = featureExtractor.featuresOf(ctx);
        if (x == null || x.length == 0) {
            return 0;
        }
        var clf = modelProvider.get();
        if (clf == null) {
            return 0;
        }
        double[] p = clf.predictProba(x);
        double risk = (p.length > 1) ? p[1] : 0.0;
        return (int)Math.round(Math.max(0, Math.min(1, risk)) * 100);
    }
}