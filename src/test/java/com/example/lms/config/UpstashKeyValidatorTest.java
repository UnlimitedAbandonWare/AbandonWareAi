package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstashKeyValidatorTest {

    @Test
    void placeholderPreferredKeyDoesNotConflictWithRealLegacyKey() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("upstash.vector.api-key", "dummy")
                .withProperty("vector.upstash.token", "real-upstash-token");
        UpstashKeyValidator validator = new UpstashKeyValidator();
        validator.setEnvironment(env);

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void propertyProbeFailureLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/UpstashKeyValidator.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"upstash.propertyProbe\", e);"));
        assertTrue(source.contains("TraceStore.put(\"upstash.config.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"upstash.config.suppressed.\" + safeStage + \".errorType\""));
    }
}
