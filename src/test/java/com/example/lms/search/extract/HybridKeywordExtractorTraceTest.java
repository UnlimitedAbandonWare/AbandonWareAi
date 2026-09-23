package com.example.lms.search.extract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridKeywordExtractorTraceTest {

    @Test
    void extractionFallbacksLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/extract/HybridKeywordExtractor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("private static void traceSuppressed(String stage, Throwable failure)"));
        assertTrue(source.contains("traceSuppressed(\"ruleAugment\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"llmTransform\", e);"));
        assertTrue(source.contains("traceSuppressed(\"llmFallbackRuleAugment\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"resolveMode\", e);"));
        assertTrue(source.contains("TraceStore.put(\"hybridKeywordExtractor.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"hybridKeywordExtractor.suppressed.errorType\", errorType);"));
        assertTrue(source.contains("log.debug(\"[HybridKeywordExtractor] trace suppression failed stage={} errorHash={} errorLength={}\""));
    }
}
