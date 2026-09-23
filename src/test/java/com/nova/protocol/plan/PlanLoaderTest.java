package com.nova.protocol.plan;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanLoaderTest {

    @Test
    void loadsClasspathPlanFieldsFromResource() {
        Plan plan = new PlanLoader().loadFromClasspath("safe.v1");

        assertNotNull(plan);
        assertEquals("safe.v1", plan.getId());
        assertEquals(2, plan.getCitationMin());
        assertEquals(8, plan.getkAllocation().get("web"));
        assertEquals(4, plan.getkAllocation().get("vector"));
        assertEquals(2, plan.getkAllocation().get("kg"));
    }

    @Test
    void loadsNestedPlanIdAndKnobsFromResource() {
        Plan plan = new PlanLoader().loadFromClasspath("brave.v1");

        assertNotNull(plan);
        assertEquals("brave.v1", plan.getId());
        assertEquals(3, plan.getCitationMin());
        assertEquals(true, plan.isEnableOverdrive());
        assertEquals(true, plan.getBurst().get("extremeZ.enabled"));
        assertEquals(12, plan.getBurst().get("expand.queryBurst.count"));
    }

    @Test
    void missingClasspathPlanReturnsNullInsteadOfSyntheticDefault() {
        assertNull(new PlanLoader().loadFromClasspath("missing-plan-for-test"));
    }

    @Test
    void nonFinitePlanIntegerFallsBackInsteadOfOverflowing() throws Exception {
        Method method = PlanLoader.class.getDeclaredMethod("asInt", Object.class, int.class);
        method.setAccessible(true);

        assertEquals(7, method.invoke(null, Double.POSITIVE_INFINITY, 7));
        assertEquals(7, method.invoke(null, Double.NaN, 7));
    }

    @Test
    void nonFinitePlanScalarRemainsStringInsteadOfNumericSignal() throws Exception {
        Method method = PlanLoader.class.getDeclaredMethod("parseScalar", String.class);
        method.setAccessible(true);

        assertEquals("NaN", method.invoke(null, "NaN"));
        assertEquals("1.0e309", method.invoke(null, "1.0e309"));
        assertEquals("-1.0e309", method.invoke(null, "-1.0e309"));
    }

    @Test
    void failSoftPlanLoaderPathsUseRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/nova/protocol/plan/PlanLoader.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("Plan loader fallback stage={0} valueLength={1} errorType={2}"));
        assertTrue(source.contains("logSuppressed(\"classpathRead\", ignored, resource.length());"));
        assertTrue(source.contains("logSuppressed(\"scalarParse\", ignored, v.length());"));
        assertTrue(source.contains("logSuppressed(\"intParse\", ignored, String.valueOf(value).length());"));
        assertTrue(source.contains("logSuppressed(\"discoverPlanIds\", ignored, PLAN_ROOT.length());"));
        assertTrue(source.contains("private static String errorType(Exception failure)"));
        assertTrue(source.contains("failure instanceof NumberFormatException"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertTrue(source.contains("errorType(failure));"));
        assertFalse(source.contains("LOG.log(System.Logger.Level.DEBUG, value"));
        assertFalse(source.contains("failure == null ? \"unknown\" : failure.getClass().getSimpleName()"));
    }
}
