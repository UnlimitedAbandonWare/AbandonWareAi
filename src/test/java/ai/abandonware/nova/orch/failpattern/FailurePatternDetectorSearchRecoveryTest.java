package ai.abandonware.nova.orch.failpattern;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePatternDetectorSearchRecoveryTest {

    private final FailurePatternDetector detector = new FailurePatternDetector();

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void detectsZeroResultRecoveryLog() {
        FailurePatternMatch match = detector.detect(
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "[AWX2AF2][rag][starvation] zero_result_reason=zero_result fallback_path=selfask_queryburst_parallel_web_vector_memory_history");

        assertNotNull(match);
        assertEquals(FailurePatternKind.SEARCH_ZERO_RESULT, match.kind());
        assertEquals("rag", match.source());
        assertEquals("zero_result", match.key());
    }

    @Test
    void detectsAfterFilterStarvationRecoveryLog() {
        FailurePatternMatch match = detector.detect(
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "[AWX2AF2][rag][starvation] zero_result_reason=after_filter_starvation recoveredCount=2");

        assertNotNull(match);
        assertEquals(FailurePatternKind.SEARCH_AFTER_FILTER_STARVATION, match.kind());
        assertEquals("after_filter_starvation", match.key());
    }

    @Test
    void detectsProviderSpecificSearchRecoverySourceFromSafeProviderField() {
        FailurePatternMatch match = detector.detect(
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "[AWX2AF2][rag][starvation] zero_result_reason=zero_result provider=tavily recoveredCount=0");

        assertNotNull(match);
        assertEquals(FailurePatternKind.SEARCH_ZERO_RESULT, match.kind());
        assertEquals("tavily", match.source());
        assertEquals("zero_result", match.key());
    }

    @Test
    void detectsInsufficientEvidenceRecoveryLog() {
        FailurePatternMatch match = detector.detect(
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "[AWX2AF2][rag][starvation] zero_result_reason=insufficient_citations recoveredCount=0");

        assertNotNull(match);
        assertEquals(FailurePatternKind.EVIDENCE_INSUFFICIENT, match.kind());
        assertEquals("insufficient_citations", match.key());
    }

    @Test
    void detectorPublishesCfvmJbCbLissajousTraceWithoutRawLog() {
        String rawLog = "[AWX2AF2][rag][starvation] zero_result_reason=after_filter_starvation "
                + "ownerToken raw-private-token";

        FailurePatternMatch match = detector.detect(
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                rawLog);

        assertNotNull(match);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.detector.activated"));
        assertTrue(TraceStore.get("cfvm.jb.score") instanceof Double);
        assertTrue(TraceStore.get("cfvm.cb.score") instanceof Double);
        assertEquals("chain_breakdown", TraceStore.get("cfvm.lissajous.pattern"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-private-token"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawLog));
    }

    @Test
    void detectorPublishesInactiveTraceForUnmatchedMessage() {
        FailurePatternMatch match = detector.detect("demo.Logger", "normal informational line");

        assertNull(match);
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.detector.activated"));
        assertEquals(0.0d, (Double) TraceStore.get("cfvm.jb.score"), 1.0e-12d);
        assertEquals(0.0d, (Double) TraceStore.get("cfvm.cb.score"), 1.0e-12d);
        assertEquals("none", TraceStore.get("cfvm.lissajous.pattern"));
    }
}
