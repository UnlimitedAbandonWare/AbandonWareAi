package com.example.lms.service;

import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.TrainingSampleRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.util.TokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FineTuningServiceDisabledTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void directStartLeavesProviderDisabledTraceWithoutTouchingRepositories() throws Exception {
        TrainingSampleRepository trainingRepo = mock(TrainingSampleRepository.class);
        CurrentModelRepository modelRepo = mock(CurrentModelRepository.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        FineTuningService service = new FineTuningService(
                trainingRepo,
                new ObjectMapper(),
                tokenCounter,
                modelRepo);

        assertNull(service.startFineTuningJob(fineTuningOptions()));
        assertEquals(Boolean.TRUE, TraceStore.get("fineTuning.providerDisabled"));
        assertEquals("remote_fine_tuning_endpoint_unavailable", TraceStore.get("fineTuning.disabledReason"));
        assertEquals("remote_fine_tuning_endpoint_unavailable", TraceStore.get("fineTuning.skipped.reason"));
        verifyNoInteractions(trainingRepo, modelRepo, tokenCounter);
    }

    private static FineTuningOptionsDto fineTuningOptions() {
        return new FineTuningOptionsDto(
                0.8,
                0.2,
                3,
                42L,
                Optional.empty(),
                Optional.empty(),
                new FineTuningOptionsDto.QualityWeightingDto(1.0, 0.0));
    }
}
