package com.example.lms.manifest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelManifestConfigRedactionContractTest {

    @Test
    void manifestLoadFailuresDoNotExposeConfiguredPath() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/manifest/ModelManifestConfig.java"));

        assertFalse(source.contains("\"Manifest not found: \" + manifestPath"));
        assertFalse(source.contains("\"Failed to load models manifest: \" + manifestPath"));
        assertTrue(source.contains("safeManifestPath()"));
        assertTrue(source.contains("pathHash="));
        assertTrue(source.contains("pathLength="));
    }
}
