package com.example.lms.service.rag.pre;

import com.example.lms.common.InputTypeScope;
import com.example.lms.config.rag.RagCognitiveProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryComplexityGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveStateExtractorHeuristicTest {

    @AfterEach
    void clearInputType() {
        InputTypeScope.clear();
        TraceStore.clear();
    }

    @Test
    void disabledCognitiveExtractionStillReturnsNull() {
        CognitiveStateExtractor extractor = new CognitiveStateExtractor();
        RagCognitiveProperties props = new RagCognitiveProperties();
        props.setEnabled(false);
        ReflectionTestUtils.setField(extractor, "ragCognitiveProperties", props);

        assertNull(extractor.extract("compare naver vs brave latest documentation"));
    }

    @Test
    void heuristicExtractionReturnsStateWithoutLlmCall() {
        CognitiveStateExtractor extractor = new CognitiveStateExtractor();
        ReflectionTestUtils.setField(extractor, "ragCognitiveProperties", new RagCognitiveProperties());

        CognitiveState state = extractor.extract("compare naver vs brave latest official documentation");

        assertNotNull(state);
        assertEquals(CognitiveState.AbstractionLevel.COMPARATIVE, state.abstractionLevel());
        assertEquals(CognitiveState.TemporalSensitivity.RECENT_REQUIRED, state.temporalSensitivity());
        assertEquals(CognitiveState.ComplexityBudget.HIGH, state.complexityBudget());
        assertEquals("analyzer", state.persona());
        assertEquals(CognitiveState.ExecutionMode.KEYWORD_SEARCH, state.executionMode());
        assertTrue(state.evidenceTypes().contains("official-doc"));
    }

    @Test
    void heuristicExtractionPreservesVoiceAndEducationVectorMode() {
        CognitiveStateExtractor extractor = new CognitiveStateExtractor();
        ReflectionTestUtils.setField(extractor, "ragCognitiveProperties", new RagCognitiveProperties());
        InputTypeScope.enter("voice");

        CognitiveState state = extractor.extract("academy subsidy curriculum steps");

        assertNotNull(state);
        assertTrue(state.voiceInput());
        assertEquals(CognitiveState.AbstractionLevel.PROCEDURAL, state.abstractionLevel());
        assertEquals(CognitiveState.ExecutionMode.VECTOR_SEARCH, state.executionMode());
    }

    @Test
    void complexityGateFailureFallsBackWithRedactedErrorType() {
        CognitiveStateExtractor extractor = new CognitiveStateExtractor();
        ReflectionTestUtils.setField(extractor, "ragCognitiveProperties", new RagCognitiveProperties());
        ReflectionTestUtils.setField(extractor, "queryComplexityGate", new QueryComplexityGate() {
            @Override
            public Level assess(String q) {
                throw new IllegalStateException("private query ownerToken=raw-secret");
            }
        });
        String rawQuery = "compare naver vs brave latest official documentation";

        CognitiveState state = extractor.extract(rawQuery);

        assertNotNull(state);
        assertEquals(Boolean.TRUE, TraceStore.get("query.cognitive.suppressed.complexityGate"));
        assertEquals("IllegalStateException", TraceStore.get("query.cognitive.suppressed.complexityGate.errorType"));
        assertEquals("complexityGate", TraceStore.get("query.cognitive.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("query.cognitive.suppressed.errorType"));
        assertEquals(rawQuery.length(), TraceStore.get("query.cognitive.queryLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }
}
