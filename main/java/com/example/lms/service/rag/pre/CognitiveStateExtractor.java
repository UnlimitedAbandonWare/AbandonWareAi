package com.example.lms.service.rag.pre;

import com.example.lms.common.InputTypeScope;
import com.example.lms.config.rag.RagCognitiveProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class CognitiveStateExtractor {

    @Autowired(required = false)
    private RagCognitiveProperties ragCognitiveProperties;

    @Autowired(required = false)
    private QueryComplexityGate queryComplexityGate;

    public CognitiveState extract(String query) {
        return extract(query, null);
    }

    public CognitiveState extract(String query, String sessionId) {
        if (ragCognitiveProperties != null && !ragCognitiveProperties.isEnabled()) {
            return null;
        }
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return null;
        }

        QueryComplexityGate.Level level = complexityLevel(normalized);
        CognitiveState.AbstractionLevel abstraction = abstractionLevel(normalized);
        CognitiveState.TemporalSensitivity temporal = temporalSensitivity(normalized);
        List<String> evidenceTypes = evidenceTypes(normalized);
        CognitiveState.ComplexityBudget budget = complexityBudget(level, normalized);
        String intent = inferIntent(normalized);
        String persona = persona(level, abstraction, intent);
        CognitiveState.ExecutionMode executionMode = executionMode(normalized);
        boolean voice = "voice".equalsIgnoreCase(InputTypeScope.current());

        return new CognitiveState(
                abstraction,
                temporal,
                List.copyOf(evidenceTypes),
                budget,
                voice,
                persona,
                executionMode);
    }

    private QueryComplexityGate.Level complexityLevel(String query) {
        if (queryComplexityGate == null) {
            return localComplexity(query);
        }
        try {
            return queryComplexityGate.assess(query);
        } catch (RuntimeException ex) {
            String errorType = SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
            TraceStore.put("query.cognitive.suppressed.stage", "complexityGate");
            TraceStore.put("query.cognitive.suppressed.errorType", errorType);
            TraceStore.put("query.cognitive.suppressed.complexityGate", true);
            TraceStore.put("query.cognitive.suppressed.complexityGate.errorType", errorType);
            TraceStore.put("query.cognitive.failureReason", errorType);
            TraceStore.put("query.cognitive.queryHash", SafeRedactor.hash12(query));
            TraceStore.put("query.cognitive.queryLength", query.length());
            return localComplexity(query);
        }
    }

    private static QueryComplexityGate.Level localComplexity(String query) {
        String lower = lower(query);
        if (containsAny(lower, " vs ", "versus", "compare", "comparison", "tradeoff")
                || query.length() > 42
                || query.chars().filter(ch -> ch == '?').count() > 1) {
            return QueryComplexityGate.Level.COMPLEX;
        }
        if (query.length() > 24 || containsAny(lower, "why", "how", "steps", "plan")) {
            return QueryComplexityGate.Level.AMBIGUOUS;
        }
        return QueryComplexityGate.Level.SIMPLE;
    }

    private static CognitiveState.AbstractionLevel abstractionLevel(String query) {
        String lower = lower(query);
        if (containsAny(lower, " vs ", "versus", "compare", "comparison", "difference", "tradeoff")) {
            return CognitiveState.AbstractionLevel.COMPARATIVE;
        }
        if (containsAny(lower, "how", "steps", "procedure", "setup", "configure", "install", "plan")) {
            return CognitiveState.AbstractionLevel.PROCEDURAL;
        }
        if (query.length() <= 24 || containsAny(lower, "who", "what", "where", "when")) {
            return CognitiveState.AbstractionLevel.FACTUAL;
        }
        return CognitiveState.AbstractionLevel.SUMMARY;
    }

    private static CognitiveState.TemporalSensitivity temporalSensitivity(String query) {
        String lower = lower(query);
        return containsAny(lower, "latest", "recent", "today", "current", "news", "update", "2026")
                ? CognitiveState.TemporalSensitivity.RECENT_REQUIRED
                : CognitiveState.TemporalSensitivity.IRRELEVANT;
    }

    private static List<String> evidenceTypes(String query) {
        String lower = lower(query);
        List<String> evidence = new ArrayList<>();
        if (containsAny(lower, "official", "documentation", "docs", "spec", "rfc")) {
            evidence.add("official-doc");
        }
        if (containsAny(lower, "metric", "benchmark", "stat", "number", "score")) {
            evidence.add("quantitative");
        }
        if (containsAny(lower, "review", "opinion", "case study", "example")) {
            evidence.add("field-report");
        }
        if (evidence.isEmpty()) {
            evidence.add("general-evidence");
        }
        return evidence;
    }

    private static CognitiveState.ComplexityBudget complexityBudget(QueryComplexityGate.Level level, String query) {
        if (level == QueryComplexityGate.Level.COMPLEX) {
            return CognitiveState.ComplexityBudget.HIGH;
        }
        if (level == QueryComplexityGate.Level.AMBIGUOUS || query.length() > 30) {
            return CognitiveState.ComplexityBudget.MEDIUM;
        }
        return CognitiveState.ComplexityBudget.LOW;
    }

    private static String persona(QueryComplexityGate.Level level,
                                  CognitiveState.AbstractionLevel abstraction,
                                  String intent) {
        if (level == QueryComplexityGate.Level.COMPLEX
                || abstraction == CognitiveState.AbstractionLevel.COMPARATIVE) {
            return "analyzer";
        }
        if ("RECOMMENDATION".equals(intent) || "PAIRING".equals(intent)) {
            return "tutor";
        }
        return "tutor";
    }

    private static CognitiveState.ExecutionMode executionMode(String query) {
        String lower = lower(query);
        return containsAny(lower,
                "academy",
                "curriculum",
                "course",
                "education",
                "subsidy",
                "scholarship",
                "embedding",
                "vector")
                ? CognitiveState.ExecutionMode.VECTOR_SEARCH
                : CognitiveState.ExecutionMode.KEYWORD_SEARCH;
    }

    private static String inferIntent(String query) {
        String lower = lower(query);
        if (containsAny(lower, " vs ", "versus", "compare", "pairing", "fit", "compatible")) {
            return "PAIRING";
        }
        if (containsAny(lower, "recommend", "suggest", "best", "which should")) {
            return "RECOMMENDATION";
        }
        return "GENERAL";
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
