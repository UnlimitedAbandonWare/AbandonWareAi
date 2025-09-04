package com.example.lms.scoring;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes how well a predicted decision path aligns with previously
 * observed paths. The result is returned as a multiplier where 0.5 means
 * strong misalignment (penalty) and 9.0 means perfect alignment providing
 * up to a ninefold probability boost. When no history is available the
 * multiplier defaults to 1.0.
 */
@Component
public class PathAlignedScorer {

    /**
     * Compute a multiplier describing the alignment of two paths. The current
     * path is compared to the tail of the past path. If the past path has
     * fewer elements than the current path it will be aligned with the
     * suffix of equal length. The multiplier ranges from 0.5 (no alignment)
     * to 9.0 (perfect alignment).
     *
     * @param pastPath    historical path sequence from memory/learning modules; may be null
     * @param currentPath path predicted for the current turn; may be null
     * @return multiplier in range [0.5, 9.0], defaults to 1.0 when either list is null or empty
     */
    public double score(List<String> pastPath, List<String> currentPath) {
        if (currentPath == null || currentPath.isEmpty()) {
            return 1.0;
        }
        if (pastPath == null || pastPath.isEmpty()) {
            return 1.0;
        }
        int max = Math.min(pastPath.size(), currentPath.size());
        int match = 0;
        // Align the suffix of the past path to the current path prefix
        for (int i = 0; i < max; i++) {
            String pastStep = pastPath.get(pastPath.size() - max + i);
            String currStep = currentPath.get(i);
            if (pastStep.equals(currStep)) {
                match++;
            } else {
                break;
            }
        }
        // Ratio of matched steps relative to current path length
        double suffixRatio = match / (double) Math.max(1, currentPath.size());
        // Build transition count matrix for the past path
        double alpha = 0.1;
        java.util.Map<String, java.util.Map<String, Integer>> counts = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pastPath.size(); i++) {
            String from = pastPath.get(i);
            String to = pastPath.get(i + 1);
            counts.computeIfAbsent(from, k -> new java.util.HashMap<>()).merge(to, 1, Integer::sum);
        }
        // Compute average transition probability of the current path steps
        double logLik = 0.0;
        int steps = 0;
        for (int i = 0; i + 1 < currentPath.size(); i++) {
            String from = currentPath.get(i);
            String to = currentPath.get(i + 1);
            var row = counts.getOrDefault(from, java.util.Collections.emptyMap());
            int tot = row.values().stream().mapToInt(Integer::intValue).sum();
            int cnt = row.getOrDefault(to, 0);
            double prob = (cnt + alpha) / (Math.max(1, tot) + alpha * Math.max(1, row.size()));
            logLik += Math.log(prob);
            steps++;
        }
        double avgProb = (steps == 0) ? 1.0 : Math.exp(logLik / steps);
        // Blend suffix alignment and Markov transition probability equally
        double blended = 0.5 * suffixRatio + 0.5 * avgProb;
        // 0.5 base plus 8.5 multiplier within [0.5, 9.0]
        return 0.5 + 8.5 * Math.max(0.0, Math.min(1.0, blended));
    }
}