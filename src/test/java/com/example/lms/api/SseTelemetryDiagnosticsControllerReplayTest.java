package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.LoggingSseEventPublisher;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SseTelemetryDiagnosticsControllerReplayTest {
    @AfterEach
    void clearSyntheticTrace() { TraceStore.clear(); }

    @Test
    void globalOperationsRouteReplaysTwoHashedSessionsAndSessionlessEvent() {
        LoggingSseEventPublisher publisher = new LoggingSseEventPublisher(8);
        publisher.emit("fixture_a", Map.of("count", 1), "fixture-session-a");
        publisher.emit("fixture_b", Map.of("count", 2), "fixture-session-b");
        publisher.emit("fixture_global", Map.of("count", 3));
        SseTelemetryDiagnosticsController controller = new SseTelemetryDiagnosticsController(publisher);

        // Calls the controller publisher directly. HTTP/admin authentication is a separate contract.
        List<Map<String, Object>> replay = controller.stream().take(4).collectList().block(Duration.ofSeconds(2));

        assertNotNull(replay);
        assertEquals(4, replay.size());
        assertEquals(1, replay.stream().filter(e -> "hello".equals(e.get("type"))).count());
        Map<String, Object> first = event(replay, "fixture_a");
        Map<String, Object> second = event(replay, "fixture_b");
        assertEquals(SafeRedactor.hashValue("fixture-session-a"), first.get("sessionId"));
        assertEquals(SafeRedactor.hashValue("fixture-session-b"), second.get("sessionId"));
        assertNotEquals(first.get("sessionId"), second.get("sessionId"));
        assertFalse(event(replay, "fixture_global").containsKey("sessionId"));
        assertFalse(replay.toString().contains("fixture-session-a"));
        assertFalse(replay.toString().contains("fixture-session-b"));
    }

    private static Map<String, Object> event(List<Map<String, Object>> replay, String type) {
        return replay.stream().filter(e -> type.equals(e.get("type"))).findFirst().orElseThrow();
    }
}
