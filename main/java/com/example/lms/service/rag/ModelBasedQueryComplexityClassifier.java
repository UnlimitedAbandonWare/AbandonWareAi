package com.example.lms.service.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ModelBasedQueryComplexityClassifier implements QueryComplexityClassifier {

    private static final Logger log = LoggerFactory.getLogger(ModelBasedQueryComplexityClassifier.class);
    private static final Pattern DIGIT = Pattern.compile("\\d");

    @Value("${rag.queryComplexity.model.path:}")
    private String modelPath;

    private volatile boolean modelUnavailable;

    @PostConstruct
    void init() {
        // This implementation has no inference session. A file is not a loaded model.
        modelUnavailable = true;
        if (modelPath != null && !modelPath.isBlank())
            log.info("[AWX][rag][query-complexity] model disabled reason=inference_not_implemented");
    }

    @Override
    public QueryComplexityGate.Level classify(String query) {
        String s = query == null ? "" : query.trim();
        if (s.isBlank()) {
            return QueryComplexityGate.Level.SIMPLE;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        int tokenCount = s.split("\\s+").length;
        boolean hasLogic = lower.contains(" and ") || lower.contains(" or ")
                || lower.contains(" vs ") || lower.contains("&&") || lower.contains("||");
        boolean hasDigits = DIGIT.matcher(s).find();
        boolean hasMultipleClauses = s.split("[.!?;:/]+").length > 2;

        int score = 0;
        if (tokenCount > 24 || s.length() > 180) score++;
        if (hasLogic) score++;
        if (hasDigits) score++;
        if (hasMultipleClauses) score++;
        if (lower.contains("compare") || lower.contains("analyze") || lower.contains("explain")) score++;
        if (modelUnavailable) {
            log.debug("[AWX][rag][query-complexity] reason=heuristic_only");
        }

        if (score >= 3) {
            return QueryComplexityGate.Level.COMPLEX;
        }
        return QueryComplexityGate.Level.AMBIGUOUS;
    }
}
