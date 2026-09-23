package com.example.lms.service.vector;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorFallbackBreadcrumbContractTest {

    @Test
    void understandingAndUpstashFallbacksExposeBreadcrumbs() throws Exception {
        assertSourceContains("main/java/com/example/lms/service/understanding/AnswerUnderstandingService.java",
                "TraceStore.put(\"answerUnderstanding.suppressed.generateOrParse\", true)");
        assertSourceContains("main/java/com/example/lms/service/understanding/AnswerUnderstandingService.java",
                "TraceStore.put(\"answerUnderstanding.suppressed.jsonRepair\", true)");
        assertSourceContains("main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java",
                "TraceStore.put(\"vector.upstash.suppressed.restUrlHost\", true)");
    }

    @Test
    void quarantineDlqFallbacksExposeBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/vector/VectorQuarantineDlqService.java"));

        assertTrue(source.contains("TraceStore.put(\"vector.quarantineDlq.suppressed.stats\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.quarantineDlq.suppressed.redriveBlocked\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.quarantineDlq.suppressed.redriveRetry\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.quarantineDlq.suppressed.resolveSid\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.quarantineDlq.suppressed.parseMeta\", true)"));
    }

    @Test
    void shadowMergeDlqFallbacksExposeBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/vector/VectorShadowMergeDlqService.java"));

        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.pendingCount\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.dedupeLookup\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.metaJson\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.duplicateRecord\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.record\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.mergeBlocked\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.mergeFailed\", true)"));
        assertTrue(source.contains("TraceStore.put(\"vector.shadowMergeDlq.suppressed.parseMeta\", true)"));
    }

    private static void assertSourceContains(String file, String needle) throws Exception {
        assertTrue(Files.readString(Path.of(file)).contains(needle), file + " missing " + needle);
    }
}
