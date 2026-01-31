package com.abandonware.ai.addons.complexity;

import java.util.*;
import java.util.regex.Pattern;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.complexity.QueryComplexityClassifier
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.complexity.QueryComplexityClassifier
role: config
*/
public class QueryComplexityClassifier {

    private static final Set<String> WEB_KEYWORDS = Set.of(
            "latest","today","breaking","price","가격","시세","환율","법","규정","뉴스","발표","업데이트","릴리즈","버전"
    );
    private static final Set<String> COMPLEX_KEYWORDS = Set.of(
            "비교","compare","장단점","trade-off","정의","근거","출처","how","guide","step","architecture","설계"
    );
    private static final Pattern ENTITY_LIKE = Pattern.compile("[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*|[가-힣]{2,}");

    public ComplexityResult classify(String query, Locale locale) {
        if (query == null) query = "";
        String q = query.trim();
        int len = q.codePointCount(0, q.length());
        int tokens = Math.max(1, q.split("\\s+").length);
        int entityMatches = (int) ENTITY_LIKE.matcher(q).results().count();
        boolean hasWeb = containsAnyIgnoreCase(q, WEB_KEYWORDS);
        boolean hasComplex = containsAnyIgnoreCase(q, COMPLEX_KEYWORDS);
        boolean hasQuestionMark = q.contains("?");

        double score = 0.0;
        score += Math.min(tokens / 8.0, 1.0) * 0.25;
        score += Math.min(entityMatches / 5.0, 1.0) * 0.20;
        score += (hasComplex ? 0.25 : 0.0);
        score += (hasWeb ? 0.25 : 0.0);
        score += (hasQuestionMark ? 0.05 : 0.0);

        ComplexityTag tag;
        if (hasWeb) tag = ComplexityTag.WEB_REQUIRED;
        else if (hasComplex || tokens >= 14) tag = ComplexityTag.COMPLEX;
        else if (entityMatches >= 3) tag = ComplexityTag.DOMAIN_SPECIFIC;
        else tag = ComplexityTag.SIMPLE;

        Map<String,Object> feats = new HashMap<>();
        feats.put("tokens", tokens);
        feats.put("entities", entityMatches);
        feats.put("hasWeb", hasWeb);
        feats.put("hasComplex", hasComplex);
        feats.put("question", hasQuestionMark);
        return new ComplexityResult(tag, clamp(score, 0.0, 1.0), Collections.unmodifiableMap(feats));
    }

    private static boolean containsAnyIgnoreCase(String s, Set<String> keys) {
        String l = s.toLowerCase(Locale.ROOT);
        for (String k : keys) if (l.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}