package com.example.lms.service.rag.pre;

import java.util.List;

public record CognitiveState(
        AbstractionLevel abstractionLevel,
        TemporalSensitivity temporalSensitivity,
        List<String> evidenceTypes,
        ComplexityBudget complexityBudget,
        boolean voiceInput,
        String persona,
        ExecutionMode executionMode
) {
    public enum AbstractionLevel { SUMMARY, PROCEDURAL, FACTUAL, COMPARATIVE }

    public enum TemporalSensitivity { RECENT_REQUIRED, HISTORICAL, IRRELEVANT }

    public enum ComplexityBudget { LOW, MEDIUM, HIGH }

    public enum ExecutionMode { KEYWORD_SEARCH, VECTOR_SEARCH }
}
