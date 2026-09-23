package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DefaultLightWeightRanker implements LightWeightRanker {

    @Override
    public List<Content> rank(List<Content> candidates, String query, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> qTokens = tokens(query);
        record Scored(Content content, double score) {}

        List<Scored> scored = new ArrayList<>();
        int position = 0;
        for (Content candidate : candidates) {
            String text = candidate.textSegment() != null
                    ? candidate.textSegment().text()
                    : String.valueOf(candidate);
            Set<String> candidateTokens = tokens(text);
            if (qTokens.isEmpty()) {
                scored.add(new Scored(candidate, 1.0 / (++position)));
                continue;
            }
            long matches = candidateTokens.stream().filter(qTokens::contains).count();
            if (matches == 0) {
                continue;
            }
            scored.add(new Scored(candidate, matches / (double) (qTokens.size() + 5.0)));
        }

        int safeLimit = Math.max(1, limit);
        if (scored.isEmpty()) {
            return new ArrayList<>(candidates).subList(0, Math.min(safeLimit, candidates.size()));
        }
        scored.sort((left, right) -> Double.compare(right.score(), left.score()));
        return scored.stream()
                .map(Scored::content)
                .limit(safeLimit)
                .collect(Collectors.toList());
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        String[] raw = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .split("\\s+");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : raw) {
            if (token.length() > 1) {
                result.add(token);
            }
        }
        return result;
    }
}
