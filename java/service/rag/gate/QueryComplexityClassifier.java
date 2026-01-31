// src/main/java/service/rag/gate/QueryComplexityClassifier.java
package service.rag.gate;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class QueryComplexityClassifier {
    private static final Set<String> RECENCY_TOKENS = Set.of(
        "최신", "최근", "today", "이번주", "이번 달", "2024", "2025", "today?", "latest", "지금", "방금"
    );
    private static final Set<String> WEB_HINTS = Set.of(
        "뉴스", "주가", "날씨", "일정", "일정표", "발표", "공시", "release", "breaking"
    );
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    public ComplexityDecision classify(String q, Set<String> domainTags) {
        String query = Optional.ofNullable(q).orElse("").trim();
        if (query.isEmpty()) {
            return new ComplexityDecision(ComplexityDecision.Complexity.SIMPLE, 0.0, false, false, Map.of());
        }

        int len = query.codePointCount(0, query.length());
        long punc = query.chars().filter(ch -> "?:!\"'()[]{}".indexOf(ch) >= 0).count();
        long cap = query.chars().filter(Character::isUpperCase).count();

        double recency = containsAny(query, RECENCY_TOKENS) || YEAR.matcher(query).find() ? 1.0 : 0.0;
        double webness = containsAny(query, WEB_HINTS) || URL.matcher(query).find() ? 1.0 : 0.0;

        // 간단한 복합도: 길이, 구두점, 대문자 비율
        double complexityScore = clamp((len / 64.0) + (punc / 4.0) + (cap / 32.0), 0, 1);
        boolean domainKnown = domainTags != null && !domainTags.isEmpty();

        ComplexityDecision.Complexity level;
        if (webness > 0.0 || recency > 0.0)      level = ComplexityDecision.Complexity.NEEDS_WEB;
        else if (complexityScore > 0.45)         level = ComplexityDecision.Complexity.COMPLEX;
        else                                     level = ComplexityDecision.Complexity.SIMPLE;

        Map<String, Double> feats = new LinkedHashMap<>();
        feats.put("lenNorm", Math.min(1.0, len / 64.0));
        feats.put("punc", (double)punc);
        feats.put("capNorm", Math.min(1.0, cap / 32.0));
        feats.put("recency", recency);
        feats.put("webness", webness);

        return new ComplexityDecision(level, complexityScore, recency > 0, domainKnown, feats);
    }

    private static boolean containsAny(String q, Set<String> tokens) {
        String lower = q.toLowerCase(Locale.ROOT);
        for (String t : tokens) if (lower.contains(t.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}