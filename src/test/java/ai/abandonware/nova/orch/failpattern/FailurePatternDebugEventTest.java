package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.example.lms.debug.*;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailurePatternDebugEventTest {
    @AfterEach void clear() { MDC.clear(); TraceStore.clear(); }
    private FailurePatternOrchestrator orchestrator(DebugEventStore store) {
        NovaFailurePatternProperties props = new NovaFailurePatternProperties();
        props.getJsonl().setReadEnabled(false);
        props.getJsonl().setWriteEnabled(false);
        return new FailurePatternOrchestrator(new FailurePatternDetector(), new FailurePatternMetrics(null, props),
                new FailurePatternJsonlWriter(new ObjectMapper(), props), new FailurePatternCooldownRegistry(),
                new ObjectMapper(), props, store);
    }
    @Test void existingLogPatternBecomesBoundedWebEventWithoutRawMessageOrLogger() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        MDC.put("requestId", "synthetic-request");
        MDC.put("traceId", "synthetic-trace");
        String privateText = "synthetic-private-input";
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        var orchestrator = orchestrator(store);
        orchestrator.onLogEvent(1000, "private.logger." + privateText, "WARN",
                "[NightmareBreaker] OPEN key=" + privateText + " payload=" + secret);
        var events = store.list(10);
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(DebugProbeType.ORCHESTRATION, event.probe());
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("CIRCUIT_OPEN", event.data().get("kind"));
        assertEquals(SafeRedactor.hashValue("synthetic-request"), event.requestId());
        assertEquals(SafeRedactor.hashValue("synthetic-trace"), event.traceId());
        assertFalse(event.toString().contains(privateText));
        assertFalse(event.toString().contains(secret));
        assertFalse(event.toString().contains("private.logger"));
        assertNull(event.error());
        assertTrue(event.data().containsKey("cooldownMs"));
        // Existing aggregation bounds repeated logs by canonical kind/source.
        for (int i = 0; i < 30; i++) orchestrator.onLogEvent(1001+i, "synthetic", "WARN",
                "[NightmareBreaker] OPEN key=" + privateText + i);
        assertTrue(store.list(100).size() < 31);
    }
    @Test void unmatchedLogsDoNotProduceEventsAndSinkFailureDoesNotChangeFeedback() {
        DebugEventStore store = mock(DebugEventStore.class);
        var orchestrator = orchestrator(store);
        orchestrator.onLogEvent(1000, "synthetic", "INFO", "ordinary synthetic line");
        verifyNoInteractions(store);
        doThrow(new IllegalStateException("synthetic private failure")).when(store)
                .emit(any(), any(), anyString(), anyString(), anyString(), anyMap(), isNull());
        assertDoesNotThrow(() -> orchestrator.onLogEvent(System.currentTimeMillis(), "synthetic", "WARN",
                "[Naver-Trace] Hard Timeout synthetic"));
        assertEquals("failurePattern.debugEventEmit", TraceStore.get("failpattern.suppressed.stage"));
        assertFalse(TraceStore.getAll().toString().contains("synthetic private failure"));
        assertTrue(orchestrator.isCoolingDown("web"));
    }
}
