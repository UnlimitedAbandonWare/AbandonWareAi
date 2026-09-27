package com.example.lms.probe;

import com.example.lms.probe.dto.SearchProbeResponse;
import com.example.lms.probe.dto.SearchProbeRequest;
import com.example.lms.probe.dto.StageSnapshot;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceSnapshotStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SearchProbeControllerTraceSnapshotTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void searchEndpointCapturesTraceSnapshotAfterProbeExecution() throws Exception {
        SearchProbeService service = request -> {
            TraceStore.put("hypernova.twpmP", 4.2d);
            TraceStore.put("hypernova.cvarPhi", 0.7d);
            return new SearchProbeResponse();
        };
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        when(snapshotStore.captureCurrent(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertEquals(4.2d, TraceStore.get("hypernova.twpmP"));
                    assertEquals(0.7d, TraceStore.get("hypernova.cvarPhi"));
                    return "snap-search-001";
                });
        SearchProbeController controller = new SearchProbeController(service, true, "probe-token-value");
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/probe/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Snapshot-Id", "snap-search-001"));
    }

    @Test
    void searchEndpointRestoresSelectedTraceStageBeforeSnapshotCapture() throws Exception {
        SearchProbeService service = request -> {
            SearchProbeResponse response = new SearchProbeResponse();
            StageSnapshot stage = new StageSnapshot();
            stage.name = "trace:selected";
            stage.params.put("trace.size", 99);
            stage.params.put("hypernova.twpmP", 5.1d);
            stage.params.put("hypernova.cvarPhi", 0.62d);
            response.stages.add(stage);
            TraceStore.clear();
            return response;
        };
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        when(snapshotStore.captureCurrent(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertEquals(5.1d, TraceStore.get("hypernova.twpmP"));
                    assertEquals(0.62d, TraceStore.get("hypernova.cvarPhi"));
                    assertEquals(Boolean.TRUE, TraceStore.get("traceSnapshot.probe.search.restoredSelectedTrace"));
                    return "snap-search-stage-001";
                });
        SearchProbeController controller = new SearchProbeController(service, true, "probe-token-value");
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/probe/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Snapshot-Id", "snap-search-stage-001"));
    }

    @Test
    void searchEndpointProjectsSnapshotCaptureSkipReasonIntoSelectedTrace() {
        SearchProbeResponse response = new SearchProbeResponse();
        StageSnapshot stage = new StageSnapshot();
        stage.name = "trace:selected";
        stage.params.put("hypernova.twpmP", 5.1d);
        response.stages.add(stage);
        SearchProbeService service = request -> response;
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        when(snapshotStore.captureCurrent(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    TraceStore.put("trace.snapshot.capture.skipped", true);
                    TraceStore.put("trace.snapshot.capture.skipReason", "disabled");
                    TraceStore.put("trace.snapshot.capture.reason", "probe_search");
                    return null;
                });
        SearchProbeController controller = new SearchProbeController(service, true, "probe-token-value");
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);

        ResponseEntity<?> entity = controller.search(
                new SearchProbeRequest(),
                "probe-token-value",
                mock(HttpServletRequest.class));

        assertEquals(200, entity.getStatusCode().value());
        assertEquals(null, entity.getHeaders().getFirst("X-Trace-Snapshot-Id"));
        assertEquals(false, stage.params.get("traceSnapshot.probe.search.headerPresent"));
        assertEquals(false, stage.params.get("traceSnapshot.probe.search.captured"));
        assertEquals("disabled", stage.params.get("traceSnapshot.probe.search.missingReason"));
        assertEquals(true, stage.params.get("trace.snapshot.capture.skipped"));
        assertEquals("disabled", stage.params.get("trace.snapshot.capture.skipReason"));
        assertEquals("probe_search", stage.params.get("trace.snapshot.capture.reason"));
    }

    @Test
    void searchEndpointCapturesRedactedTraceMemoryDiagnosticsWhenServiceFails() {
        String rawFailure = "private trace memory loader secret sk-local-probe-value";
        SearchProbeService service = request -> {
            TraceStore.put("traceMemory.virtualCheckpoint.beforeService", "raw_snapshot");
            throw new IllegalStateException(rawFailure);
        };
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        when(snapshotStore.captureCurrent(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertEquals(Boolean.TRUE, TraceStore.get("traceSnapshot.probe.search.serviceFailed"));
                    assertEquals("serviceRun", TraceStore.get("traceSnapshot.probe.search.failedStage"));
                    assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.probe.search.serviceFailed"));
                    assertEquals("IllegalStateException", TraceStore.get("traceMemory.probe.search.errorType"));
                    assertFalse(String.valueOf(TraceStore.getAll()).contains(rawFailure));
                    return "snap-search-failure-001";
                });
        DebugEventStore debugEventStore = new DebugEventStore();
        SearchProbeController controller = new SearchProbeController(service, true, "probe-token-value");
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);
        ReflectionTestUtils.setField(controller, "debugEventStore", debugEventStore);

        ResponseEntity<?> entity = controller.search(
                new SearchProbeRequest(),
                "probe-token-value",
                mock(HttpServletRequest.class));

        assertEquals(500, entity.getStatusCode().value());
        assertEquals("snap-search-failure-001", entity.getHeaders().getFirst("X-Trace-Snapshot-Id"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceSnapshot.probe.search.serviceFailed"));
        assertEquals("serviceRun", TraceStore.get("traceMemory.probe.search.failedStage"));
        assertTrue(String.valueOf(TraceStore.get("traceMemory.probe.search.errorHash")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawFailure));
        assertEquals(1, debugEventStore.listByProbe(DebugProbeType.TRACE_MEMORY, 5).size());
        assertFalse(String.valueOf(debugEventStore.list(10)).contains(rawFailure));
    }
}
