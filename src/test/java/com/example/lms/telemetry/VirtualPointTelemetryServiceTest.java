package com.example.lms.telemetry;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualPointTelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void appendNdjsonStoresRequestIdAsHashOnly() throws Exception {
        VirtualPointService service = new VirtualPointService();
        String rawRid = "sk-" + "virtualpointrequestid01234567890";
        Path out = tempDir.resolve("virtual-points.ndjson");

        service.appendNdjson(rawRid, Map.of("m1_source_mix_web", 1), out.toFile());

        String line = Files.readString(out);
        assertFalse(line.contains(rawRid), line);
        assertTrue(line.contains("\"requestId\":\"" + SafeRedactor.hashValue(rawRid) + "\""), line);
    }

    @Test
    void malformedVectorNumberRecordsInvalidNumberWithoutRawLeak() {
        VirtualPointService service = new VirtualPointService();
        TraceStore.clear();
        String raw = "ownerToken=raw-secret";

        List<Double> vector = service.toVector(Map.of(
                "_order", List.of("m1_source_mix_web"),
                "m1_source_mix_web", raw));

        assertEquals(List.of(0.0d), vector);
        assertEquals(Boolean.TRUE, TraceStore.get("telemetry.virtualPoint.suppressed.vectorParse"));
        assertEquals("invalid_number", TraceStore.get("telemetry.virtualPoint.suppressed.vectorParse.errorType"));
        assertEquals("vectorParse", TraceStore.get("telemetry.virtualPoint.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("telemetry.virtualPoint.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(raw), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
        TraceStore.clear();
    }
}
