package com.abandonware.ai.addons.cache;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightRegistryRedactionTest {

    @Test
    void evictionLogDoesNotRenderRawCacheKey() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/abandonware/ai/addons/cache/SingleFlightRegistry.java"));

        assertFalse(source.contains("\"single-flight evicted: \" + key"));
        assertTrue(source.contains("SafeRedactor.hashValue(key)"));
    }
}
