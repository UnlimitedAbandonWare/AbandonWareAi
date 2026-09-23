package com.example.lms.orchestration.control;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugProbeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagControlFailureSignalProjectorTest {

    private final RagControlFailureSignalProjector projector = new RagControlFailureSignalProjector();

    @Test
    void projectsOnlyTheAllowlistedFailureSignalContract() {
        Map<String, Object> data = validData();
        data.put("rawAnswer", "private-answer-marker");
        data.put("arbitraryNested", Map.of("query", "private-query-marker"));

        RagControlFailureSignalProjector.FailureSignal signal = projector.project(event(
                DebugProbeType.ORCHESTRATION,
                "failure-signal.v1",
                data)).orElseThrow();

        assertEquals("failure-signal.v1", signal.schema());
        assertEquals("event-1", signal.eventId());
        assertEquals("hash:333333333333", signal.requestIdHash());
        assertEquals("hash:222222222222", signal.traceIdHash());
        assertEquals("LLM", signal.stage());
        assertEquals("HELD", signal.status());
        assertEquals("VERIFIED", signal.evidenceStatus());
        assertEquals("SILENT_FAILURE", signal.failureClass());
        assertEquals("HOLD", signal.action());
        assertEquals("silent_failure", signal.reasonCode());
        assertEquals("answer_held", signal.answerImpact());
        assertEquals("ENFORCE", signal.rolloutMode());
        assertTrue(signal.hardGuard());

        String rendered = signal.toString();
        assertFalse(rendered.contains("private-answer-marker"));
        assertFalse(rendered.contains("private-query-marker"));
        assertFalse(rendered.contains("raw-debug-message"));
        assertFalse(rendered.contains("raw-where-marker"));
        assertFalse(rendered.contains("raw-thread-marker"));
    }

    @Test
    void rejectsNonOrchestrationAndWrongSchemaEvents() {
        assertTrue(projector.project(event(
                DebugProbeType.GENERIC,
                "failure-signal.v1",
                validData())).isEmpty());
        assertTrue(projector.project(event(
                DebugProbeType.ORCHESTRATION,
                "debug-event.v1",
                validData())).isEmpty());
    }

    @Test
    void rejectsMalformedEnumAndReasonFieldsInsteadOfPassingRawValues() {
        Map<String, Object> invalidStatus = validData();
        invalidStatus.put("status", "HELD|private-row");
        Map<String, Object> invalidReason = validData();
        invalidReason.put("reasonCode", "bad reason\nprivate-row");

        assertTrue(projector.project(event(
                DebugProbeType.ORCHESTRATION,
                "failure-signal.v1",
                invalidStatus)).isEmpty());
        assertTrue(projector.project(event(
                DebugProbeType.ORCHESTRATION,
                "failure-signal.v1",
                invalidReason)).isEmpty());
    }

    @Test
    void rejectsSyntacticallyValidButUnregisteredReasonCode() {
        Map<String, Object> invalidReason = validData();
        invalidReason.put("reasonCode", "private_but_valid_token");

        assertTrue(projector.project(event(
                DebugProbeType.ORCHESTRATION,
                "failure-signal.v1",
                invalidReason)).isEmpty());
    }

    @Test
    void projectsExplicitObservationOnlyImpactForUnexecutedAction() {
        Map<String, Object> data = validData();
        data.put("status", "OBSERVED");
        data.put("failureClass", "ZERO_RESULT");
        data.put("action", "DEGRADE");
        data.put("reasonCode", "zero_result");
        data.put("answerImpact", "observation_only");
        data.put("hardGuard", false);

        RagControlFailureSignalProjector.FailureSignal signal = projector.project(event(
                DebugProbeType.ORCHESTRATION,
                "failure-signal.v1",
                data)).orElseThrow();

        assertEquals("OBSERVED", signal.status());
        assertEquals("observation_only", signal.answerImpact());
    }

    private static Map<String, Object> validData() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("stage", "LLM");
        data.put("status", "HELD");
        data.put("evidenceStatus", "VERIFIED");
        data.put("failureClass", "SILENT_FAILURE");
        data.put("action", "HOLD");
        data.put("reasonCode", "silent_failure");
        data.put("answerImpact", "answer_held");
        data.put("rolloutMode", "ENFORCE");
        data.put("hardGuard", true);
        return data;
    }

    private static DebugEvent event(
            DebugProbeType probe,
            String schema,
            Map<String, Object> inputData) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>(inputData);
        data.put("schema", schema);
        return new DebugEvent(
                "event-1",
                Instant.parse("2026-08-15T00:00:00Z"),
                1_776_211_200_000L,
                DebugEventLevel.WARN,
                probe,
                "fingerprint",
                "raw-debug-message",
                "hash:111111111111",
                "hash:222222222222",
                "hash:333333333333",
                "raw-thread-marker",
                "raw-where-marker",
                data,
                null,
                null);
    }
}
