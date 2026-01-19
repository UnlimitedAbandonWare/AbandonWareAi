package com.example.rag.planner;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;



@Component
public class QueryComplexityClassifier {
    public enum Complexity { SIMPLE, COMPLEX, WEB_REQUIRED }

    public record Decision(
            Complexity level,
            boolean useWeb,
            boolean useVector,
            boolean officialSourcesOnly,
            int initialTopK,
            int rerankMinCandidates,
            double budgetShare
    ) { }

    public Decision evaluate(String query, QueryHints hints, RoutingThresholds th) {
        int tokens = roughTokenCount(query);
        boolean hasRecencyCue = containsAny(query, hints.recencyKeywords());
        boolean hasExactQuote = query != null && query.contains("\"");

        if (hasRecencyCue) {
            return new Decision(Complexity.WEB_REQUIRED, true, true, true,
                    th.topKWebRequired(), th.rerankMinCandidates(), th.budgetWeb());
        }
        if (tokens >= th.complexMinTokens() || hasExactQuote) {
            return new Decision(Complexity.COMPLEX, true, true, false,
                    th.topKComplex(), th.rerankMinCandidates(), th.budgetComplex());
        }
        return new Decision(Complexity.SIMPLE, false, true, false,
                th.topKSimple(), th.rerankMinCandidates(), th.budgetSimple());
    }

    private int roughTokenCount(String q){
        if (q == null || q.isBlank()) return 1;
        return Math.max(1, q.trim().split("\\s+").length);
    }
    private boolean containsAny(String q, List<String> keys){
        if (q == null) return false;
        String s = q.toLowerCase();
        for (String k: keys) {
            if (s.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    public record QueryHints(List<String> recencyKeywords, Instant now) { }
    public record RoutingThresholds(
            int complexMinTokens, int topKSimple, int topKComplex, int topKWebRequired,
            int rerankMinCandidates, double budgetSimple, double budgetComplex, double budgetWeb
    ) { }
}