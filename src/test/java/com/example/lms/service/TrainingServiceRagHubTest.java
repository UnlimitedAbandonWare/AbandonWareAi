package com.example.lms.service;

import com.example.lms.repository.MemoryRepository;
import com.example.lms.repository.SampleRepository;
import com.example.lms.repository.TrainingJobRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadata;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingServiceRagHubTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void recordRagSampleDelegatesToTrainRagWriterWithValidationMetadata() throws Exception {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        when(writer.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getDataset().setPath("data/train_rag.jsonl");
        props.getDataset().setName("training-rag");

        TrainingService service = service();
        configureTrainingRag(service, writer, props);

        recordRagSample(service,
                "raw-session-id",
                "How should Training RAG store answer samples?",
                "Only accepted PromptBuilder-backed answers are collected.",
                List.of("https://docs.example/a", "https://docs.example/b"),
                0.86d);

        ArgumentCaptor<UawDatasetWriter.TrainingMetadata> metadata =
                ArgumentCaptor.forClass(UawDatasetWriter.TrainingMetadata.class);
        verify(writer).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), metadata.capture());
        LearningSampleValidationMetadata validation = metadata.getValue().validation();

        assertEquals("training_rag", metadata.getValue().branch());
        assertEquals("internal", metadata.getValue().provider());
        assertTrue(validation.accepted());
        assertEquals(0.86d, validation.sampleScore(), 0.0001d);
        assertEquals(2, validation.runtime().evidenceCount());
        assertEquals(Boolean.TRUE, TraceStore.get("training.rag.recorded"));
        assertTrue(String.valueOf(TraceStore.get("training.rag.sessionHash")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-session-id"));
    }

    @Test
    void recordFailurePatternAndPlateFeedbackStoreRedactedTrainingTrace() throws Exception {
        TrainingService service = service();

        recordFailurePattern(service, "raw-session-id", "timeout", "ownerToken=secret-slot", 0.42d);
        recordPlatePerformance(service, "AP10_TRAIN_RAG", true, 0.91d);

        assertEquals(Boolean.TRUE, TraceStore.get("training.cfvm.recorded"));
        assertEquals("timeout", TraceStore.get("training.cfvm.failureType"));
        assertEquals(0.42d, TraceStore.get("training.cfvm.boltzmannWeight"));
        assertTrue(String.valueOf(TraceStore.get("training.cfvm.rawSlotHash")).startsWith("hash:"));
        assertEquals("AP10_TRAIN_RAG", TraceStore.get("training.plate.feedback"));
        assertEquals(Boolean.TRUE, TraceStore.get("training.plate.passed"));
        assertEquals(0.91d, TraceStore.get("training.plate.rewardScore"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-session-id"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret-slot"));
    }

    @Test
    void findTrainingRagSamplesReadsAcceptedJsonlWithoutRawPathOrQueryTrace() throws Exception {
        Path dir = Files.createTempDirectory("training-rag-probe");
        Path dataset = dir.resolve("train_rag.jsonl");
        Files.writeString(dataset,
                "{\"finalGate\":true,\"question\":\"RAG citation policy\",\"answer\":\"Use grounded citations only.\",\"validation\":{\"accepted\":true}}\n"
                        + "{not-json}\n"
                        + "{\"finalGate\":false,\"question\":\"RAG citation policy\",\"answer\":\"Rejected sample.\",\"validation\":{\"accepted\":true}}\n",
                StandardCharsets.UTF_8);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getDataset().setPath(dataset.toString());

        TrainingService service = service();
        configureTrainingRag(service, mock(UawDatasetWriter.class), props);

        List<String> samples = service.findTrainingRagSamples("grounded citation", 3);

        assertEquals(1, samples.size());
        assertTrue(samples.get(0).contains("Use grounded citations only."));
        assertEquals(Boolean.TRUE, TraceStore.get("training.rag.probe.available"));
        assertEquals("ok", TraceStore.get("training.rag.probe.reason"));
        assertEquals(1, TraceStore.get("training.rag.probe.returnedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("training.rag.probe.lineParseSkipped"));
        assertEquals("invalid_json", TraceStore.get("training.rag.probe.lineParseSkipped.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(dataset.toString()));
        assertFalse(trace.contains("grounded citation"));
        assertFalse(trace.contains("Rejected sample."));
        assertFalse(trace.contains("{not-json}"));
    }

    private static TrainingService service() {
        return new TrainingService(
                mock(SampleRepository.class),
                mock(MemoryRepository.class),
                mock(TrainingJobRepository.class),
                Runnable::run,
                new TransactionTemplate());
    }

    private static void configureTrainingRag(TrainingService service,
                                             UawDatasetWriter writer,
                                             UawAutolearnProperties props) throws Exception {
        Method method = TrainingService.class.getDeclaredMethod(
                "configureTrainingRag", UawDatasetWriter.class, UawAutolearnProperties.class);
        method.setAccessible(true);
        method.invoke(service, writer, props);
    }

    private static void recordRagSample(TrainingService service,
                                        String sessionId,
                                        String query,
                                        String answer,
                                        List<String> citationUrls,
                                        double qualityScore) throws Exception {
        Method method = TrainingService.class.getDeclaredMethod(
                "recordRagSample", String.class, String.class, String.class, List.class, double.class);
        method.invoke(service, sessionId, query, answer, citationUrls, qualityScore);
    }

    private static void recordFailurePattern(TrainingService service,
                                             String sessionId,
                                             String failureType,
                                             String rawSlot,
                                             double boltzmannWeight) throws Exception {
        Method method = TrainingService.class.getDeclaredMethod(
                "recordFailurePattern", String.class, String.class, String.class, double.class);
        method.invoke(service, sessionId, failureType, rawSlot, boltzmannWeight);
    }

    private static void recordPlatePerformance(TrainingService service,
                                               String plateId,
                                               boolean passed,
                                               double rewardScore) throws Exception {
        Method method = TrainingService.class.getDeclaredMethod(
                "recordPlatePerformance", String.class, boolean.class, double.class);
        method.invoke(service, plateId, passed, rewardScore);
    }
}
