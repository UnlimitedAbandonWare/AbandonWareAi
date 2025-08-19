package com.example.lms.scoring;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes how well a predicted decision path aligns with previously
 * observed paths. The result is returned as a multiplier where 0.5 means
 * strong misalignment (penalty) and 9.0 means perfect alignment providing
 * up to a ninefold probability boost.
 */
@Component
public class PathAlignedScorer {

    /**
     * @param pastPath     historical path sequence from memory/learning modules
     * @param currentPath  path predicted for the current turn
     * @return multiplier in range [0.5, 9.0]
     */
    public double score(List<String> pastPath, List<String> currentPath) {
        if (currentPath == null || currentPath.isEmpty()) return 1.0;
        if (pastPath == null || pastPath.isEmpty()) return 1.0;
        int max = Math.min(pastPath.size(), currentPath.size());
        int match = 0;
        for (int i = 0; i < max; i++) {
            String pastStep = pastPath.get(pastPath.size() - max + i);
            String currStep = currentPath.get(i);
            if (pastStep.equals(currStep)) {
                match++;
            } else {
                break;
            }
        }
        double ratio = match / (double) currentPath.size();
        return 0.5 + 8.5 * ratio;
    }
}

