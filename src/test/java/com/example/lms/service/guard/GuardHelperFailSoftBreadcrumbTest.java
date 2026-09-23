package com.example.lms.service.guard;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardHelperFailSoftBreadcrumbTest {

    @Test
    void guardHelperFallbacksLeaveStageBreadcrumbs() throws Exception {
        String enricher = read("main/java/com/example/lms/service/guard/EvidenceDocListEnricher.java");
        String context = read("main/java/com/example/lms/service/guard/GuardContext.java");
        String holder = read("main/java/com/example/lms/service/guard/GuardContextHolder.java");
        String vectorQuality = read("main/java/com/example/lms/service/guard/VectorQualityGuard.java");

        assertSystemStage(enricher, "EvidenceDocListEnricher", "urlHost");
        assertSystemStage(enricher, "EvidenceDocListEnricher", "urlTail");
        assertSystemStage(enricher, "EvidenceDocListEnricher", "safeDecode");

        assertSystemInvalidNumberStage(context, "GuardContext", "planInt.parseInt");
        assertSystemInvalidNumberStage(context, "GuardContext", "planInt.parseLong");
        assertSystemInvalidNumberStage(context, "GuardContext", "planLong.parse");
        assertSystemInvalidNumberStage(context, "GuardContext", "planDouble.parse");
        assertSystemStage(context, "GuardContext", "copy.planOverrides");
        assertSystemStage(context, "GuardContext", "copy.irregularityReasons");

        assertSystemStage(holder, "GuardContextHolder", "isWebRequestThread");
        assertSlf4jInvalidNumberStage(vectorQuality, "VectorQualityGuard", "safeInt.parse");
        assertSlf4jInvalidNumberStage(vectorQuality, "VectorQualityGuard", "safeDouble.parse");
    }

    @Test
    void guardContextPlanNumbersDropNonFiniteOverrides() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("i", Double.POSITIVE_INFINITY);
        context.putPlanOverride("l", Double.NEGATIVE_INFINITY);
        context.putPlanOverride("d", Double.POSITIVE_INFINITY);
        context.putPlanOverride("optionalDouble", Double.POSITIVE_INFINITY);

        assertEquals(7, context.planInt("i", 7));
        assertEquals(11L, context.planLong("l", 11L));
        assertEquals(0.25d, context.planDouble("d", 0.25d), 0.0001d);
        assertEquals(null, context.planDouble("optionalDouble"));
    }

    @Test
    void vectorQualityGuardHelpersDropNonFiniteNumbers() throws Exception {
        Method safeInt = VectorQualityGuard.class.getDeclaredMethod("safeInt", Object.class, int.class);
        Method safeDouble = VectorQualityGuard.class.getDeclaredMethod("safeDouble", Object.class, double.class);
        safeInt.setAccessible(true);
        safeDouble.setAccessible(true);

        assertEquals(7, safeInt.invoke(null, Double.POSITIVE_INFINITY, 7));
        assertEquals(0.25d, (Double) safeDouble.invoke(null, Double.NaN, 0.25d), 0.0001d);
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void assertSystemStage(String source, String component, String stage) {
        assertTrue(source.contains("[" + component + "] fail-soft stage={0}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }

    private static void assertSystemInvalidNumberStage(String source, String component, String stage) {
        assertTrue(source.contains("[" + component + "] fail-soft stage={0} errorType={1}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing " + component + " invalid_number fail-soft stage: " + stage);
    }

    private static void assertSlf4jStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }

    private static void assertSlf4jInvalidNumberStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing " + component + " invalid_number fail-soft stage: " + stage);
    }
}
