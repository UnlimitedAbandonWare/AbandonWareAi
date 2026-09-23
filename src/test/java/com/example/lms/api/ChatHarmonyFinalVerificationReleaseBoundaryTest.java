package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatWorkflow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatHarmonyFinalVerificationReleaseBoundaryTest {

    private static final String QUERY =
            "What is the verified value? Reply with unsupported draft only.";
    private static final String DRAFT = "unsupported draft";

    @Test
    void deniedFinalVerificationCannotBeOverwrittenByExactOutputShaping() throws Exception {
        TraceStore.clear();
        try {
            for (VerificationCase verification : List.of(
                    new VerificationCase("unknown", false, false),
                    new VerificationCase("insufficient", true, false),
                    new VerificationCase("rejected", true, false),
                    new VerificationCase("pass", true, false))) {
                GateDecision gated = gate(verification);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("finalAnswer.releaseAllowed", gated.releaseAllowed());

                String visible = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                        meta, QUERY, gated.content());

                assertFalse(gated.releaseAllowed(), verification.status());
                assertEquals(gated.content(), visible, verification.status());
                assertFalse(visible.contains(DRAFT), verification.status());
            }
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void acceptedFinalVerificationStillHonorsExactOutputShaping() throws Exception {
        GateDecision gated = gate(new VerificationCase("pass", true, true));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("finalAnswer.releaseAllowed", gated.releaseAllowed());

        String visible = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                meta, QUERY, "The verified value is unsupported draft.");

        assertEquals(DRAFT, visible);
    }

    @Test
    void priorSafetyFallbackIsBytePreservedThroughControllerHarmony() throws Exception {
        Method baseGate = ChatWorkflow.class.getDeclaredMethod(
                "applyFinalVerificationReleaseGate",
                String.class,
                boolean.class,
                String.class,
                boolean.class,
                boolean.class);
        baseGate.setAccessible(true);
        Object base = baseGate.invoke(null, "safe fallback", false, "not_run", false, false);
        Class<?> decisionType = base.getClass();
        Class<?> evidenceStateType = Class.forName("com.example.lms.service.ChatWorkflow$EvidenceReleaseState");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object incomplete = Enum.valueOf((Class<? extends Enum>) evidenceStateType, "METADATA_INCOMPLETE");
        Method compose = ChatWorkflow.class.getDeclaredMethod(
                "applyEvidenceReleasePolicy",
                decisionType,
                evidenceStateType,
                boolean.class,
                boolean.class,
                boolean.class);
        compose.setAccessible(true);
        Object locked = compose.invoke(null, base, incomplete, true, true, false);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("finalAnswer.releaseAllowed", recordValue(locked, "releaseAllowed"));

        String visible = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                meta,
                "Reply with unsupported draft only.",
                (String) recordValue(locked, "content"));

        assertEquals("safe fallback", visible);
        assertFalse((boolean) recordValue(locked, "releaseAllowed"));
    }

    private static GateDecision gate(VerificationCase verification) throws Exception {
        Method gate = ChatWorkflow.class.getDeclaredMethod(
                "applyFinalVerificationReleaseGate",
                String.class,
                boolean.class,
                String.class,
                boolean.class,
                boolean.class);
        gate.setAccessible(true);
        Object decision = gate.invoke(
                null,
                DRAFT,
                true,
                verification.status(),
                verification.outcomeKnown(),
                verification.accepted());
        return new GateDecision(
                (String) recordValue(decision, "content"),
                (boolean) recordValue(decision, "releaseAllowed"));
    }

    private static Object recordValue(Object record, String component) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(component);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private record VerificationCase(String status, boolean outcomeKnown, boolean accepted) {
    }

    private record GateDecision(String content, boolean releaseAllowed) {
    }
}
