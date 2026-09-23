package com.example.lms.risk;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskFallbackBreadcrumbContractTest {

    @Test
    void riskFallbacksLeaveFixedTraceBreadcrumbs() throws Exception {
        String topK = Files.readString(Path.of("main/java/com/example/lms/risk/TopKShrinkerAspect.java"));
        String model = Files.readString(Path.of("main/java/com/example/lms/risk/rdi/RealRiskModelProvider.java"));
        String features = Files.readString(Path.of("main/java/com/example/lms/risk/rdi/RiskFeatureExtractor.java"));

        assertTrue(topK.contains("TraceStore.put(\"risk.topK.suppressed.normalizeRisk\", true);"));
        assertTrue(topK.contains("TraceStore.put(\"risk.topK.suppressed.stage\", \"normalizeRisk\");"));
        assertTrue(topK.contains("TraceStore.put(\"risk.topK.suppressed.errorType\", \"invalid_number\");"));
        assertTrue(topK.contains("TraceStore.put(\"risk.topK.suppressed.normalizeRisk.errorType\""));
        assertTrue(model.contains("TraceStore.put(\"risk.model.suppressed.coefficientParse\", true);"));
        assertTrue(model.contains("TraceStore.put(\"risk.model.suppressed.stage\", \"coefficientParse\");"));
        assertTrue(model.contains("TraceStore.put(\"risk.model.suppressed.errorType\", \"invalid_number\");"));
        assertTrue(model.contains("TraceStore.put(\"risk.model.suppressed.coefficientParse.errorType\""));
        assertTrue(features.contains("TraceStore.put(\"risk.feature.suppressed.textSegment\", true);"));
        assertTrue(features.contains("TraceStore.put(\"risk.feature.suppressed.stage\", \"textSegment\");"));
        assertTrue(features.contains("TraceStore.put(\"risk.feature.suppressed.errorType\", errorType);"));
        assertTrue(features.contains("TraceStore.put(\"risk.feature.suppressed.textSegment.errorType\""));
    }
}
