package com.example.lms.service.rag.rerank;

import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.rag.support.ContentCompat;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates normalized scores from multiple {@link NormalizedScorer} instances and computes a weighted sum.
 * Each scorer contributes according to a hyperparameter weight (w_geo, w_elem, w_rel) in HyperparameterService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(HyperparameterService.class)
public class WeightedSumRanker {

    /** Name of the normalized scorers; Spring will inject a map keyed by bean name. */
    private final Map<String, NormalizedScorer> ns;
    private final HyperparameterService hp;

    /**
     * Rank documents using weighted sum of normalized scores plus a seed RRF-based rank.
     *
     * @param input candidates to rank
     * @param query query string
     * @param topK number of top documents to return
     * @return ranked list of contents
     */
    public List<Content> rank(List<Content> input, String query, int topK) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        // 1) Acquire score maps from all normalized scorers
        Map<String, Map<String, Double>> maps = new LinkedHashMap<>();
        for (Map.Entry<String, NormalizedScorer> entry : ns.entrySet()) {
            String name = entry.getKey();
            try {
                Map<String, Double> m = entry.getValue().scoreMap(input, query);
                if (m != null && !m.isEmpty()) {
                    maps.put(name, m);
                }
            } catch (Exception ex) {
                // ignore
            }
        }
        // 2) Retrieve weights for scorers (default 1.0)
        double wGeo = hp.getDoubleInRange01("w_geo", 1.0);
        double wElem = hp.getDoubleInRange01("w_elem", 1.0);
        double wRel = hp.getDoubleInRange01("w_rel", 1.0);
        Map<String, Double> weights = new java.util.HashMap<>();
        weights.put("nsGeo", wGeo);
        weights.put("nsElem", wElem);
        weights.put("nsRel", wRel);
        // Bean 이름이 nsFoo면 w_foo 하이퍼파라미터로 가중치 주입
        for (String name : maps.keySet()) {
            String suffix = name.startsWith("ns") ? name.substring(2) : name;
            double w = hp.getDoubleInRange01(("w_" + suffix).toLowerCase(), 1.0);
            weights.putIfAbsent(name, w);
        }
        // 3) Initialize final score with seed RRF 1/(k + rank)
        int k = 60;
        Map<String, Double> finalScore = new LinkedHashMap<>();
        for (int i = 0; i < input.size(); i++) {
            String id = ContentCompat.idOf(input.get(i));
            finalScore.put(id, 1.0 / (k + (i + 1)));
        }
        // 4) Sum weighted normalized scores
        maps.forEach((name, m) -> {
            double w = weights.getOrDefault(name, 1.0);
            m.forEach((id, v) -> finalScore.merge(id, w * v, Double::sum));
        });
        // 5) Sort by aggregated score and pick topK
        Map<String, Content> byId = input.stream().collect(Collectors.toMap(ContentCompat::idOf, c -> c, (a, b) -> a, LinkedHashMap::new));
        List<Content> sorted = finalScore.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(e -> byId.get(e.getKey()))
                .filter(Objects::nonNull)
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
        // Logging for observability
        log.debug("[MLA-Jammini] fused top={} weights={}", sorted.size(), weights);
        return sorted;
    }
}