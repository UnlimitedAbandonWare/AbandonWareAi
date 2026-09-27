package com.example.lms.uaw.autolearn.ingest;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainRagMixedDecisionCountBoundaryTest {
    @TempDir Path temporary;
    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test
    void successfulProjectionCountIncludesRejectedAndQuarantinedSyntheticInputs() throws Exception {
        TraceStore.clear();
        var vector = mock(VectorStoreService.class);
        var sid = mock(VectorSidService.class);
        var properties = new UawAutolearnProperties();
        properties.getRetrain().setIngestStatePath(temporary.resolve("state.json").toString());
        properties.getRetrain().setMaxIngestLinesPerRun(12);
        Path dataset = temporary.resolve("synthetic.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder rows = new StringBuilder();
        for (String category : List.of("accepted", "rejected", "quarantined")) {
            Map<String, Object> validation = new HashMap<>();
            validation.put("accepted", !category.equals("rejected"));
            validation.put("sampleScore", 0.9d);
            validation.put("contaminationScore", 0.01d);
            validation.put("legacyContextScore", 0.0d);
            validation.put("contradictionScore", 0.0d);
            validation.put("rejectReasons", category.equals("rejected") ? List.of("validation_rejected") : List.of());
            validation.put("feedback", Map.of("vectorDecision", category.equals("quarantined") ? "QUARANTINE" : "SHADOW_REVIEW"));
            rows.append(mapper.writeValueAsString(Map.of("question", "synthetic question " + category,
                    "answer", "synthetic answer " + category, "sessionId", "synthetic-session", "validation", validation))).append('\n');
        }
        Files.writeString(dataset, rows.toString());
        var service = new TrainRagIngestService(vector, sid, properties);
        int returned = service.ingestNewSamples(dataset, "synthetic-dataset", () -> false);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(vector, times(3)).enqueue(anyString(), anyString(), anyString(), metadata.capture());
        verify(vector).flush();
        List<Map<String, Object>> projected = metadata.getAllValues();
        long validationAccepted = count(projected, VectorMetaKeys.META_LEARNING_VALIDATION_DECISION, "accepted");
        long validationRejected = count(projected, VectorMetaKeys.META_LEARNING_VALIDATION_DECISION, "rejected");
        assertEquals(1, validationAccepted);
        assertEquals(2, validationRejected);
        assertEquals(1, count(projected, VectorMetaKeys.META_AGENT_HANDOFF_DECISION, "ACCEPTED"));
        assertEquals(2, count(projected, VectorMetaKeys.META_AGENT_HANDOFF_DECISION, "QUARANTINE"));
        assertEquals(0, count(projected, VectorMetaKeys.META_AGENT_HANDOFF_DECISION, "REJECTED"));
        assertEquals(3, count(projected, "vector_projection_mode", "METADATA_ONLY"));
        assertEquals(3, count(projected, VectorMetaKeys.META_VERIFIED, "false"));
        assertEquals(3, count(projected, VectorMetaKeys.META_SHADOW_WRITE, "true"));
        assertEquals(3, returned, "current return counts all successful projections");
        assertEquals(3L, TraceStore.getLong("uaw.retrain.ingest.count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) TraceStore.get("uaw.retrain.ingest.summary");
        assertEquals(3, summary.get("acceptedDocs"));
        assertEquals(3, summary.get("parsedLines"));
        assertEquals(3, summary.get("queuedDocs"));
        assertEquals(0, summary.get("failedBatches"));
        assertTrue(returned > validationAccepted, "canonical accepted-only count is not satisfied");
        assertEquals(0, service.ingestNewSamples(dataset, "synthetic-dataset", () -> false), "checkpoint replay adds no projection");
        verifyNoMoreInteractions(vector);
        verifyNoInteractions(sid);
        System.out.println("AL02_COUNTS inputAccepted=1 inputRejected=1 inputQuarantined=1"
                + " validationAccepted=1 validationRejected=2 handoffAccepted=1 handoffQuarantine=2 handoffRejected=0"
                + " returned=3 traceAcceptedDocs=3 parsed=3 queued=3 failedBatches=0 replay=0"
                + " canonicalAcceptedOnlySatisfied=false wholePipelineClaim=false");
    }

    private static long count(List<Map<String, Object>> rows, String key, String value) {
        return rows.stream().filter(row -> value.equals(String.valueOf(row.get(key)))).count();
    }
}
