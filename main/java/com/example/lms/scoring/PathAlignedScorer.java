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
        double ratio = match / (double) currentPath.size();
        // 0.5 base plus 8.5 multiplier (0.5-9.0 range)
        return 0.5 + 8.5 * ratio;
    }
}