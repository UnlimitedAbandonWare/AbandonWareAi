package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.service.MemoryReinforcementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LearningWriteInterceptorTest {

    private final GeminiCurationService curationService = mock(GeminiCurationService.class);
    private final MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
    private final LearningWriteInterceptor interceptor = new LearningWriteInterceptor(curationService, memoryService);

    @Test
    void skipsNonFiniteScores() {
        ReflectionTestUtils.setField(interceptor, "minScore", 0.75d);

        for (double score : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            interceptor.ingest("session", "query", "answer", score);
        }

        verifyNoInteractions(curationService, memoryService);
    }

    @Test
    void preservesFiniteThresholdBehavior() {
        ReflectionTestUtils.setField(interceptor, "minScore", 0.75d);

        interceptor.ingest("session", "query", "answer", 0.74d);

        verifyNoInteractions(curationService, memoryService);
        reset(curationService, memoryService);

        interceptor.ingest("session", "query", "answer", 0.75d);

        verify(curationService).ingest(any(LearningEvent.class));
        verify(memoryService).reinforceWithSnippet(
                eq("session"), eq("query"), eq("answer"), eq("ASSISTANT"), eq(0.75d));
    }
}
