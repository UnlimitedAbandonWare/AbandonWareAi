package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import com.example.lms.service.rag.support.ContentCompat;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wraps RelationshipRuleScorer to produce a normalized score map using reciprocal rank.
 */
@Component("nsRel")
@ConditionalOnBean(RelationshipRuleScorer.class)
@RequiredArgsConstructor
public class RelationshipRuleNormalizedAdapter implements NormalizedScorer {

    private final RelationshipRuleScorer rel;

    private record Pair(String id, double raw) {}

    @Override
    public Map<String, Double> scoreMap(List<Content> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<Pair> list = new ArrayList<>();
        for (Content c : candidates) {
            String id = ContentCompat.idOf(c);
            double raw;
            try {
                raw = rel.deltaForText(ContentCompat.textOf(c), Map.of());
            } catch (Exception ex) {
                raw = 0.0;
            }
            list.add(new Pair(id, raw));
        }
        // sort descending by raw score
        list.sort((a, b) -> Double.compare(b.raw(), a.raw()));
        Map<String, Double> out = new LinkedHashMap<>();
        int k = 60;
        for (int i = 0; i < list.size(); i++) {
            Pair p = list.get(i);
            out.put(p.toString(), 1.0 / (k + (i + 1)));
        }
        return out;
    }
}