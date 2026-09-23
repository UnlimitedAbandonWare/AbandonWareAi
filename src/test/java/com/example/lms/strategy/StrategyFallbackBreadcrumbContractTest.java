package com.example.lms.strategy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyFallbackBreadcrumbContractTest {

    @Test
    void retrievalOrderAndStrategyFallbacksExposeBreadcrumbs() throws Exception {
        String retrieval = Files.readString(Path.of(
                "main/java/com/example/lms/strategy/RetrievalOrderService.java"));
        String selector = Files.readString(Path.of(
                "main/java/com/example/lms/strategy/StrategySelectorService.java"));

        assertTrue(retrieval.contains("TraceStore.put(\"retrieval.order.suppressed.ruleBreakContext\", true)"));
        assertTrue(retrieval.contains("TraceStore.put(\"retrieval.order.suppressed.ruleBreakContext.errorType\", \"rulebreak_context_failed\")"));
        assertTrue(retrieval.contains("TraceStore.put(\"retrieval.order.suppressed.guardContext\", true)"));
        assertTrue(retrieval.contains("TraceStore.put(\"retrieval.order.suppressed.guardContext.errorType\", \"guard_context_failed\")"));
        assertTrue(retrieval.contains("TraceStore.put(\"retrieval.order.suppressed.cooldown\", true)"));
        assertTrue(retrieval.contains("TraceStore.put(\"retrieval.order.suppressed.cooldown.errorType\", \"cooldown_check_failed\")"));
        assertTrue(selector.contains("TraceStore.put(\"strategy.selector.suppressed.strategyName\", true)"));
        assertTrue(selector.contains("TraceStore.put(\"strategy.selector.suppressed.strategyName.errorType\", \"invalid_strategy\")"));
    }
}
