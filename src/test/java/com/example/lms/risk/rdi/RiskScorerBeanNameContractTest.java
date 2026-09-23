package com.example.lms.risk.rdi;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScorerBeanNameContractTest {

    @Test
    void rdiRiskScorerUsesExplicitBeanNameToAvoidTopLevelRiskScorerConflict() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/risk/rdi/RiskScorer.java"));

        assertTrue(source.contains("@Component(\"rdiRiskScorer\")"));
    }
}
