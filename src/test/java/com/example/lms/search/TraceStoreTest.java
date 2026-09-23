package com.example.lms.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceStoreTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidLongFallbackPreservesExistingTraceContract() {
        TraceStore.put("unsafeLong", "raw private number token");

        assertEquals(0L, TraceStore.getLong("unsafeLong"));
        assertEquals("raw private number token", TraceStore.getString("unsafeLong"));
    }

    @Test
    void nonFiniteNumberLongFallbackPreventsCounterOverflow() {
        TraceStore.put("unsafeCounter", Double.POSITIVE_INFINITY);

        assertEquals(0L, TraceStore.getLong("unsafeCounter"));
        assertEquals(1L, TraceStore.inc("unsafeCounter"));
        assertEquals(1L, TraceStore.getLong("unsafeCounter"));
    }

    @Test
    void numericParseFallbackUsesHashOnlyDebugBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/TraceStore.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("[TraceStore] numeric parse fallback errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(ignore))"));
        assertFalse(source.contains("ignore.getMessage()"));
    }
}
