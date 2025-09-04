package com.example.lms.service.rag.tune;

import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;

/**
 * A simple heuristic implementation of {@link StrategyWeightTuner} that
 * adjusts the balance between web and vector retrieval based on the
 * characteristics of the query.  The following heuristics are applied:
 *
 * <ul>
 *   <li>If the query contains recency terms such as "latest", "today",
 *       "recent" or "news" then the web weight is increased.</li>
 *   <li>If the query mentions internal concepts or appears lengthy (more
 *       than ~30 characters) the vector weight is increased, as such
 *       queries often refer to structured knowledge or internal
 *       documents.</li>
 *   <li>Otherwise equal weights (0.5, 0.5) are suggested.</li>
 * </ul>
 */
@Component
public class DefaultStrategyWeightTuner implements StrategyWeightTuner {
    @Override
    public double[] tune(Query query, Metadata meta) {
        if (query == null || query.text() == null) {
            return null;
        }
        String text = query.text().toLowerCase();
        double webWeight = 0.5;
        double vecWeight = 0.5;
        // Recency keywords favour web
        if (containsAny(text, "latest", "today", "recent", "news", "current", "now")) {
            webWeight += 0.2;
            vecWeight -= 0.2;
        }
        // Internal or long queries favour vector
        if (text.contains("internal") || text.contains("document") || text.length() > 30) {
            vecWeight += 0.2;
            webWeight -= 0.2;
        }
        // Clamp to [0,1]
        webWeight = clamp(webWeight, 0.0, 1.0);
        vecWeight = clamp(vecWeight, 0.0, 1.0);
        return new double[]{webWeight, vecWeight};
    }

    private static boolean containsAny(String text, String token) {
    if (text == null || token == null || token.isBlank()) return false;
    return text.toLowerCase().contains(token.toLowerCase());
}
private static boolean containsAny(String text, String... tokens) {
    if (text == null) return false;
    String lc = text.toLowerCase();
    for (String t : tokens) {
        if (t != null && !t.isBlank() && lc.contains(t.toLowerCase())) return true;
    }
    return false;
}
private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}