package com.example.lms.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextRefinementMetadataTest {

    @Test
    void contextRefinementMetadataIsSeparateFromLearningSignalsAndSurvivesToBuilder() {
        Map<String, Double> signals = Map.of(
                "needleKeptRatio", 0.15d,
                "contextContamination", 0.44d,
                "cfvmBoltzmannWeight", 0.73d);

        PromptContext ctx = PromptContext.builder()
                .contextRefinementSummary("reason=selected,candidates=3,selected=ensemble_trace_best")
                .contextRefinementSignals(signals)
                .build();

        assertEquals("reason=selected,candidates=3,selected=ensemble_trace_best",
                ctx.contextRefinementSummary());
        assertEquals(signals, ctx.contextRefinementSignals());
        assertTrue(ctx.learningSignals().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.contextRefinementSignals().put("raw", 1.0d));

        PromptContext copied = ctx.toBuilder().build();

        assertEquals(ctx.contextRefinementSummary(), copied.contextRefinementSummary());
        assertEquals(ctx.contextRefinementSignals(), copied.contextRefinementSignals());
        assertTrue(copied.learningSignals().isEmpty());
    }
}
