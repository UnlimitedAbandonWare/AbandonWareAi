package com.abandonware.ai.agent.integrations.service.route;


import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.route.QueryComplexityClassifier
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.route.QueryComplexityClassifier
role: config
*/
public class QueryComplexityClassifier {
    public enum Level { LOW, MEDIUM, HIGH }
    public Level classify(String query){
        if (query == null) return Level.LOW;
        int tokens = query.trim().split("\\s+").length;
        String q = query.toLowerCase(Locale.ROOT);
        int score = 0;
        if (tokens > 8) score++;
        if (q.matches(".*\\b(최신|news|prices?|policy|법|규정|standard|오늘|today|어제|yesterday|변경)\\b.*")) score++;
        if (q.contains("?") || q.contains("어떻게")) score++;
        if (q.contains("비교") || q.contains("tradeoff")) score++;
        if (score >= 3) return Level.HIGH;
        if (score == 2) return Level.MEDIUM;
        return Level.LOW;
    }
}