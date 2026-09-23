package com.example.lms.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemPropertyConfigFallbackTest {

    private final Map<String, String> originalProperties = new HashMap<>();

    @AfterEach
    void restoreProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void featureFlagsFallbackWhenRrfPropertiesAreMalformedOrNonFinite() {
        setProperty("fusion.rrf.weight.web", "not-a-number");
        setProperty("fusion.rrf.weight.vector", "NaN");
        setProperty("fusion.rrf.weight.kg", "Infinity");
        setProperty("fusion.rrf.weight.bm25", "-Infinity");

        FeatureFlags flags = assertDoesNotThrow(FeatureFlags::new);

        assertEquals(1.0d, flags.rrfWeightWeb);
        assertEquals(1.0d, flags.rrfWeightVector);
        assertEquals(0.8d, flags.rrfWeightKg);
        assertEquals(1.0d, flags.rrfWeightBm25);
    }

    @Test
    void bm25ConfigFallbackWhenTopKPropertyIsMalformed() {
        setProperty("bm25.topK", "not-a-number");

        Bm25Config config = assertDoesNotThrow(Bm25Config::new);

        assertEquals(50, config.topK);
    }

    @Test
    void malformedSystemPropertyFallbacksLeaveTraceBreadcrumbs() throws Exception {
        String bm25 = Files.readString(
                Path.of("main/java/com/example/lms/config/Bm25Config.java"),
                StandardCharsets.UTF_8);
        String featureFlags = Files.readString(
                Path.of("main/java/com/example/lms/config/FeatureFlags.java"),
                StandardCharsets.UTF_8);

        assertEquals(true, bm25.contains("traceSuppressed(\"bm25Config.topK\");"));
        assertEquals(true, featureFlags.contains("traceSuppressed(\"featureFlags.doubleProperty\");"));
    }

    private void setProperty(String key, String value) {
        originalProperties.computeIfAbsent(key, System::getProperty);
        System.setProperty(key, value);
    }
}
