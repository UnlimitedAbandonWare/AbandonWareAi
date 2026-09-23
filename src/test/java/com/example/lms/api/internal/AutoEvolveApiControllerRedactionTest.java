package com.example.lms.api.internal;

import com.example.lms.moe.RgbMoeProperties;
import com.example.lms.moe.RgbSoakMetrics;
import com.example.lms.moe.RgbSoakReport;
import com.example.lms.scheduler.AutoEvolveDebugStore;
import com.example.lms.scheduler.AutoEvolveRunDebug;
import com.example.lms.scheduler.TrainingJobRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoEvolveApiControllerRedactionTest {

    @Test
    void runEndpointReturnsRedactedReport() throws Exception {
        TrainingJobRunner runner = mock(TrainingJobRunner.class);
        RgbMoeProperties props = new RgbMoeProperties();
        AutoEvolveDebugStore debugStore = new AutoEvolveDebugStore(
                props,
                new ObjectMapper().findAndRegisterModules());
        AutoEvolveApiController controller = new AutoEvolveApiController(runner, debugStore, props);

        String rawSession = "session-private-run";
        String rawQuery = "private autoevolve run query";
        String rawPath = "C:\\private\\autoevolve\\run-report.json";
        RgbSoakReport report = new RgbSoakReport(
                rawSession,
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:01Z"),
                "R_ONLY",
                List.of("G_ONLY"),
                List.of(),
                List.of(rawQuery),
                Map.of("R_ONLY", new RgbSoakMetrics(1, 0.5d, 0.5d, 10.0d, 1, 0, 0.0d)),
                Map.of("rawQuery", rawQuery, "reportPath", rawPath));
        when(runner.runOnce(false, "manual_api")).thenReturn(report);

        ResponseEntity<?> response = controller.run();
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(response.getBody());

        assertFalse(body.contains(rawSession), body);
        assertFalse(body.contains(rawQuery), body);
        assertFalse(body.contains(rawPath), body);
        assertTrue(body.contains("hash:"), body);
    }

    @Test
    void statusEndpointDoesNotExposeRawExceptionClassNames() throws Exception {
        TrainingJobRunner runner = mock(TrainingJobRunner.class);
        when(runner.currentSessionId()).thenReturn("session-private-current");
        when(runner.isRunning()).thenReturn(false);

        RgbMoeProperties props = new RgbMoeProperties();
        props.getDebug().setEnabled(true);
        AutoEvolveDebugStore debugStore = new AutoEvolveDebugStore(
                props,
                new ObjectMapper().findAndRegisterModules());
        AutoEvolveApiController controller = new AutoEvolveApiController(runner, debugStore, props);

        String rawSession = "session-private-run";
        String rawQuery = "private autoevolve debug query";
        String rawPath = "C:\\private\\autoevolve\\debug-report.json";
        String rawErrorMessage = "failed on query=" + rawQuery;
        debugStore.record(new AutoEvolveRunDebug(
                rawSession,
                "manual_api",
                false,
                true,
                AutoEvolveRunDebug.Outcome.FAILED,
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:01Z"),
                null,
                null,
                null,
                List.of(rawQuery),
                List.of(rawQuery + " final"),
                AutoEvolveRunDebug.ExpansionDebug.skipped(1),
                new AutoEvolveRunDebug.BlueCallDebug(true, false, 25L, 1, 0, 429,
                        "retry later", Map.of("x-request-id", "rid-private"),
                        "HttpStatusException", rawErrorMessage, "body:" + rawQuery, true),
                rawPath,
                null,
                "RuntimeException",
                rawErrorMessage));

        ResponseEntity<Map<String, Object>> response = controller.status(10);
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(response.getBody());

        assertFalse(body.contains(rawSession), body);
        assertFalse(body.contains(rawQuery), body);
        assertFalse(body.contains(rawPath), body);
        assertFalse(body.contains("RuntimeException"), body);
        assertFalse(body.contains("HttpStatusException"), body);
        assertTrue(body.contains("errorClassHash"), body);
        assertTrue(body.contains("errorClassLength"), body);
    }
}
