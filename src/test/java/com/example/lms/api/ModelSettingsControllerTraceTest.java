package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ModelSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ModelSettingsControllerTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void rejectedModelSaveLeavesHashOnlyTraceWithoutRawModelOrExceptionMessage() {
        String rawModel = "private-model";
        String rawError = "model lookup failed at C:\\Users\\nninn\\Desktop\\secret\\models\\private-model.txt";
        ModelSettingsService modelSettingsService = mock(ModelSettingsService.class);
        doThrow(new IllegalArgumentException(rawError)).when(modelSettingsService).changeCurrentModel(rawModel);
        ModelSettingsController controller = new ModelSettingsController(modelSettingsService);

        ResponseEntity<?> response = controller.saveDefaultModel(Map.of("model", rawModel));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, TraceStore.get("api.modelSettings.rejected"));
        assertEquals(1L, TraceStore.get("api.modelSettings.rejected.count"));
        assertEquals("model_setting_rejected", TraceStore.get("api.modelSettings.skipped.reason"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(rawModel),
                TraceStore.get("api.modelSettings.modelHash"));
        assertEquals(rawModel.length(), TraceStore.get("api.modelSettings.modelLength"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(rawError),
                TraceStore.get("api.modelSettings.errorHash"));
        assertEquals(rawError.length(), TraceStore.get("api.modelSettings.errorLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawModel));
        assertFalse(trace.contains("model lookup failed"));
        assertFalse(trace.contains("C:\\Users\\nninn"));
    }
}
