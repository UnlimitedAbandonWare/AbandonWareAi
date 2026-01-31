package com.example.lms.service.rag.pre;

import java.util.List;



/** 인지 상태 DTO (PromptContext에 내장) */
/**
 * Data transfer object capturing high-level attributes about the user's query.  In
 * addition to summarising the abstraction level, temporal sensitivity and
 * evidence preferences, this record now encodes an {@link ExecutionMode}.  The
 * execution mode indicates which retrieval pipeline should be used downstream.
 * A value of {@link ExecutionMode#KEYWORD_SEARCH} means the standard
 * keyword/web-search pipeline is appropriate.  A value of
 * {@link ExecutionMode#VECTOR_SEARCH} signals that the query should be
 * handled purely via a vector search against the embedding store.  See
 * {@link CognitiveStateExtractor} for the logic that sets this flag.
 */
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
        String persona,
        /**
         * Execution mode for the retrieval layer.  When set to
         * {@link ExecutionMode#VECTOR_SEARCH} the downstream retrieval should
         * bypass web/keyword search entirely and instead query the vector store.
         * See {@link CognitiveStateExtractor} for the detection logic.
         */
        ExecutionMode executionMode
) {
    public enum AbstractionLevel { SUMMARY, PROCEDURAL, FACTUAL, COMPARATIVE }
    public enum TemporalSensitivity { RECENT_REQUIRED, HISTORICAL, IRRELEVANT }
    public enum ComplexityBudget { LOW, MEDIUM, HIGH }

    /**
     * Enumeration of retrieval execution modes.  The default value is
     * {@link #KEYWORD_SEARCH}.  When {@link #VECTOR_SEARCH} is selected the
     * retrieval pipeline will short-circuit to a purely vector similarity
     * lookup.
     */
    public enum ExecutionMode {
        /**
         * Standard mode: the pipeline performs keyword/web search followed by
         * optional vector fallback.  This is the default unless certain
         * education keywords are detected.
         */
        KEYWORD_SEARCH,
        /**
         * Vector-only mode: keyword/web search is bypassed and the query is
         * embedded directly for vector retrieval.
         */
        VECTOR_SEARCH
    }
}