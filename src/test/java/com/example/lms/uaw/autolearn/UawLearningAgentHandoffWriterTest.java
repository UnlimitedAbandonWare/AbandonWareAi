package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UawLearningAgentHandoffWriterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void recordSampleWritesRedactedJsonlAndManifest() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String supabaseSecret = "sb_secret_" + "handoffpreview123456";
        String question = "How to debug? sk-1234567890abcdef ownerToken=secret-value " + supabaseSecret;
        String answer = "Use diagnostics. Bearer " + "abc.def.ghi password=plain-text " + supabaseSecret;

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                question,
                answer,
                "gemma4:26b",
                3,
                metadata(LearningSampleValidationMetadata.empty(), true),
                true);

        Path accepted = tempDir.resolve("handoff/accepted.jsonl");
        Path manifest = tempDir.resolve("handoff/manifest.json");
        assertTrue(Files.exists(accepted));
        assertTrue(Files.exists(manifest));

        String line = Files.readString(accepted, StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(line);
        assertEquals("ACCEPTED", node.path("decision").asText());
        assertEquals(SafeRedactor.hashValue("session-raw-123"), node.path("sessionHash").asText());
        assertEquals(SafeRedactor.hashValue(question), node.path("questionHash").asText());
        assertEquals(SafeRedactor.hashValue(answer), node.path("answerHash").asText());
        assertEquals("ACCEPTED", node.path("outcome").asText());
        assertFalse(line.contains("sk-1234567890abcdef"));
        assertFalse(line.contains("ownerToken"));
        assertFalse(line.contains("secret-value"));
        assertFalse(line.contains("Bearer " + "abc.def.ghi"));
        assertFalse(line.contains("password=plain-text"));
        assertFalse(line.contains(supabaseSecret));
        assertFalse(line.contains("sb_secret_"));
        JsonNode manifestNode = objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        assertTrue(manifestNode.path("priorityFiles").toString().contains("rejected.jsonl"));
        assertFalse(manifestNode.path("rawDatasetIsDb").asBoolean(true));
        assertFalse(manifestNode.path("vectorDbIsTrainingSource").asBoolean(true));
        assertTrue(manifestNode.path("reviewInstruction").asText().contains("Do not overwrite train_rag.jsonl"));
        assertFalse(manifestNode.has("rootDir"));
        assertEquals(SafeRedactor.hashValue("handoff"), manifestNode.path("rootDirHash").asText());
        assertEquals("handoff".length(), manifestNode.path("rootDirLength").asInt());

        Map<String, Object> summary = writer.manifestSummary();
        assertFalse(summary.containsKey("rootDir"));
        assertEquals(SafeRedactor.hashValue("handoff"), summary.get("rootDirHash"));
        assertEquals("handoff".length(), summary.get("rootDirLength"));
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) summary.get("files");
        @SuppressWarnings("unchecked")
        Map<String, Object> acceptedSummary = (Map<String, Object>) files.get("accepted");
        assertFalse(acceptedSummary.containsKey("fileName"));
        assertEquals(SafeRedactor.hashValue("accepted.jsonl"), acceptedSummary.get("fileNameHash"));
        assertEquals("accepted.jsonl".length(), acceptedSummary.get("fileNameLength"));
        assertEquals(true, acceptedSummary.get("exists"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "synthetic_email, al07@example.invalid",
            "synthetic_phone, +1-202-555-0147",
            "synthetic_address_like, 17 Synthetic Placeholder Road"
    })
    void syntheticCategoriesTraverseBothTemporarySerializationBoundaries(
            String category, String marker) throws Exception {
        // Characterize admission and persistence; the normative PII policy is still pending.
        UawDatasetWriter datasetWriter = new UawDatasetWriter(
                new UawDatasetTrainingDataFilter(new UawDatasetFilterProperties(), null));
        UawLearningAgentHandoffWriter handoffWriter = new UawLearningAgentHandoffWriter(props());
        Path training = tempDir.resolve("train_rag.jsonl");
        String question = "Explain validation of this entirely synthetic record: " + marker;
        String answer = "Validate the supplied synthetic record, preserve its evidence, "
                + "and check the result before admitting a training sample.";
        UawDatasetWriter.TrainingMetadata accepted =
                metadata(LearningSampleValidationMetadata.empty(), true);

        boolean admitted = datasetWriter.append(training.toFile(), "al07-synthetic", question,
                answer, "synthetic-model", 3, "synthetic-session", accepted);
        handoffWriter.recordSample("synthetic-session", "al07-synthetic", question, answer,
                "synthetic-model", 3, accepted, admitted);

        assertTrue(admitted, "synthetic sample must reach the real serialization boundary");
        List<String> trainingRows = Files.readAllLines(training, StandardCharsets.UTF_8);
        List<String> handoffRows = Files.readAllLines(
                tempDir.resolve("handoff/accepted.jsonl"), StandardCharsets.UTF_8);
        assertEquals(1, trainingRows.size());
        assertEquals(1, handoffRows.size());
        assertFalse(Files.exists(tempDir.resolve("handoff/rejected.jsonl")));
        assertTrue(objectMapper.readTree(trainingRows.get(0)).isObject());
        JsonNode handoff = objectMapper.readTree(handoffRows.get(0));
        assertEquals("ACCEPTED", handoff.path("decision").asText());
        assertEquals("ACCEPTED", handoff.path("outcome").asText());
        assertTrue(handoff.path("writerOk").asBoolean());

        // Emit category/count observations only, never the synthetic marker or serialized rows.
        System.out.printf("AL07_CHAR category=%s admitted=%d trainingRows=%d acceptedRows=%d "
                        + "rejectedRows=0 trainingMarkerRows=%d handoffMarkerRows=%d%n",
                category, admitted ? 1 : 0, trainingRows.size(), handoffRows.size(),
                trainingRows.stream().filter(row -> row.contains(marker)).count(),
                handoffRows.stream().filter(row -> row.contains(marker)).count());
    }

    @Test
    void recordSampleClassifiesQuarantineBeforeWriterFailure() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        LearningSampleValidationMetadata quarantine = LearningSampleValidationMetadata.empty()
                .withFeedback(new LearningSampleValidationMetadata.Feedback(0.0d, "QUARANTINE"));

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "answer",
                "gemma4:26b",
                3,
                metadata(quarantine, true),
                false);

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        assertEquals("QUARANTINE", node.path("decision").asText());
        assertEquals("REJECTED", node.path("outcome").asText());
        assertEquals("vector_quarantine", node.path("failureReason").asText());
    }

    @Test
    void recordSkippedSampleKeepsOutcomeAndFailureReason() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);

        writer.recordSkippedSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "",
                "",
                0,
                null,
                "SKIPPED",
                "insufficient_evidence");

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        assertEquals("SKIPPED", node.path("decision").asText());
        assertEquals("SKIPPED", node.path("outcome").asText());
        assertEquals("insufficient_evidence", node.path("failureReason").asText());
    }

    @Test
    void recordHeldSamplePersistsOnlyPrehashedEvidence() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String session = "HELD_PRIVATE_SESSION_MARKER";
        String dataset = "HELD_PRIVATE_DATASET_MARKER";
        String question = "HELD_PRIVATE_QUESTION_MARKER";
        String answer = "HELD_PRIVATE_ANSWER_MARKER";
        String model = "HELD_PRIVATE_MODEL_MARKER";
        String sample = question + '\0' + answer + '\0' + model;

        writer.recordHeldSample(
                SafeRedactor.hashValue(sample),
                SafeRedactor.hashValue(session),
                SafeRedactor.hashValue(dataset),
                SafeRedactor.hashValue(question),
                SafeRedactor.hashValue(answer),
                SafeRedactor.hashValue(model),
                7);

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        assertEquals("uaw_autolearn_hold", node.path("type").asText());
        assertEquals("HOLD", node.path("decision").asText());
        assertEquals("HELD", node.path("outcome").asText());
        assertEquals("rag_control_hold", node.path("failureReason").asText());
        assertEquals(SafeRedactor.hashValue(sample), node.path("sampleHash").asText());
        assertEquals(SafeRedactor.hashValue(session), node.path("sessionHash").asText());
        assertEquals(SafeRedactor.hashValue(dataset), node.path("datasetHash").asText());
        assertEquals(SafeRedactor.hashValue(question), node.path("questionHash").asText());
        assertEquals(SafeRedactor.hashValue(answer), node.path("answerHash").asText());
        assertEquals(SafeRedactor.hashValue(model), node.path("modelHash").asText());
        assertEquals(7, node.path("evidenceCount").asInt());
        assertFalse(node.path("containsFullText").asBoolean(true));
        for (String marker : java.util.List.of(session, dataset, question, answer, model)) {
            assertFalse(line.contains(marker));
        }
        for (String forbidden : java.util.List.of(
                "questionPreview", "answerPreview", "dataset", "model", "branch", "provider",
                "disabledReason", "validation", "traceHints")) {
            assertFalse(node.has(forbidden), forbidden);
        }
        Method api = UawLearningAgentHandoffWriter.class.getDeclaredMethod(
                "recordHeldSample",
                String.class, String.class, String.class, String.class, String.class, String.class, int.class);
        assertFalse(Modifier.isPublic(api.getModifiers()));
        assertFalse(Modifier.isProtected(api.getModifiers()));
        assertFalse(Modifier.isPrivate(api.getModifiers()));
    }

    @Test
    void recordHeldSampleRejectsNonHashInputs() throws Exception {
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props());
        String raw = "RAW_HELD_INPUT_MUST_NOT_PERSIST";

        writer.recordHeldSample(raw, raw, raw, raw, raw, raw, -7);

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        assertFalse(line.contains(raw));
        assertEquals("", node.path("sampleHash").asText());
        assertEquals("", node.path("sessionHash").asText());
        assertEquals("", node.path("datasetHash").asText());
        assertEquals("", node.path("questionHash").asText());
        assertEquals("", node.path("answerHash").asText());
        assertEquals("", node.path("modelHash").asText());
        assertEquals(0, node.path("evidenceCount").asInt());
    }

    @Test
    void recordSampleHashesUnsafeDisabledReason() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String unsafeReason = "ownerToken=" + "sk-" + "handoffredaction1234567890";

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "answer",
                "gemma4:26b",
                3,
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn",
                        "local",
                        unsafeReason,
                        3,
                        true,
                        0.90d,
                        LearningSampleValidationMetadata.empty()),
                true);

        String line = Files.readString(tempDir.resolve("handoff/accepted.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        String disabledReason = node.path("disabledReason").asText();
        assertTrue(disabledReason.startsWith("hash:"), disabledReason);
        assertFalse(line.contains("ownerToken"));
        assertFalse(line.contains("handoffredaction1234567890"));
    }

    @Test
    void recordSkippedSampleHashesUnsafeExplicitFailureReason() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String unsafeReason = "client_secret=" + "sk-" + "handofffailure1234567890";

        writer.recordSkippedSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "",
                "",
                0,
                null,
                "SKIPPED",
                unsafeReason);

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        String failureReason = node.path("failureReason").asText();
        assertTrue(failureReason.startsWith("hash:"), failureReason);
        assertFalse(line.contains("client_secret"));
        assertFalse(line.contains("handofffailure1234567890"));
    }

    @Test
    void recordSampleEnforcesMaxLineBytes() throws Exception {
        UawAutolearnProperties props = props();
        props.getAgentHandoff().setMaxLineBytes(512);
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String large = "x".repeat(10_000);

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                large,
                large,
                "gemma4:26b",
                3,
                metadata(LearningSampleValidationMetadata.empty(), true),
                true);

        String line = Files.readString(tempDir.resolve("handoff/accepted.jsonl"), StandardCharsets.UTF_8).trim();
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 512);
        assertTrue(line.contains("truncated"));
    }

    @Test
    void recordSampleFailsSoftOnWriteError() throws Exception {
        UawAutolearnProperties props = props();
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        props.getAgentHandoff().setRejectedPath(blocker.resolve("rejected.jsonl").toString());
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "answer",
                "gemma4:26b",
                3,
                metadata(rejectedValidation(), true),
                false);

        assertEquals("sample_write_failed", TraceStore.get("uaw.agent.handoff.status"));
        String error = String.valueOf(TraceStore.get("uaw.agent.handoff.error"));
        assertFalse(error.isBlank());
        assertTrue(error.startsWith("hash:"), error);
        assertFalse(error.contains("FileAlreadyExistsException"));
        assertFalse(error.contains("java.nio.file"));
    }

    @Test
    void manifestSummaryHashesFileStatErrors() throws Exception {
        UawAutolearnProperties props = props();
        Files.createDirectories(tempDir.resolve("handoff/accepted.jsonl"));
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);

        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) writer.manifestSummary().get("files");
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted = (Map<String, Object>) files.get("accepted");
        String error = String.valueOf(accepted.get("error"));

        assertFalse(accepted.containsKey("fileName"));
        assertTrue(accepted.containsKey("error"));
        assertTrue(error.startsWith("hash:"), error);
        assertFalse(error.contains(tempDir.toString()));
        assertFalse(error.contains("accepted.jsonl"));
    }

    @Test
    void recordCycleWritesHashOnlyDatasetFileDiagnostics() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String datasetPath = tempDir.resolve("private").resolve("train_rag.jsonl").toString();
        AutoLearnCycleResult result = new AutoLearnCycleResult(
                3,
                1,
                false,
                datasetPath,
                0.67d,
                0.05d,
                false,
                "provider_disabled",
                "BLOCK_RETRAIN");
        UawAutolearnQualityTracker.CycleDiagnostics cycle =
                new UawAutolearnQualityTracker.CycleDiagnostics(
                        3,
                        0.67d,
                        0.40d,
                        0.72d,
                        0.10d,
                        0.05d,
                        0.35d,
                        false,
                        "provider_disabled",
                        "BLOCK_RETRAIN",
                        0.33d,
                        Map.of("provider_disabled", 2),
                        java.util.List.of("error_rate_threshold"));

        writer.recordCycle("cycle-session-raw", datasetPath, result, cycle);

        String cycleLine = Files.readString(tempDir.resolve("handoff/cycles.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode cycleNode = objectMapper.readTree(cycleLine);
        assertFalse(cycleNode.has("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), cycleNode.path("datasetFileHash").asText());
        assertEquals("train_rag.jsonl".length(), cycleNode.path("datasetFileLength").asInt());
        assertEquals(SafeRedactor.hashValue(datasetPath), cycleNode.path("datasetPathHash").asText());
        assertFalse(cycleLine.contains(datasetPath));
        assertFalse(cycleLine.contains("cycle-session-raw"));

        JsonNode latestCycle = objectMapper.readTree(
                Files.readString(tempDir.resolve("handoff/manifest.json"), StandardCharsets.UTF_8))
                .path("latestCycle");
        assertFalse(latestCycle.has("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), latestCycle.path("datasetFileHash").asText());
        assertEquals("train_rag.jsonl".length(), latestCycle.path("datasetFileLength").asInt());
        assertEquals(SafeRedactor.hashValue(datasetPath), latestCycle.path("datasetPathHash").asText());
    }

    @Test
    void lockTimeoutProducesHoldWithoutMutation() throws Exception {
        Path root = tempDir.resolve("timeout-handoff");
        Files.createDirectories(root);
        Path sidecar = root.resolve(".uaw-agent-handoff.lock");
        try (FileChannel channel = FileChannel.open(sidecar,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(
                    propsFor(root), 50L, Files::move,
                    nanos -> TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos)));

            long started = System.nanoTime();
            recordSkipped(writer, "timeout");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 2_000L, "lock wait must remain bounded");
            assertEquals("write_held", TraceStore.get("uaw.agent.handoff.status"));
            assertEquals("HOLD", TraceStore.get("uaw.agent.handoff.lastDecision"));
            assertEquals("lock_timeout", TraceStore.get("uaw.agent.handoff.holdReason"));
            assertFalse(Files.exists(root.resolve("rejected.jsonl")));
            assertFalse(Files.exists(root.resolve("manifest.json")));
        }
    }

    @Test
    void interruptedLockWaitProducesHoldAndRestoresInterrupt() throws Exception {
        Path root = tempDir.resolve("interrupted-handoff");
        Files.createDirectories(root);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean restored = new AtomicBoolean(false);
        AtomicReference<Object> status = new AtomicReference<>();
        AtomicReference<Object> holdReason = new AtomicReference<>();
        Path sidecar = root.resolve(".uaw-agent-handoff.lock");
        try (FileChannel channel = FileChannel.open(sidecar,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(
                    propsFor(root), 10_000L, Files::move, nanos -> {
                waiting.countDown();
                TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos));
            });
            Thread thread = new Thread(() -> {
                recordSkipped(writer, "interrupted");
                restored.set(Thread.currentThread().isInterrupted());
                status.set(TraceStore.get("uaw.agent.handoff.status"));
                holdReason.set(TraceStore.get("uaw.agent.handoff.holdReason"));
            }, "uaw-handoff-interrupted-test");

            thread.start();
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            thread.interrupt();
            thread.join(5_000L);

            assertFalse(thread.isAlive());
            assertTrue(restored.get(), "interrupt flag must be restored before returning");
            assertEquals("write_held", status.get());
            assertEquals("interrupted", holdReason.get());
            assertFalse(Files.exists(root.resolve("rejected.jsonl")));
            assertFalse(Files.exists(root.resolve("manifest.json")));
        }
    }

    @Test
    void concurrentWriterInstancesAppendExactlyOnceAndKeepManifestValid() throws Exception {
        Path root = tempDir.resolve("concurrent-handoff");
        int writers = 8;
        int recordsPerWriter = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int writerIndex = 0; writerIndex < writers; writerIndex++) {
                int index = writerIndex;
                futures.add(pool.submit(() -> {
                    UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(
                            propsFor(root), 10_000L, Files::move,
                            nanos -> TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos)));
                    start.await();
                    for (int record = 0; record < recordsPerWriter; record++) {
                        recordSkipped(writer, "writer-" + index + "-record-" + record);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertJsonlRecords(root.resolve("rejected.jsonl"), writers * recordsPerWriter);
        objectMapper.readTree(Files.readString(root.resolve("manifest.json"), StandardCharsets.UTF_8));
    }

    @Test
    void twoProcessesAppendExactlyOnceAndLeaveValidManifest() throws Exception {
        Path root = tempDir.resolve("process-handoff");
        Process first = startProbe("write", root.toString(), "first", "20");
        Process second = startProbe("write", root.toString(), "second", "20");
        assertProcessExit(first, 0);
        assertProcessExit(second, 0);

        assertJsonlRecords(root.resolve("rejected.jsonl"), 40);
        objectMapper.readTree(Files.readString(root.resolve("manifest.json"), StandardCharsets.UTF_8));
        assertTrue(Files.exists(root.resolve(".uaw-agent-handoff.lock")));
    }

    @Test
    void postWriteHashMatchesExactJsonlBytes() throws Exception {
        Path root = tempDir.resolve("receipt-handoff");
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(propsFor(root));

        recordSkipped(writer, "receipt");

        byte[] bytes = Files.readAllBytes(root.resolve("rejected.jsonl"));
        String expected = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, TraceStore.get("uaw.agent.handoff.lastRecordHash"));
        assertEquals(bytes.length, TraceStore.get("uaw.agent.handoff.lastRecordBytes"));
        assertTrue(String.valueOf(TraceStore.get("uaw.agent.handoff.lastManifestHash"))
                .matches("sha256:[a-f0-9]{64}"));
    }

    @Test
    void atomicMoveFallbackIsExplicitAndGenericFailureDoesNotDowngrade() throws Exception {
        Path fallbackRoot = tempDir.resolve("fallback-handoff");
        AtomicInteger fallbackCalls = new AtomicInteger();
        UawLearningAgentHandoffWriter fallbackWriter = new UawLearningAgentHandoffWriter(
                propsFor(fallbackRoot), 2_000L, (source, target, options) -> {
            int call = fallbackCalls.incrementAndGet();
            if (call == 1 && Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test fallback");
            }
            return Files.move(source, target, options);
        }, nanos -> TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos)));

        recordSkipped(fallbackWriter, "fallback");

        assertEquals(2, fallbackCalls.get());
        assertEquals("replace_non_atomic", TraceStore.get("uaw.agent.handoff.manifestMove"));
        objectMapper.readTree(Files.readString(fallbackRoot.resolve("manifest.json"), StandardCharsets.UTF_8));

        TraceStore.clear();
        Path deniedRoot = tempDir.resolve("denied-handoff");
        Files.createDirectories(deniedRoot);
        Path manifest = deniedRoot.resolve("manifest.json");
        Files.writeString(manifest, "{\"old\":true}", StandardCharsets.UTF_8);
        AtomicInteger deniedCalls = new AtomicInteger();
        UawLearningAgentHandoffWriter deniedWriter = new UawLearningAgentHandoffWriter(
                propsFor(deniedRoot), 2_000L, (source, target, options) -> {
            deniedCalls.incrementAndGet();
            throw new AccessDeniedException(target.toString());
        }, nanos -> TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos)));

        recordSkipped(deniedWriter, "denied");

        assertEquals(1, deniedCalls.get(), "generic move failure must not trigger a fallback");
        assertEquals("sample_recorded_manifest_failed", TraceStore.get("uaw.agent.handoff.status"));
        assertTrue(String.valueOf(TraceStore.get("uaw.agent.handoff.lastRecordHash"))
                .matches("sha256:[a-f0-9]{64}"));
        assertTrue(objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8))
                .path("old").asBoolean());
    }

    @Test
    void crashedOwnerReleasesLockAndForeignTempIsNotClaimed() throws Exception {
        Path root = tempDir.resolve("crash-handoff");
        Process crashed = startProbe("crash", root.toString());
        assertProcessExit(crashed, 23);
        Path foreignTemp = root.resolve(".manifest.json.foreign.tmp");
        assertTrue(Files.exists(root.resolve("probe.ready")));
        assertTrue(Files.exists(foreignTemp));

        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(
                propsFor(root), 10_000L, Files::move,
                nanos -> TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos)));
        recordSkipped(writer, "after-crash");

        assertEquals("sample_recorded", TraceStore.get("uaw.agent.handoff.status"));
        assertTrue(Files.exists(foreignTemp), "another process's temp must remain untouched");
        assertTrue(Files.exists(root.resolve(".uaw-agent-handoff.lock")));
    }

    private UawAutolearnProperties props() {
        return propsFor(tempDir.resolve("handoff"));
    }

    private static UawAutolearnProperties propsFor(Path root) {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getAgentHandoff().setRootPath(root.toString());
        props.getAgentHandoff().setAcceptedPath(root.resolve("accepted.jsonl").toString());
        props.getAgentHandoff().setRejectedPath(root.resolve("rejected.jsonl").toString());
        props.getAgentHandoff().setCyclePath(root.resolve("cycles.jsonl").toString());
        props.getAgentHandoff().setManifestPath(root.resolve("manifest.json").toString());
        return props;
    }

    private static void recordSkipped(UawLearningAgentHandoffWriter writer, String marker) {
        writer.recordSkippedSample(
                marker + "-session", "uaw-train", marker + "-question", "", "", 0,
                null, "SKIPPED", "insufficient_evidence");
    }

    private void assertJsonlRecords(Path path, int expectedCount) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertEquals(expectedCount, lines.size());
        Set<String> hashes = new HashSet<>();
        for (String line : lines) {
            JsonNode node = objectMapper.readTree(line);
            assertEquals("uaw_autolearn_sample", node.path("type").asText());
            assertTrue(hashes.add(node.path("sampleHash").asText()), "sample hash must be unique");
        }
    }

    private static Process startProbe(String... arguments) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        String classPath = Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(entry -> Path.of(entry).toUri().toASCIIString())
                .collect(java.util.stream.Collectors.joining(" "));
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        Path classPathJar = Files.createTempFile("uaw-handoff-probe-classpath-", ".jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(classPathJar), manifest)) {
            // The manifest-only JAR keeps the Windows child-process command line bounded.
        }
        classPathJar.toFile().deleteOnExit();
        List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(classPathJar.toString());
        command.add(ProcessProbe.class.getName());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static void assertProcessExit(Process process, int expected) throws Exception {
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "probe process timed out");
            assertEquals(expected, process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static final class ProcessProbe {
        private ProcessProbe() {
        }

        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Path root = Path.of(args[1]);
            if ("write".equals(mode)) {
                String prefix = args[2];
                int count = Integer.parseInt(args[3]);
                UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(
                        propsFor(root), 10_000L, Files::move,
                        nanos -> TimeUnit.NANOSECONDS.sleep(Math.max(1L, nanos)));
                for (int index = 0; index < count; index++) {
                    recordSkipped(writer, prefix + '-' + index);
                    if (!"sample_recorded".equals(TraceStore.get("uaw.agent.handoff.status"))) {
                        System.exit(2);
                    }
                }
                return;
            }
            if (!"crash".equals(mode)) {
                System.exit(3);
            }
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(root.resolve(".uaw-agent-handoff.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Files.writeString(root.resolve(".manifest.json.foreign.tmp"), "{}", StandardCharsets.UTF_8);
                Files.writeString(root.resolve("probe.ready"), "ready", StandardCharsets.UTF_8);
                Runtime.getRuntime().halt(23);
            }
        }
    }

    private static UawDatasetWriter.TrainingMetadata metadata(LearningSampleValidationMetadata validation,
                                                             boolean finalGate) {
        return new UawDatasetWriter.TrainingMetadata(
                "uaw_autolearn",
                "local",
                "",
                3,
                finalGate,
                0.90d,
                validation);
    }

    private static LearningSampleValidationMetadata rejectedValidation() {
        return new LearningSampleValidationMetadata(
                "causal",
                java.util.List.of("BQ", "ER", "RC"),
                1.0d,
                0.0d,
                0.0d,
                0.0d,
                new LearningSampleValidationMetadata.Requery(true, false),
                0.0d,
                0.0d,
                0.30d,
                java.util.List.of("sample_score_below_threshold"),
                java.util.List.of("sample_score_min"));
    }
}
