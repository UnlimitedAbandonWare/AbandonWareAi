package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigSecurityTest {

    @Test
    void legacyModelConfigUsesPlaceholderAwareKeySelection() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/ModelConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("apiKey != null && !apiKey.isBlank()"));
        assertTrue(source.contains("ConfigValueGuards.isMissing(apiKey)"));
    }
}
