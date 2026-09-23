package com.example.lms.entity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStatusConverterTest {

    @Test
    void invalidStatusFallsBackToActive() {
        MemoryStatusConverter converter = new MemoryStatusConverter();

        assertEquals(TranslationMemory.MemoryStatus.ACTIVE, converter.convertToEntityAttribute("not-a-status"));
    }

    @Test
    void invalidStatusFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/entity/MemoryStatusConverter.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"memoryStatus.ordinal\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"memoryStatus.name\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"memory.status.suppressed.\" + safeStage, true);"));
    }
}
