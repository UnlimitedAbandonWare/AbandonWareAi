package com.example.lms.service.rag.pre;

import java.util.List;

/** 인지 상태 DTO (PromptContext에 내장) */
public record CognitiveState(
        AbstractionLevel abstractionLevel,
        TemporalSensitivity temporalSensitivity,
        List<String> evidenceTypes,     // 예: ["공식 문서", "기술 사양"]
        ComplexityBudget complexityBudget,
        boolean voiceInput,
        /**
         * The chosen persona for this query.  Personas guide the tone and
         * style of the assistant's reply (e.g. "tutor", "analyzer", "brainstormer").
         * This value is determined by {@link CognitiveStateExtractor} based on
         * query complexity and intent heuristics.  It may be {@code null} if no
         * persona could be determined.
         */
        String persona
) {
    public enum AbstractionLevel { SUMMARY, PROCEDURAL, FACTUAL, COMPARATIVE }
    public enum TemporalSensitivity { RECENT_REQUIRED, HISTORICAL, IRRELEVANT }
    public enum ComplexityBudget { LOW, MEDIUM, HIGH }
}
