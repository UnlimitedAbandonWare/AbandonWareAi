package com.example.lms.moe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RgbLogSignalParserTraceContractTest {

    @Test
    void tailReadFallbackCatchesLeaveScannerVisibleTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/moe/RgbLogSignalParser.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"moe.rgb.parser.suppressed.tailRead\", true)"));
        assertTrue(source.contains("TraceStore.put(\"moe.rgb.parser.suppressed.stage\", \"tailRead\")"));
        assertTrue(source.contains("TraceStore.put(\"moe.rgb.parser.suppressed.stage\", \"fullRead\")"));
        assertTrue(source.contains("TraceStore.put(\"moe.rgb.parser.suppressed.errorType\""));
        assertTrue(source.contains("TraceStore.put(\"moe.rgb.parser.suppressed.fullRead\", true)"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
    }
}
