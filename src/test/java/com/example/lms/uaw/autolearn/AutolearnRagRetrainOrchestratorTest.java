package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.uaw.autolearn.ingest.TrainRagIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutolearnRagRetrainOrchestratorTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void maybeRetrainSkipsTinyAcceptedBatches() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setMinAcceptedToTrain(10);
        FakeIngestService ingest = new FakeIngestService(props);
        AutolearnRagRetrainOrchestrator orchestrator =
                new AutolearnRagRetrainOrchestrator(ingest, props);

        int ingested = orchestrator.maybeRetrain(Path.of("train_rag.jsonl"), 1, null);

        assertEquals(0, ingested);
        assertEquals(0, ingest.calls);
        assertEquals("below_min_accepted", TraceStore.getString("uaw.retrain.skip.reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> check = (Map<String, Object>) TraceStore.get("uaw.retrain.check");
        assertNotNull(check);
        assertEquals(1, check.get("acceptedCount"));
        assertEquals(10, check.get("minAcceptedToTrain"));
    }

    @Test
    void maybeRetrainRunsWhenAcceptedBatchMeetsMinimum() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setMinAcceptedToTrain(10);
        FakeIngestService ingest = new FakeIngestService(props);
        AutolearnRagRetrainOrchestrator orchestrator =
                new AutolearnRagRetrainOrchestrator(ingest, props);

        int ingested = orchestrator.maybeRetrain(Path.of("train_rag.jsonl"), 10, null);

        assertEquals(7, ingested);
        assertEquals(1, ingest.calls);
        assertEquals(7L, TraceStore.getLong("uaw.retrain.ingest.count"));
        assertEquals("", TraceStore.getString("uaw.retrain.skip.reason"));
    }

    @Test
    void maybeRetrainRecordsDailyCapSkipReason() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setMinAcceptedToTrain(1);
        props.getRetrain().setMaxRunsPerDay(1);
        FakeIngestService ingest = new FakeIngestService(props);
        AutolearnRagRetrainOrchestrator orchestrator =
                new AutolearnRagRetrainOrchestrator(ingest, props);

        assertEquals(7, orchestrator.maybeRetrain(Path.of("train_rag.jsonl"), 1, null));
        int capped = orchestrator.maybeRetrain(Path.of("train_rag.jsonl"), 1, null);

        assertEquals(0, capped);
        assertEquals(1, ingest.calls);
        assertEquals("daily_cap", TraceStore.getString("uaw.retrain.skip.reason"));
    }

    @Test
    void retrainLogsDoNotWriteRawDatasetFileName() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/uaw/autolearn/AutolearnRagRetrainOrchestrator.java"));

        assertFalse(source.contains("datasetFile={} datasetPathHash={}"));
        assertFalse(source.contains("datasetFileName(jsonl), hashOrEmpty(jsonl)"));
        assertFalse(source.contains("data.put(\"fileName\", jsonl == null || jsonl.getFileName() == null ? \"\" : safe(jsonl.getFileName().toString()));"));
        assertTrue(source.contains("datasetFileHash={} datasetFileLength={} datasetPathHash={}"));
        assertTrue(source.contains("datasetFileHash(jsonl), datasetFileLength(jsonl), hashOrEmpty(jsonl)"));
        assertTrue(source.contains("data.put(\"fileNameHash\", datasetFileHash(jsonl));"));
        assertTrue(source.contains("data.put(\"fileNameLength\", datasetFileLength(jsonl));"));
    }

    private static final class FakeIngestService extends TrainRagIngestService {
        int calls;

        private FakeIngestService(UawAutolearnProperties props) {
            super(null, null, props);
        }

        @Override
        public int ingestNewSamples(Path jsonlPath, String datasetName, PreemptionToken token) {
            calls++;
            return 7;
        }
    }
}
