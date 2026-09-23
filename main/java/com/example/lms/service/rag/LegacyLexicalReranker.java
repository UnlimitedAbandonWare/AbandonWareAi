package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Legacy lexical reranker based on token overlap (Dice coefficient).
 * <p>
 * 기존 SimpleReranker 의 구현을 그대로 보존하면서, Bean 이름을
 * "legacyLexicalReranker" 로 노출하여 HybridReranker 등에서 명시적으로
 * 참조할 수 있도록 한다.
 */
@Deprecated
@Component("legacyLexicalReranker")
public class LegacyLexicalReranker {

    /**
     * 후보 Content 리스트를 쿼리와의 토큰 중첩 점수를 기반으로 재순위화합니다.
     * 기존 자카드 유사도 대신 Dice 계수를 사용하며,
     * 너무 짧은 문서는 길이 페널티를 적용합니다.
     */
    public List<Content> rerank(String query, List<Content> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (limit <= 0) {
            return Collections.emptyList();
        }

        Set<String> queryTokens = tokenize(query);
        Map<Content, Double> scored = new HashMap<>();

        for (Content candidate : candidates) {
            String text = candidate.textSegment().text();
            Set<String> docTokens = tokenize(text);

            double overlapScore = calculateDice(queryTokens, docTokens);
            double lengthPenalty = (text != null && text.length() < 20) ? 0.5 : 1.0;

            scored.put(candidate, overlapScore * lengthPenalty);
        }

        return scored.entrySet().stream()
                .sorted(Map.Entry.<Content, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        String cleaned = text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-z가-힣\s]", " ");

        String[] raw = cleaned.split("\s+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : raw) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Dice Coefficient: 2 * |A ∩ B| / (|A| + |B|)
     */
    private double calculateDice(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        int denom = a.size() + b.size();
        if (denom == 0) {
            return 0.0;
        }
        return (2.0 * intersection.size()) / denom;
    }
}

