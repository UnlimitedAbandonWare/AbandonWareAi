package com.example.lms.uaw.orchestration;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.presence.UserAbsenceGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UawOrchestrationGateTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void stagePolicyFailureRecordsHashOnlyFailSoftBreadcrumbAndStillAllows() {
        UserAbsenceGate absenceGate = mock(UserAbsenceGate.class);
        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        StagePolicyProperties stagePolicy = mock(StagePolicyProperties.class);
        when(stagePolicy.isEnabled()).thenReturn(true);
        when(stagePolicy.isStageEnabled(anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("raw stage policy secret"));
        UawOrchestrationGate gate = new UawOrchestrationGate(absenceGate);
        ReflectionTestUtils.setField(gate, "stagePolicy", stagePolicy);

        UawOrchestrationGate.Decision decision = gate.decide("uaw.secret.stage", -1.0d);

        assertTrue(decision.allowed());
        assertEquals("ok", decision.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.orchestrationGate.stagePolicy.failSoft"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.stagePolicy.errorClass"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.stagePolicy.errorType"));
        assertEquals(SafeRedactor.hashValue("uaw.secret.stage"),
                TraceStore.get("uaw.orchestrationGate.stagePolicy.stageKeyHash"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("uaw.secret.stage"));
        assertFalse(trace.contains("raw stage policy secret"));
    }

    @Test
    void presenceFailureRecordsHashOnlyFailSoftBreadcrumbAndStillAllows() {
        UserAbsenceGate absenceGate = mock(UserAbsenceGate.class);
        when(absenceGate.isUserAbsentNow())
                .thenThrow(new IllegalStateException("raw presence tracker secret"));
        UawOrchestrationGate gate = new UawOrchestrationGate(absenceGate);

        UawOrchestrationGate.Decision decision = gate.decide("uaw.presence.stage", -1.0d);

        assertTrue(decision.allowed());
        assertEquals("ok", decision.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.orchestrationGate.presence.failSoft"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.presence.errorClass"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.presence.errorType"));
        assertEquals(SafeRedactor.hashValue("uaw.presence.stage"),
                TraceStore.get("uaw.orchestrationGate.presence.stageKeyHash"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("uaw.presence.stage"));
        assertFalse(trace.contains("raw presence tracker secret"));
    }

    @Test
    void breakerFailureRecordsHashOnlyFailSoftBreadcrumbAndStillAllows() {
        UserAbsenceGate absenceGate = mock(UserAbsenceGate.class);
        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        NightmareBreaker breaker = mock(NightmareBreaker.class);
        when(breaker.isAnyOpen(any(String[].class)))
                .thenThrow(new IllegalStateException("raw breaker secret"));
        UawOrchestrationGate gate = new UawOrchestrationGate(absenceGate);
        ReflectionTestUtils.setField(gate, "nightmareBreaker", breaker);

        UawOrchestrationGate.Decision decision = gate.decide("uaw.breaker.stage", -1.0d, "raw.breaker.key");

        assertTrue(decision.allowed());
        assertEquals("ok", decision.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.orchestrationGate.breaker.failSoft"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.breaker.errorClass"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.breaker.errorType"));
        assertEquals(SafeRedactor.hashValue("uaw.breaker.stage"),
                TraceStore.get("uaw.orchestrationGate.breaker.stageKeyHash"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("uaw.breaker.stage"));
        assertFalse(trace.contains("raw.breaker.key"));
        assertFalse(trace.contains("raw breaker secret"));
    }

    @Test
    void breakerPrefixFailureRecordsHashOnlyFailSoftBreadcrumbAndStillAllows() {
        UserAbsenceGate absenceGate = mock(UserAbsenceGate.class);
        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        NightmareBreaker breaker = mock(NightmareBreaker.class);
        when(breaker.isAnyOpen(any(String[].class))).thenReturn(false);
        when(breaker.isAnyOpenPrefix(anyString()))
                .thenThrow(new IllegalStateException("raw breaker prefix secret"));
        UawOrchestrationGate gate = new UawOrchestrationGate(absenceGate);
        ReflectionTestUtils.setField(gate, "nightmareBreaker", breaker);

        UawOrchestrationGate.Decision decision = gate.decide("uaw.breaker.prefix.stage", -1.0d, "raw.prefix.key");

        assertTrue(decision.allowed());
        assertEquals("ok", decision.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.orchestrationGate.breakerPrefix.failSoft"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.breakerPrefix.errorClass"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.orchestrationGate.breakerPrefix.errorType"));
        assertEquals(SafeRedactor.hashValue("uaw.breaker.prefix.stage"),
                TraceStore.get("uaw.orchestrationGate.breakerPrefix.stageKeyHash"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("uaw.breaker.prefix.stage"));
        assertFalse(trace.contains("raw.prefix.key"));
        assertFalse(trace.contains("raw breaker prefix secret"));
    }

    @Test
    void uawOrchestrationGateDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/orchestration/UawOrchestrationGate.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "UAW gate fail-soft probes need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("TraceStore.put(\"uaw.orchestrationGate.suppressed.stagePolicy\", true)"));
        assertTrue(source.contains("TraceStore.put(\"uaw.orchestrationGate.suppressed.presence\", true)"));
        assertTrue(source.contains("TraceStore.put(\"uaw.orchestrationGate.suppressed.breakerPrefix\", true)"));
        assertTrue(source.contains("TraceStore.put(\"uaw.orchestrationGate.suppressed.breaker\", true)"));
        assertTrue(source.contains("UAW orchestration gate trace skipped source="));
    }
}
