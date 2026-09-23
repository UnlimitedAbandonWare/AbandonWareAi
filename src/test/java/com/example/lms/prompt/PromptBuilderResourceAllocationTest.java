package com.example.lms.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderResourceAllocationTest {

    @Test
    void instructionsIncludeResourceAllocationBlockWhenHintsExist() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("DGX Spark 구매 전 스펙/가격/리스크 비교")
                .resourceTier("HIGH")
                .resourceValueScore(0.82d)
                .resourceOptimismScore(0.60d)
                .resourceRiskAdjustedConfidence(0.74d)
                .resourceRewriteTemperature(0.22d)
                .resourceSearchRangeMultiplier(1.50d)
                .build();

        String instructions = new StandardPromptBuilder().buildInstructions(ctx);

        assertTrue(instructions.contains("### RESOURCE ALLOCATION"));
        assertTrue(instructions.contains("tier: HIGH"));
        assertTrue(instructions.contains("decisionValueScore: 0.820"));
        assertTrue(instructions.contains("rewriteTemperature: 0.220"));
        assertTrue(instructions.contains("searchRangeMultiplier: 1.500"));
    }
}
