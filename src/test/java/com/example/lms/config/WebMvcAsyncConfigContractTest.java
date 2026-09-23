package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMvcAsyncConfigContractTest {

    @Test
    void mvcAsyncTimeoutUsesConfiguredPropertyInsteadOfThirtySecondHardLimit() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/WebMvcAsyncConfig.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("@Value(\"${spring.mvc.async.request-timeout:180000}\")"));
        assertTrue(source.contains("configurer.setDefaultTimeout(asyncRequestTimeoutMs);"));
        assertFalse(source.contains("configurer.setDefaultTimeout(30_000L);"));
    }
}
