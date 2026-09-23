package com.example.lms.service.rag.plan;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePlanDslLoaderTraceContractTest {

    @Test
    void numericFallbackCatchLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/plan/PlanDslLoader.java"));
        String parserCall = "return Integer.parseInt(s);";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "integer parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 620));

        assertTrue(window.contains("TraceStore.put(\"rag.planDsl.suppressed.intValue\", true)"));
        assertTrue(window.contains("rag.planDsl.suppressed.intValue.reason"));
        assertTrue(window.contains("TraceStore.put(\"rag.planDsl.suppressed.stage\", \"intValue\")"));
        assertTrue(window.contains("TraceStore.put(\"rag.planDsl.suppressed.errorType\", \"invalid_number\")"));
        assertFalse(window.contains("value={}"),
                "malformed numeric plan values must not be logged raw");
    }

    @Test
    void numericFallbackUsesStableInvalidNumberLabel() throws Exception {
        TraceStore.clear();
        JsonNode node = new ObjectMapper().readTree("{\"limit\":\"not-a-number\"}");
        Method method = PlanDslLoader.class.getDeclaredMethod("intOrNull", JsonNode.class, String.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, node, "limit"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.planDsl.suppressed.intValue"));
        assertEquals("number-format", TraceStore.get("rag.planDsl.suppressed.intValue.reason"));
        assertEquals("invalid_number", TraceStore.get("rag.planDsl.suppressed.intValue.errorType"));
        assertEquals("intValue", TraceStore.get("rag.planDsl.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("rag.planDsl.suppressed.errorType"));
    }
}
