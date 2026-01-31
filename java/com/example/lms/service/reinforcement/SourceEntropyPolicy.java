package com.example.lms.service.reinforcement;

import com.example.lms.entity.TranslationMemory;
import java.util.Map;



/**
 * A reward policy that boosts snippets based on the information entropy of their sources.
 */
public class SourceEntropyPolicy implements RewardScoringEngine.RewardPolicy {

    /** Scaling factor applied to the entropy term. */
    private final double alpha;
    /** Source distribution for the current computation. */
    private Map<String, Integer> sourceDistribution;

    public SourceEntropyPolicy(double alpha) {
        this.alpha = alpha;
    }

    public SourceEntropyPolicy withSourceDistribution(Map<String, Integer> distribution) {
        this.sourceDistribution = distribution;
        return this;
    }

    @Override
    public double compute(TranslationMemory m, String q, double similarity) {
        if (sourceDistribution == null || sourceDistribution.isEmpty()) {
            return 1.0;
        }
        double total = sourceDistribution.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        if (total <= 0) {
            return 1.0;
        }
        double entropy = 0.0;
        for (Integer count : sourceDistribution.values()) {
            double p = count / total;
            if (p > 0) {
                entropy -= p * Math.log(p);
            }
        }
        return 1.0 + alpha * entropy;
    }
}