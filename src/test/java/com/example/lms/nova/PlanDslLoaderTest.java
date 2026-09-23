package com.example.lms.nova;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDslLoaderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void loadsNewPlanOverridesSchemaForSafeAutorun() {
        BravePlan plan = new PlanDslLoader().load("safe_autorun.v1");

        assertTrue(plan.enabled);
        assertEquals(10, plan.webTopK);
        assertEquals(8, plan.vectorTopK);
        assertEquals(4, plan.kgTopK);
        assertEquals(3, plan.minCitations);
    }

    @Test
    void loadsNewPlanKnobsSchemaForBrave() {
        BravePlan plan = new PlanDslLoader().load("brave.v1");

        assertTrue(plan.enabled);
        assertEquals(18, plan.webTopK);
        assertTrue(plan.burstEnabled);
        assertEquals(12, plan.burstMax);
        assertEquals(3, plan.minCitations);
    }

    @Test
    void missingPlanFailsSoftDisabled() {
        BravePlan plan = new PlanDslLoader().load("missing-plan-for-test");

        assertFalse(plan.enabled);
        assertEquals(0, plan.webTopK);
        assertEquals(true, TraceStore.get("nova.planDsl.suppressed.load"));
        assertNotNull(TraceStore.get("nova.planDsl.suppressed.load.errorType"));
        assertEquals("load", TraceStore.get("nova.planDsl.suppressed.stage"));
        assertNotNull(TraceStore.get("nova.planDsl.suppressed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("missing-plan-for-test"));
    }

    @Test
    void numericFallbackCatchLeavesScannerVisibleBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/nova/PlanDslLoader.java"));

        assertTrue(source.contains("TraceStore.put(\"nova.planDsl.suppressed.intValue\", true)"));
        assertTrue(source.contains("TraceStore.put(\"nova.planDsl.suppressed.intValue.errorType\", errorType(e))"));
        assertTrue(source.contains("return \"invalid_number\";"));
    }

    @Test
    void invalidNumericPlanValueUsesStableReasonCodeWithoutRawValue() throws Exception {
        String raw = "private plan integer ownerToken=fake-token";
        Method intValue = PlanDslLoader.class.getDeclaredMethod("intValue", Object.class, int.class);
        intValue.setAccessible(true);

        assertEquals(7, ((Number) intValue.invoke(null, raw, 7)).intValue());

        assertEquals("invalid_number", TraceStore.get("nova.planDsl.suppressed.intValue.errorType"));
        assertEquals("intValue", TraceStore.get("nova.planDsl.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("nova.planDsl.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    @Test
    void minimalPlanDslExecutorLoadFailureLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/orchestrator/PlanDslExecutor.java"));

        assertTrue(source.contains("stage=loadPlan"));
        assertTrue(source.contains("err=plan-load-failure"));
    }
}
