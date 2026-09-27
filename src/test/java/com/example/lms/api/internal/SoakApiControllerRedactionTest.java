package com.example.lms.api.internal;

import com.example.lms.moe.RgbSoakMetrics;
import com.example.lms.moe.RgbSoakReport;
import com.example.lms.scheduler.TrainingJobRunner;
import com.example.lms.service.soak.SoakTestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoakApiControllerRedactionTest {

    @Test
    void runCapsOversizedTopKBeforeServiceInvocation() {
        SoakTestService soakService = mock(SoakTestService.class);

        controller(soakService).run(Integer.MAX_VALUE, "all");

        verify(soakService).run(100, "all");
    }

    @Test
    void quickCapsOversizedTopKBeforeServiceInvocation() {
        SoakTestService soakService = mock(SoakTestService.class);

        controller(soakService).quick(Integer.MAX_VALUE, "all");

        verify(soakService).runQuick(100, "all");
    }

    @Test
    void bothEntryPointsRaiseNonpositiveTopKToOne() {
        SoakTestService soakService = mock(SoakTestService.class);
        SoakApiController controller = controller(soakService);

        controller.run(0, "all");
        controller.quick(-1, "all");

        verify(soakService).run(1, "all");
        verify(soakService).runQuick(1, "all");
    }

    @Test
    void rgbEndpointReturnsRedactedReport() throws Exception {
        SoakTestService soakService = mock(SoakTestService.class);
        TrainingJobRunner runner = mock(TrainingJobRunner.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TrainingJobRunner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runner);

        SoakApiController controller = new SoakApiController(soakService, provider);
        String rawSession = "session-private-soak-rgb";
        String rawQuery = "private soak rgb query";
        String rawPath = "C:\\private\\soak\\rgb-report.json";
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
        when(runner.runOnce(false, "soak_api")).thenReturn(report);

        ResponseEntity<?> response = controller.rgb();
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(response.getBody());

        assertFalse(body.contains(rawSession), body);
        assertFalse(body.contains(rawQuery), body);
        assertFalse(body.contains(rawPath), body);
        assertTrue(body.contains("hash:"), body);
    }

    @Test
    void rgbFailSoftCatchEmitsNamedBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/internal/SoakApiController.java"));

        assertTrue(source.contains("traceSuppressed(\"soak.rgb\", e);"));
    }

    private static SoakApiController controller(SoakTestService soakService) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TrainingJobRunner> provider = mock(ObjectProvider.class);
        return new SoakApiController(soakService, provider);
    }
}
