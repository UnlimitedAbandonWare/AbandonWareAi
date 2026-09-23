package com.abandonware.ai.agent.contract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolManifestCatalogRedactionContractTest {

    @Test
    void manifestReadFailuresUseStableOperationalDetail() throws Exception {
        String source = source();

        assertFalse(source.contains("event(\"manifest_read_failed\", candidate, ex.getClass().getSimpleName())"));
        assertTrue(source.contains("event(\"manifest_read_failed\", candidate, \"manifest_read_failed\")"));
        assertTrue(source.contains("traceSuppressed(\"manifest.read\", ex);"));
    }

    @Test
    void reportWriteFailuresUseStableOperationalLabel() throws Exception {
        String source = source();

        assertFalse(source.contains("report.put(\"reportWriteError\", ex.getClass().getSimpleName())"));
        assertTrue(source.contains("report.put(\"reportWriteError\", \"report_write_failed\")"));
        assertTrue(source.contains("traceSuppressed(\"report.write\", ex);"));
    }

    @Test
    void bestEffortFallbacksLeaveFixedTraceStages() throws Exception {
        String source = source();

        assertTrue(source.contains("traceSuppressed(\"objectMapper.findAndRegisterModules\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"sha256.digest\", ex);"));
        assertTrue(source.contains("agent.toolManifest.suppressed.stage"));
        assertFalse(source.contains("ex.getMessage()"));
    }

    private static String source() throws Exception {
        return Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/contract/ToolManifestCatalog.java"),
                StandardCharsets.UTF_8);
    }
}
