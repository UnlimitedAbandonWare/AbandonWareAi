package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.rerank.ElementConstraintScorer;
import com.example.lms.service.rag.support.ContentCompat;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps ElementConstraintScorer to produce a normalized score map using the reciprocal rank formula.
 */
@Component("nsElem")
@ConditionalOnBean(ElementConstraintScorer.class)
@RequiredArgsConstructor
public class ElementConstraintNormalizedAdapter implements NormalizedScorer {

    private final ElementConstraintScorer elem;

    @Override
    public Map<String, Double> scoreMap(List<Content> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        // Use the element scorer to rescore (sort) the candidates
        List<Content> ranked;
        try {
            ranked = elem.rescore(query != null ? query : "", candidates);
        } catch (Exception ex) {
            ranked = candidates;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        int k = 60;
        for (int i = 0; i < ranked.size(); i++) {
            Content c = ranked.get(i);
            String id = ContentCompat.idOf(c);
            out.put(id, 1.0 / (k + (i + 1)));
        }
        return out;
    }
}