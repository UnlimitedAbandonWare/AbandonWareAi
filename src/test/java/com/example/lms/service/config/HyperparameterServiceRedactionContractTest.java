package com.example.lms.service.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class HyperparameterServiceRedactionContractTest {

    @Test
    void nonFiniteRerankSynergyWeightFallsBackToDefault() {
        System.setProperty("rerank.synergy-weight", "Infinity");
        try {
            HyperparameterService service = new HyperparameterService(null);

            assertEquals(1.0d, service.getRerankSynergyWeight());
        } finally {
            System.clearProperty("rerank.synergy-weight");
        }
    }

    @Test
    void dynamicTuningLogDoesNotWriteRawKeyValuePair() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/config/HyperparameterService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("{} = {}\", key, value"));
        assertTrue(source.contains("keyHash"));
        assertTrue(source.contains("valuePresent"));
        assertTrue(source.contains("SafeRedactor.hashValue(key)"));
    }

    @Test
    void numericEnvironmentFallbackOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/config/HyperparameterService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Exception ignored) {\n            return 1.0;\n        }"));
        assertFalse(source.contains("catch (NumberFormatException ignored) {\n            return 1.0;\n        }"));
        assertTrue(source.contains("catch (NumberFormatException ignored) {"));
        assertTrue(source.contains("environment double fallback errorHash={} errorLength={}"));
    }
}
