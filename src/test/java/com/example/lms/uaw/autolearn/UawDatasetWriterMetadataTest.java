package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawDatasetWriterMetadataTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void appendRejectsProviderDisabledSamples() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "api3", "missing-key-or-unauthorized", 3, true, 0.8d));

        assertFalse(ok);
        assertFalse(file.exists());
    }

    @Test
    void appendRejectsFailedFinalGate() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 3, false, 0.8d));

        assertFalse(ok);
        assertFalse(file.exists());
    }

    @Test
    void appendRejectsQuarantinedValidationSamples() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();
        LearningSampleValidationMetadata quarantined = new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.80d,
                0.20d,
                0.80d,
                new LearningSampleValidationMetadata.Requery(true, true),
                0.0d,
                0.0d,
                0.90d,
                List.of(),
                List.of("cause_effect_support"),
                LearningSampleValidationMetadata.Thresholds.defaults(),
                LearningSampleValidationMetadata.Runtime.defaults(),
                LearningSampleValidationMetadata.Anomalies.none(),
                new LearningSampleValidationMetadata.Feedback(0.0d, "QUARANTINE"));

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 3, true, 0.80d, quarantined));

        assertFalse(ok);
        assertFalse(file.exists());
    }

    @Test
    void appendRejectsMissingValidationMetadata() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1");

        assertFalse(ok);
        assertFalse(file.exists());
        assertTrue(String.valueOf(TraceStore.get("uaw.autolearn.datasetWriter.lastFailureReason"))
                .equals("missing_validation_metadata"));
    }

    @Test
    void appendRejectsUnsafeDisabledReasonWithHashOnlyTrace() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn",
                        "api3",
                        "ownerToken=" + "sk-" + "redactioncontract1234567890",
                        3,
                        true,
                        0.8d));

        assertFalse(ok);
        assertFalse(file.exists());
        String tracedReason = String.valueOf(TraceStore.get("uaw.autolearn.datasetWriter.lastFailureReason"));
        assertTrue(tracedReason.startsWith("hash:"), tracedReason);
        assertFalse(tracedReason.contains("ownerToken"));
    }

    @Test
    void sourceContractNormalizesDisabledReasonBeforeRejectOrJsonlWrite() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/uaw/autolearn/UawDatasetWriter.java"));

        assertFalse(source.contains("return reject(meta.disabledReason(), redactedQuestion, redactedAnswer, modelUsed);"));
        assertFalse(source.contains("n.put(\"disabledReason\", meta.disabledReason());"));
        assertTrue(source.contains("safeReason(meta.disabledReason())"));
    }

    @Test
    void appendRejectsNullValidationMetadata() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 3, true, 0.90d, null));

        assertFalse(ok);
        assertFalse(file.exists());
    }

    @Test
    void appendRejectsAnomalousValidationEvenWhenVectorDecisionIsShadowReview() {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();
        LearningSampleValidationMetadata anomalous = new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.80d,
                0.20d,
                0.80d,
                new LearningSampleValidationMetadata.Requery(true, true),
                0.0d,
                0.0d,
                0.90d,
                List.of(),
                List.of("cause_effect_support"),
                LearningSampleValidationMetadata.Thresholds.defaults(),
                new LearningSampleValidationMetadata.Runtime(3, 3, 0.90d, 1.0d, 0.10d),
                new LearningSampleValidationMetadata.Anomalies(List.of("blackbox_provider_disabled"), false, false),
                new LearningSampleValidationMetadata.Feedback(0.0d, "SHADOW_REVIEW"));

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 3, true, 0.90d, anomalous));

        assertFalse(ok);
        assertFalse(file.exists());
    }

    @Test
    void appendWritesTrainingMetadata() throws Exception {
        UawDatasetWriter writer = writer();
        File file = tempDir.resolve("train_rag.jsonl").toFile();

        boolean ok = writer.append(file, "ds", "q", "a", "gemma4:26b", 3, "s1",
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn", "mixed", "", 4, true, 0.75d, acceptedValidation()));

        assertTrue(ok);
        String content = Files.readString(file.toPath());
        assertTrue(content.contains("\"branch\":\"uaw_autolearn\""));
        assertTrue(content.contains("\"provider\":\"mixed\""));
        assertTrue(content.contains("\"afterFilterCount\":4"));
        assertTrue(content.contains("\"finalGate\":true"));
        assertTrue(content.contains("\"contextDiversity\":0.75"));
        assertTrue(content.contains("\"contextContaminationScore\":0.0"));
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
                List.of("cause_effect_support", "alternative_cause_checked", "requery_confirmation_required"));
    }

    private static UawDatasetWriter writer() {
        return new UawDatasetWriter(new UawDatasetTrainingDataFilter(new UawDatasetFilterProperties(), null));
    }
}
