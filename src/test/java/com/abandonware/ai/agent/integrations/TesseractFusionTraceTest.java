package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TesseractFusionTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidNumericSignalsLeaveTypeOnlyTraceAndStillRerank() {
        TesseractFusion fusion = new TesseractFusion();
        fusion.setMode(TesseractFusion.Mode.GREEDY);
        Map<String, Object> item = Map.of(
                "id", "doc-1",
                "score", "raw private score token",
                "ts", "raw private timestamp token",
                "snippet", "needle evidence text",
                "source", "local");

        List<Map<String, Object>> out = fusion.rerank("needle", List.of(item), 1, "local", null);

        assertEquals("doc-1", out.get(0).get("id"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.tesseract.numeric.suppressed"));
        assertEquals("timestamp.parseFallback", TraceStore.get("agent.tesseract.numeric.suppressed.stage"));
        assertEquals(1L, TraceStore.get("agent.tesseract.numeric.score.parseFallback.count"));
        assertEquals("invalid_number", TraceStore.get("agent.tesseract.numeric.score.parseFallback.errorType"));
        assertEquals(1L, TraceStore.get("agent.tesseract.numeric.timestamp.parseFallback.count"));
        assertEquals("invalid_number", TraceStore.get("agent.tesseract.numeric.timestamp.parseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private score token"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private timestamp token"));
    }
}
