package com.example.lms.service.rag.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ContextConsistencyFilterRedactionContractTest {

    @Test
    void noisyDocLogDoesNotWriteRawTitle() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/filter/ContextConsistencyFilter.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("title='{}'"));
        assertFalse(source.contains("loweredNoise, title"));
        assertTrue(source.contains("titleHash"));
        assertTrue(source.contains("titleLength"));
        assertTrue(source.contains("SafeRedactor.hashValue(title)"));
    }

    @Test
    void consistencyFilterDiagnosticsDoNotWriteRawDomainOrNoiseTerms() throws Exception {
        String filterSource = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/filter/ContextConsistencyFilter.java"),
                StandardCharsets.UTF_8);
        String orchestratorSource = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(filterSource.contains("expectedDomain={}, noise={}"));
        assertFalse(filterSource.contains("expectedDomain, loweredNoise"));
        assertTrue(filterSource.contains("expectedDomainHash"));
        assertTrue(filterSource.contains("noiseDomainCount"));
        assertTrue(filterSource.contains("noiseDomainHash"));

        assertFalse(orchestratorSource.contains("expectedDomain={})"));
        assertFalse(orchestratorSource.contains("beforeSize, afterSize, expectedDomain"));
        assertTrue(orchestratorSource.contains("expectedDomainHash"));
    }
}
