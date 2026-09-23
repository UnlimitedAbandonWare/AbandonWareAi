package com.example.lms.telemetry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryFallbackBreadcrumbContractTest {

    @Test
    void telemetryFallbacksExposeBreadcrumbs() throws Exception {
        assertSourceContains("main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java",
                "TraceStore.put(\"telemetry.matrix.suppressed.numberParse\", true)");
        assertSourceContains("main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java",
                "TraceStore.put(\"telemetry.matrix.suppressed.stage\", \"numberParse\")");
        assertSourceContains("main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java",
                "TraceStore.put(\"telemetry.matrix.suppressed.extract\", true)");
        assertSourceContains("main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java",
                "TraceStore.put(\"telemetry.matrix.suppressed.extract.errorType\", \"extract_failed\")");
        assertSourceContains("main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java",
                "TraceStore.put(\"telemetry.matrix.suppressed.stage\", \"extract\")");
        assertSourceContains("main/java/com/example/lms/telemetry/MlaBreadcrumb.java",
                "TraceStore.put(\"mla.breadcrumb.suppressed.toDouble\", true)");
        assertSourceContains("main/java/com/example/lms/telemetry/MlaBreadcrumb.java",
                "TraceStore.put(\"mla.breadcrumb.suppressed.stage\", \"toDouble\")");
        assertSourceContains("main/java/com/example/lms/telemetry/TelemetryService.java",
                "TraceStore.put(\"telemetry.searchEvent.suppressed.record\", true)");
        assertSourceContains("main/java/com/example/lms/telemetry/TelemetryService.java",
                "TraceStore.put(\"telemetry.searchEvent.suppressed.record.errorType\", \"telemetry_record_failed\")");
        assertSourceContains("main/java/com/example/lms/telemetry/TelemetryService.java",
                "TraceStore.put(\"telemetry.searchEvent.suppressed.stage\", \"record\")");
        assertSourceContains("main/java/com/example/lms/telemetry/VirtualPointService.java",
                "traceSuppressed(\"vectorParse\", \"invalid_number\")");
        assertSourceContains("main/java/com/example/lms/telemetry/VirtualPointService.java",
                "traceSuppressed(\"append\", \"append_failed\")");
        assertSourceContains("main/java/com/example/lms/telemetry/VirtualPointService.java",
                "TraceStore.put(\"telemetry.virtualPoint.suppressed.stage\", safeStage)");
    }

    private static void assertSourceContains(String file, String needle) throws Exception {
        assertTrue(Files.readString(Path.of(file)).contains(needle), file + " missing " + needle);
    }
}
