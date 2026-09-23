package com.example.lms.nova;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanSelectionServiceTest {

    @AfterEach
    void clearContext() {
        NovaRequestContext.setBrave(false);
    }

    @Test
    void defaultSelectionUsesEnabledSafeAutorunPlan() {
        BravePlan plan = new PlanSelectionService(new PlanDslLoader()).resolve();

        assertTrue(plan.enabled);
        assertEquals(10, plan.webTopK);
        assertEquals(3, plan.minCitations);
    }

    @Test
    void braveSelectionUsesEnabledBravePlan() {
        NovaRequestContext.setBrave(true);

        BravePlan plan = new PlanSelectionService(new PlanDslLoader()).resolve();

        assertTrue(plan.enabled);
        assertEquals(18, plan.webTopK);
        assertEquals(12, plan.burstMax);
    }

    @Test
    void planSelectionUsesInjectedPlanDslLoader() throws Exception {
        String service = Files.readString(Path.of("main/java/com/example/lms/nova/PlanSelectionService.java"));
        String loader = Files.readString(Path.of("main/java/com/example/lms/nova/PlanDslLoader.java"));

        assertFalse(service.contains("new PlanDslLoader()"));
        assertTrue(service.contains("PlanSelectionService(PlanDslLoader loader)"));
        assertTrue(loader.contains("@Component(\"novaPlanDslLoader\")"));
    }
}
