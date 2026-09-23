package com.example.lms.service.rag.handler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class KnowledgeGraphHandlerJavaCleanTraceTest {

    @Test
    void javaCleanInterruptedFallbackRecordsRedactedTraceStoreBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "app/src/main/java_clean/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;"));
        assertTrue(source.contains("Thread.currentThread().interrupt();"));
        assertTrue(source.contains("\"retrieval.kg.javaClean.suppressed\""));
        assertTrue(source.contains("\"retrieval.kg.javaClean.suppressed.stage\""));
        assertTrue(source.contains("\"retrieval.kg.javaClean.suppressed.errorType\""));
        assertTrue(source.contains("\"retrieval.kg.javaClean.suppressed.count\""));
        assertTrue(source.contains("TraceStore.inc(\"retrieval.kg.javaClean.suppressed.count\")"));

        assertFalse(source.contains("TraceStore.put(\"retrieval.kg.javaClean.rawQuery\""));
        assertFalse(source.contains(".getMessage()"));
    }
}
