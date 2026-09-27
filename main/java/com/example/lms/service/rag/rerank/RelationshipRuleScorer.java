// src/main/java/com/example/lms/service/rag/rerank/RelationshipRuleScorer.java
package com.example.lms.service.rag.rerank;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;




/**
 * RELATIONSHIP_* 규칙과 문서 텍스트를 비교하여 점수 델타를 계산합니다.
 * - 규칙별 기본 가중치 적용, 과도한 가산에 상한.
 */
@Component
public class RelationshipRuleScorer {

    private static final Map<String, Double> BASE_WEIGHT = Map.of(
            "RELATIONSHIP_CONTAINS", 0.10,
            "RELATIONSHIP_HAS_COMPONENT", 0.08,
            "RELATIONSHIP_IS_PART_OF", 0.06,
            "RELATIONSHIP_ASSOCIATED_WITH", 0.05,
            "RELATIONSHIP_HAS_SYNERGY_WITH", 0.12 // ⬅️ 이 규칙이 추가되었습니다.
    );

    private static final double MAX_DELTA_PER_RULE = 0.20;
    private static final double MAX_TOTAL_DELTA = 0.40;

    public double deltaForText(String text, Map<String, Set<String>> rules) {
        if (text == null || text.isBlank() || rules == null || rules.isEmpty()) return 0.0;
        String s = text.toLowerCase(Locale.ROOT);

        double total = 0.0;
        for (Map.Entry<String, Set<String>> e : rules.entrySet()) {
            String rule = e.getKey();
            Set<String> targets = e.getValue();
            if (targets == null || targets.isEmpty()) continue;

            double w = BASE_WEIGHT.getOrDefault(rule, 0.04);
            double hit = 0.0;

            for (String t : targets) {
                if (t == null || t.isBlank()) continue;
                String needle = t.toLowerCase(Locale.ROOT);
                if (containsTokenLike(s, needle)) {
                    hit += w;
                    if (hit >= MAX_DELTA_PER_RULE) break;
                }
            }
            total += Math.min(hit, MAX_DELTA_PER_RULE);
            if (total >= MAX_TOTAL_DELTA) break;
        }
        return total;
    }

    private static boolean containsTokenLike(String hay, String needle) {
        String pat = "\\b" + Pattern.quote(needle) + "\\b";
        return Pattern.compile(pat).matcher(hay).find();
    }
}