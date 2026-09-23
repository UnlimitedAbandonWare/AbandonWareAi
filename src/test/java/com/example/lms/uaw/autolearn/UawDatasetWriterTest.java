package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawDatasetWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void appendWritesValidationMetadataAndEvaluationCriteria() throws Exception {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 4, true, 0.75d, acceptedValidation()));

        assertTrue(ok);
        String content = Files.readString(file.toPath());
        assertTrue(content.contains("\"validation\""));
        assertTrue(content.contains("\"questionType\":\"causal\""));
        assertTrue(content.contains("\"selfAskLanes\":[\"BQ\",\"ER\",\"RC\"]"));
        assertTrue(content.contains("\"sampleScore\":0.82"));
        assertTrue(content.contains("\"accepted\":true"));
        assertTrue(content.contains("\"contradictionScore\":0.0"));
        assertTrue(content.contains("\"contradictionCause\":\"unknown\""));
        assertTrue(content.contains("\"evaluationCriteria\""));
        assertTrue(content.contains("\"cause_effect_support\""));
        assertTrue(content.contains("\"thresholds\""));
        assertTrue(content.contains("\"contradictionMax\":0.6"));
        assertTrue(content.contains("\"runtime\""));
        assertTrue(content.contains("\"anomalies\""));
        assertTrue(content.contains("\"feedback\""));
        assertTrue(content.contains("\"needleRoi\""));
        assertTrue(content.contains("\"promoted\":true"));
    }

    @Test
    void appendRejectsFailedValidationMetadata() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        LearningSampleValidationMetadata rejected = new LearningSampleValidationMetadata(
                "long_tail",
                List.of("BQ"),
                0.33d,
                0.70d,
                0.40d,
                0.20d,
                new LearningSampleValidationMetadata.Requery(true, false),
                0.50d,
                0.10d,
                0.40d,
                List.of("contamination_risk"),
                List.of("bq_er_rc_lane_coverage"));

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 4, true, 0.75d, rejected));

        assertFalse(ok);
        assertFalse(file.exists());
    }

    @Test
    void appendRejectsUnpromotedNeedleRoiCandidateEvenWhenLegacyReasonsAreEmpty() {
        TraceStore.clear();
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        LearningSampleValidationMetadata rejected = new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.74d,
                0.20d,
                0.78d,
                0.0d,
                "unknown",
                new LearningSampleValidationMetadata.Requery(true, true),
                0.0d,
                0.0d,
                0.82d,
                List.of(),
                List.of("cause_effect_support"),
                LearningSampleValidationMetadata.Thresholds.defaults(),
                LearningSampleValidationMetadata.Runtime.defaults(),
                LearningSampleValidationMetadata.Anomalies.none(),
                LearningSampleValidationMetadata.Feedback.none(),
                new LearningSampleValidationMetadata.NeedleRoi(true, 0.40d, false, "signal_below_threshold"));

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 4, true, 0.75d, rejected));

        assertFalse(ok);
        assertFalse(file.exists());
        assertEquals("needle_roi_rejected",
                TraceStore.getString("uaw.autolearn.datasetWriter.lastFailureReason"));
        TraceStore.clear();
    }

    @Test
    void appendRecordsFailureReasonWithoutRawContent() {
        TraceStore.clear();
        UawDatasetWriter writer = writer();

        boolean ok = writer.append(null, "ds", "api_key=abcdsecret", "Bearer " + "abcdefghijklmnop",
                "gemma4:26b", 3, "s1", metadata());

        assertFalse(ok);
        Map<String, Object> trace = TraceStore.getAll();
        assertEquals("file_missing", trace.get("uaw.autolearn.datasetWriter.lastFailureReason"));
        String rendered = trace.toString();
        assertFalse(rendered.contains("abcdsecret"));
        assertFalse(rendered.contains("abcdefghijklmnop"));
        TraceStore.clear();
    }

    @Test
    void appendHashesFailureDetailBeforeWritingTrace() throws Exception {
        TraceStore.clear();
        UawDatasetWriter writer = writer();
        Path blockedParent = tempDir.resolve("ownerToken=raw-secret-detail");
        Files.writeString(blockedParent, "not a directory");

        boolean ok = writer.append(blockedParent.resolve("train_rag.jsonl").toFile(), "ds", "q", "a",
                "gemma4:26b", 3, "s1", metadata());

        assertFalse(ok);
        Object detail = TraceStore.get("uaw.autolearn.datasetWriter.lastFailureDetail");
        assertTrue(detail != null);
        assertTrue(String.valueOf(detail).startsWith("hash:"));
        assertFalse(TraceStore.getAll().toString().contains("raw-secret-detail"));
        TraceStore.clear();
    }

    @Test
    void appendMasksDisabledReasonBeforeWritingFailureTrace() {
        TraceStore.clear();
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();
        String fakeKey = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        String rawReason = "missing provider key " + fakeKey;

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "api3", rawReason, 3, true, 0.8d, acceptedValidation()));

        assertFalse(ok);
        Map<String, Object> trace = TraceStore.getAll();
        assertTrue(String.valueOf(trace.get("uaw.autolearn.datasetWriter.lastFailureReason")).startsWith("hash:"));
        assertFalse(String.valueOf(trace.get("uaw.autolearn.datasetWriter.lastFailureReason"))
                .contains(fakeKey));
        assertFalse(trace.toString().contains(fakeKey));
        TraceStore.clear();
    }

    @Test
    void appendRedactsSensitiveQuestionAndAnswerBeforeWriting() throws Exception {
        TraceStore.clear();
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        String rawSessionId = "raw-uaw-session-id";
        boolean ok = writer.append(file, "ds", "api_key=abcdsecret", "Bearer " + "abcdefghijklmnop",
                "gemma4:26b", 3, rawSessionId, metadata());

        assertTrue(ok);
        String content = Files.readString(file.toPath());
        assertFalse(content.contains("abcdsecret"));
        assertFalse(content.contains("abcdefghijklmnop"));
        assertFalse(content.contains(rawSessionId));
        assertFalse(content.contains("\"sessionId\""));
        assertTrue(content.contains("\"sessionHash\":\"" + SafeRedactor.hashValue(rawSessionId) + "\""));
        assertTrue(content.contains("\"redactionApplied\":true"));
        assertTrue(content.contains("\"redactionCount\":2"));
        assertEquals(2, TraceStore.get("uaw.autolearn.datasetWriter.lastRedactionCount"));
        TraceStore.clear();
    }

    @Test
    void trainRagIngestSupportsSessionHashOnlyWriterOutput() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java"));

        assertTrue(source.contains("Object sessionHash = m.get(\"sessionHash\");"));
        assertTrue(source.contains("sid = (sessionHash == null) ? \"\" : sessionHash.toString().trim();"));
    }

    @Test
    void concurrentAppendProducesValidJsonlLines() throws Exception {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int idx = i;
                tasks.add(() -> writer.append(file, "ds", "q" + idx, "a" + idx,
                        "gemma4:26b", 3, "s" + idx, metadata()));
            }
            var results = pool.invokeAll(tasks);
            for (var result : results) {
                assertTrue(result.get());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        List<String> lines = Files.readAllLines(file.toPath());
        assertEquals(10, lines.size());
        ObjectMapper om = new ObjectMapper();
        for (String line : lines) {
            assertTrue(om.readTree(line).has("id"));
        }
    }

    private static LearningSampleValidationMetadata acceptedValidation() {
        return new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.74d,
                0.30d,
                0.78d,
                new LearningSampleValidationMetadata.Requery(true, true),
                0.0d,
                0.0d,
                0.82d,
                List.of(),
                List.of("cause_effect_support", "alternative_cause_checked", "requery_confirmation_required"),
                LearningSampleValidationMetadata.Thresholds.defaults(),
                LearningSampleValidationMetadata.Runtime.defaults(),
                LearningSampleValidationMetadata.Anomalies.none(),
                LearningSampleValidationMetadata.Feedback.none(),
                new LearningSampleValidationMetadata.NeedleRoi(true, 0.82d, true, "accepted"));
    }

    private static UawDatasetWriter.TrainingMetadata metadata() {
        return new UawDatasetWriter.TrainingMetadata(
                "uaw_autolearn", "mixed", "", 4, true, 0.75d, acceptedValidation());
    }

    private static UawDatasetWriter writer() {
        return new UawDatasetWriter(new UawDatasetTrainingDataFilter(new UawDatasetFilterProperties(), null));
    }
}
