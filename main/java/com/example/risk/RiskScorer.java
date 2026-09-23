package com.example.risk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * Computes a risk decision index (RDI) for a given listing context.
 * The RDI ranges from 0 to 100 representing the probability that the context corresponds to a risk signal.
 * When no classifier has been trained the scorer returns 0 to reflect a neutral, conservative stance.
 */
@Component
@RequiredArgsConstructor
public class RiskScorer {
    private final RiskFeatureExtractor featureExtractor;
    private final RiskModelProvider modelProvider;

    /**
     * Returns integer RDI in [0,100].
     *
     * @param ctx listing context
     * @return RDI score
     */
    public int computeRdi(ListingContext ctx) {
        double[] x = featureExtractor.featuresOf(ctx);
        if (x == null || x.length == 0) {
            return 0;
        }
        RiskModelProvider.Classifier clf = modelProvider.get();
        if (clf == null) {
            return 0;
        }
        double[] p = clf.predictProba(x);
        double risk = (p != null && p.length > 1) ? p[1] : 0.0;
        return (int) Math.round(Math.max(0, Math.min(1, risk)) * 100);
    }
}