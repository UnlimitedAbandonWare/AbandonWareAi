package com.example.lms.api;

import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.FineTuningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerFineTuningDisabledTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void startFineTuningReturnsDisabledReasonWithoutCreatingJobWhenRemoteEndpointUnavailable() throws Exception {
        FineTuningService fineTuningService = mock(FineTuningService.class);
        when(fineTuningService.isRemoteFineTuningDisabled()).thenReturn(true);
        when(fineTuningService.remoteDisabledReason()).thenReturn("remote_fine_tuning_endpoint_unavailable");
        AdminController controller = new AdminController(fineTuningService);

        ResponseEntity<?> response = controller.startFineTuning(fineTuningOptions());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("fine_tuning_provider_disabled", body.get("error"));
        assertEquals("remote_fine_tuning_endpoint_unavailable", body.get("disabledReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("fineTuning.providerDisabled"));
        assertEquals("remote_fine_tuning_endpoint_unavailable", TraceStore.get("fineTuning.disabledReason"));
        assertEquals("remote_fine_tuning_endpoint_unavailable", TraceStore.get("fineTuning.skipped.reason"));
        verify(fineTuningService, never()).startFineTuningJob(any());
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
