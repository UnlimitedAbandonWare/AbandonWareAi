package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedWebSearchWiringLogContractTest {

    @Test
    void missingCachedWebSearchProvidersUseInfoCheckpointNotWarn() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LangChainConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.warn(\"[Wiring] CachedWebSearch providers=0"));
        assertTrue(source.contains("log.info(\"[AWX][search][cached] supplementalProviders=0 disabledReason=supplemental_multi_search_providers_disabled"));
        assertFalse(source.contains("disabledReason=no_web_search_providers"));
    }
}
