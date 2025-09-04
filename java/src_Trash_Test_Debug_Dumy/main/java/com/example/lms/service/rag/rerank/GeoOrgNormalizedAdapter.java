package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.rerank.GeoOrgBoostScorer;
import com.example.lms.service.rag.support.ContentCompat;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wraps GeoOrgBoostScorer to produce a normalized score map using a reciprocal rank formula.
 * Each document receives a score of 1/(k+rank) where rank is determined by the raw score ranking.
 */
@Component("nsGeo")
@ConditionalOnBean(GeoOrgBoostScorer.class)
@RequiredArgsConstructor
public class GeoOrgNormalizedAdapter implements NormalizedScorer {

    private final GeoOrgBoostScorer geo;

    @Override
    public Map<String, Double> scoreMap(List<Content> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        // Collect raw scores
        List<Map.Entry<String, Double>> list = new ArrayList<>();
        for (Content c : candidates) {
            String id = ContentCompat.idOf(c);
            double raw;
            try {
                raw = geo.boost(id, Optional.empty());
            } catch (Exception ex) {
                raw = 0.0;
            }
            list.add(Map.entry(id, raw));
        }
        // Sort descending by raw value
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        // Assign normalized scores using 1/(k+rank)
        int k = 60;
        Map<String, Double> out = new LinkedHashMap<>();
        int rank = 1;
        for (Map.Entry<String, Double> e : list) {
            out.put(e.getKey(), 1.0 / (k + rank));
            rank++;
        }
        return out;
    }
}