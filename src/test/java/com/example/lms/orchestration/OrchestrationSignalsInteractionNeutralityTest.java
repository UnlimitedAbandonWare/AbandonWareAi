package com.example.lms.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;

class OrchestrationSignalsInteractionNeutralityTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void cooperationRudenessAndFailureWordsDoNotChangeRouting() {
        OrchestrationSignals baseline = compute("Summarize the supplied source.");
        List<String> socialOrToneVariants = List.of(
                "Please summarize the supplied source quickly.",
                "제발 빨리 제공된 출처를 요약해 주세요.",
                "You are useless; summarize the supplied source.",
                "The previous answer failed. Summarize the supplied source.",
                "I disagree with the source. Summarize it.");

        for (String request : socialOrToneVariants) {
            OrchestrationSignals actual = compute(request);
            assertEquals(baseline.modeLabel(), actual.modeLabel(), request);
            assertEquals(baseline.strikeMode(), actual.strikeMode(), request);
            assertEquals(baseline.compressionMode(), actual.compressionMode(), request);
            assertFalse(actual.reasons().stream().anyMatch(reason -> reason.contains("frustration")), request);
        }
    }

    private static OrchestrationSignals compute(String query) {
        return OrchestrationSignals.compute(query, null, GuardContext.defaultContext());
    }
}
