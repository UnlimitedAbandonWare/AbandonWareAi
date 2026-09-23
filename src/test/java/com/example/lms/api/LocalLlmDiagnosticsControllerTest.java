package com.example.lms.api;

import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalLlmDiagnosticsControllerTest {

    @Test
    void smokeHistoryEndpointReturnsReadOnlyServiceSnapshotWithBoundedLimit() {
        LocalLlmSmokeHistoryDiagnosticsService service = mock(LocalLlmSmokeHistoryDiagnosticsService.class);
        when(service.snapshot(5)).thenReturn(Map.of(
                "available", true,
                "recommendedRoute", "native_ollama"));

        LocalLlmDiagnosticsController controller = new LocalLlmDiagnosticsController(service);

        Map<String, Object> out = controller.smokeHistory(5);

        assertEquals(true, out.get("available"));
        assertEquals("native_ollama", out.get("recommendedRoute"));
        verify(service).snapshot(5);
    }
}
