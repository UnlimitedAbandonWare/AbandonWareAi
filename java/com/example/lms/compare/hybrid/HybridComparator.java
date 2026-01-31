package com.example.lms.compare.hybrid;

import com.example.lms.compare.api.ComparatorCalculator;
import com.example.lms.compare.common.CompareResult;
import com.example.lms.compare.state.CompareState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;




/**
 * Default implementation of {@link ComparatorCalculator} that combines
 * multiple heuristics to rank entities. This simplistic version computes
 * a score for each entity based on the relative position in the input list
 * and the presence of optional weights. The implementation is deliberately
 * lightweight; production deployments are expected to replace or extend
 * this class with more sophisticated vector similarity, team embedding,
 * cross-encoder and LLM scoring pipelines.
 */
public class HybridComparator implements ComparatorCalculator {

    @Override
    public CompareResult compute(CompareState state) {
        // Handle null or empty state gracefully
        List<String> entities = state.entities() != null ? state.entities() : Collections.emptyList();
        Map<String, Double> weights = state.weights() != null ? state.weights() : Collections.emptyMap();

        // Very naive scoring: assign descending scores based on list order.
        // If weights are provided, adjust scores proportionally to the sum
        // of weight values to illustrate how weights might influence ranking.
        double weightSum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (weightSum <= 0) {
            weightSum = 1.0; // avoid division by zero
        }
        List<CompareResult.ScoredEntity> ranked = new ArrayList<>();
        int n = entities.size();
        for (int i = 0; i < n; i++) {
            String name = entities.get(i);
            // simple score: higher for earlier entities
            double baseScore = (double) (n - i) / n;
            double weightedScore = baseScore * weightSum;
            ranked.add(new CompareResult.ScoredEntity(name, name, weightedScore, Collections.emptyList()));
        }
        // sort descending by score just in case
        ranked.sort(Comparator.comparingDouble(CompareResult.ScoredEntity::score).reversed());
        return new CompareResult(ranked, Collections.emptyMap());
    }
}