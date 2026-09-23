package com.example.lms.service.disambiguation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryDisambiguationServiceRedactionContractTest {

    @Test
    void queryDisambiguationServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "disambiguation fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void noiseGateProbabilityPropertiesUseLocalFailSoftParser() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java"));

        assertFalse(source.contains("Double.parseDouble(System.getProperty(\"orch.noiseGate.disambig"));
        assertTrue(source.contains("boundedDoubleProperty("));
        assertTrue(source.contains("\"orch.noiseGate.disambig.compression.escapeP.max\", 0.12d"));
        assertTrue(source.contains("\"orch.noiseGate.disambig.compression.escapeP.min\", 0.02d"));
        assertTrue(source.contains("TraceStore.put(\"disambig.suppressed.\" + safeStage, true);"));
    }
}
