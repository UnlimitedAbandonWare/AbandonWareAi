package ai.abandonware.nova.orch.uaw;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadataBuilder;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDatasetApiControllerTest {

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
    void appendRagResponseRedactsDatasetPathAndSessionIdButWriterReceivesMetadata() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        when(writer.append(any(File.class), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(),
                any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);
        String rawPath = tempDir.resolve("secret").resolve("train_rag.jsonl").toString();
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment()
                        .withProperty("uaw.dataset-api.key", "internal-secret")
                        .withProperty("uaw.autolearn.dataset.path", rawPath));
        String rawSessionId = "raw-session-123";

        InternalDatasetApiController.AppendRagRequest req = acceptedRequest();
        req.setDatasetName("manual-qa");
        req.setDatasetPath(rawPath);
        req.setModel("local-model");
        req.setSessionId(rawSessionId);
        req.setProvider("macmini-curator");

        ResponseEntity<Map<String, Object>> response = controller.appendRag(req, "internal-secret");
        Map<String, Object> body = response.getBody();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("accepted", body.get("status"));
        assertEquals(true, body.get("accepted"));
        assertEquals("accepted", body.get("validationDecision"));
        assertEquals(List.of(), body.get("rejectReasons"));
        assertFalse(body.containsKey("datasetName"));
        assertFalse(body.containsKey("evidenceCount"));
        assertFalse(body.containsKey("afterFilterCount"));
        assertFalse(body.containsKey("finalGate"));
        assertFalse(body.containsKey("model"));
        assertFalse(body.containsKey("datasetPath"));
        assertFalse(body.containsKey("sessionId"));
        assertFalse(body.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), body.get("datasetFileHash"));
        assertEquals("train_rag.jsonl".length(), body.get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(rawPath), body.get("datasetPathHash"));
        assertFalse(body.containsKey("hasDatasetPath"));
        assertFalse(body.containsKey("hasSessionId"));
        assertEquals(SafeRedactor.hashValue(rawSessionId), body.get("sessionHash"));
        assertFalse(body.toString().contains(rawPath));
        assertFalse(body.toString().contains(rawSessionId));
        assertFalse(body.toString().contains("internal-secret"));

        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        ArgumentCaptor<UawDatasetWriter.TrainingMetadata> metadataCaptor =
                ArgumentCaptor.forClass(UawDatasetWriter.TrainingMetadata.class);
        verify(writer).append(
                fileCaptor.capture(),
                eq("manual-qa"),
                eq(req.getQuestion().trim()),
                eq(req.getAnswer().trim()),
                eq("local-model"),
                eq(5),
                eq(rawSessionId),
                metadataCaptor.capture());
        verify(writer, never()).append(
                any(File.class), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString());
        assertEquals(rawPath, fileCaptor.getValue().getPath());
        UawDatasetWriter.TrainingMetadata metadata = metadataCaptor.getValue();
        assertEquals("macmini-curator", metadata.provider());
        assertEquals("internal_dataset_api", metadata.branch());
        assertEquals(5, metadata.afterFilterCount());
        assertEquals(true, metadata.finalGate());
        assertEquals("accepted", metadata.validation().accepted() ? "accepted" : "rejected");

        assertEquals("accepted", TraceStore.getString("uaw.dataset-api.status"));
        assertEquals(SafeRedactor.hashValue(rawPath), TraceStore.get("uaw.dataset-api.datasetPathHash"));
        assertEquals(SafeRedactor.hashValue(rawSessionId), TraceStore.get("uaw.dataset-api.sessionHash"));
        assertEquals("accepted", TraceStore.getString("uaw.dataset-api.validationDecision"));
    }

    @Test
    void requestDatasetPathCannotRedirectWritesOutsideConfiguredDataset() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        String configuredPath = tempDir.resolve("allowed").resolve("train_rag.jsonl").toString();
        String requestedPath = tempDir.resolve("other").resolve("train_rag.jsonl").toString();
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment()
                        .withProperty("uaw.dataset-api.key", "internal-secret")
                        .withProperty("uaw.autolearn.dataset.path", configuredPath));

        InternalDatasetApiController.AppendRagRequest req = acceptedRequest();
        req.setDatasetPath(requestedPath);
        req.setSessionId("raw-session-redirect");

        ResponseEntity<Map<String, Object>> response = controller.appendRag(req, "internal-secret");
        Map<String, Object> body = response.getBody();

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("rejected", body.get("status"));
        assertEquals(false, body.get("accepted"));
        assertEquals("dataset_path_not_allowed", body.get("disabledReason"));
        assertFalse(body.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), body.get("datasetFileHash"));
        assertEquals("train_rag.jsonl".length(), body.get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(configuredPath), body.get("datasetPathHash"));
        assertEquals(SafeRedactor.hashValue("raw-session-redirect"), body.get("sessionHash"));
        assertFalse(body.toString().contains(configuredPath));
        assertFalse(body.toString().contains(requestedPath));
        assertEquals("rejected", TraceStore.getString("uaw.dataset-api.status"));
        assertEquals("dataset_path_not_allowed", TraceStore.getString("uaw.dataset-api.disabledReason"));
        assertEquals(SafeRedactor.hashValue(configuredPath), TraceStore.get("uaw.dataset-api.datasetPathHash"));
        verifyNoAppend(writer);
    }

    @Test
    void enabledDatasetApiWithoutKeyFailsClosedAndDoesNotWrite() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        String configuredPath = tempDir.resolve("private").resolve("train_rag_curated.jsonl").toString();
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment().withProperty("uaw.autolearn.dataset.path", configuredPath));

        ResponseEntity<Map<String, Object>> response = controller.appendRag(request("q", "a"), null);
        Map<String, Object> body = response.getBody();

        assertEquals(503, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("disabled", body.get("status"));
        assertEquals(false, body.get("accepted"));
        assertFalse(body.containsKey("error"));
        assertEquals("missing_internal_key", body.get("disabledReason"));
        assertFalse(body.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag_curated.jsonl"), body.get("datasetFileHash"));
        assertEquals("train_rag_curated.jsonl".length(), body.get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(configuredPath), body.get("datasetPathHash"));
        assertFalse(body.toString().contains(configuredPath));
        assertEquals("disabled", TraceStore.getString("uaw.dataset-api.status"));
        assertEquals("missing_internal_key", TraceStore.getString("uaw.dataset-api.disabledReason"));
        assertEquals(SafeRedactor.hashValue(configuredPath), TraceStore.get("uaw.dataset-api.datasetPathHash"));
        verifyNoAppend(writer);
    }

    @Test
    void placeholderDatasetApiKeyFailsClosedAndDoesNotWrite() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment().withProperty("uaw.dataset-api.key", "sk-local"));

        ResponseEntity<Map<String, Object>> response = controller.appendRag(request("q", "a"), "sk-local");
        Map<String, Object> body = response.getBody();

        assertEquals(503, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("missing_internal_key", body.get("disabledReason"));
        assertFalse(body.toString().contains("sk-local"));
        verifyNoAppend(writer);
    }

    @Test
    void invalidInternalKeyRejectsWithoutWriting() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        String configuredPath = tempDir.resolve("private").resolve("train_rag_curated.jsonl").toString();
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment()
                        .withProperty("uaw.dataset-api.key", "internal-secret")
                        .withProperty("uaw.autolearn.dataset.path", configuredPath));

        ResponseEntity<Map<String, Object>> response = controller.appendRag(request("q", "a"), "wrong-secret");
        Map<String, Object> body = response.getBody();

        assertEquals(401, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("rejected", body.get("status"));
        assertEquals(false, body.get("accepted"));
        assertEquals("unauthorized", body.get("disabledReason"));
        assertFalse(body.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag_curated.jsonl"), body.get("datasetFileHash"));
        assertEquals("train_rag_curated.jsonl".length(), body.get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(configuredPath), body.get("datasetPathHash"));
        assertFalse(body.toString().contains("internal-secret"));
        assertFalse(body.toString().contains("wrong-secret"));
        assertFalse(body.toString().contains(configuredPath));
        assertEquals("rejected", TraceStore.getString("uaw.dataset-api.status"));
        assertEquals(SafeRedactor.hashValue(configuredPath), TraceStore.get("uaw.dataset-api.datasetPathHash"));
        verifyNoAppend(writer);
    }

    @Test
    void missingQuestionOrAnswerReturnsInvalidRequestWithoutWriting() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        String configuredPath = tempDir.resolve("private").resolve("train_rag_curated.jsonl").toString();
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment()
                        .withProperty("uaw.dataset-api.key", "internal-secret")
                        .withProperty("uaw.autolearn.dataset.path", configuredPath));

        ResponseEntity<Map<String, Object>> response = controller.appendRag(request(" ", "answer"), "internal-secret");
        Map<String, Object> body = response.getBody();

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("rejected", body.get("status"));
        assertEquals(false, body.get("accepted"));
        assertEquals("invalid_request", body.get("disabledReason"));
        assertFalse(body.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag_curated.jsonl"), body.get("datasetFileHash"));
        assertEquals("train_rag_curated.jsonl".length(), body.get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(configuredPath), body.get("datasetPathHash"));
        assertFalse(body.toString().contains(configuredPath));
        assertEquals(SafeRedactor.hashValue(configuredPath), TraceStore.get("uaw.dataset-api.datasetPathHash"));
        verifyNoAppend(writer);
    }

    @Test
    void validationRejectionReturnsUnprocessableAndDoesNotWrite() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment().withProperty("uaw.dataset-api.key", "internal-secret"));
        InternalDatasetApiController.AppendRagRequest req = request(
                "What is the local RAG cache policy?",
                "It writes a supported dataset sample.");
        req.setEvidenceCount(0);
        req.setAfterFilterCount(0);
        req.setContextDiversity(0.0d);
        req.setFinalGate(false);

        ResponseEntity<Map<String, Object>> response = controller.appendRag(req, "internal-secret");
        Map<String, Object> body = response.getBody();

        assertEquals(422, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("rejected", body.get("status"));
        assertEquals(false, body.get("accepted"));
        assertEquals("final_gate_failed", body.get("disabledReason"));
        assertEquals("rejected", body.get("validationDecision"));
        assertTrue(body.get("rejectReasons").toString().contains("final_gate_failed"));
        verifyNoAppend(writer);
    }

    @Test
    void rejectReasonTraceUsesSafeMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/uaw/InternalDatasetApiController.java"));

        assertFalse(source.contains(
                "TraceStore.put(\"uaw.dataset-api.rejectReasons\", body.getOrDefault(\"rejectReasons\", List.of()));"));
        assertFalse(source.contains(
                "TraceStore.put(\"uaw.dataset-api.datasetFile\", body.getOrDefault(\"datasetFile\", \"\"));"));
        assertFalse(source.contains("datasetFile={} datasetPathHash={} requestPathHash={}"));
        assertFalse(source.contains("accepted={} status={} disabledReason={} datasetFile={} datasetPathHash={}"));
        assertFalse(source.contains("data.put(\"datasetFile\", datasetFileName(path));"));
        assertTrue(source.contains(
                "TraceStore.put(\"uaw.dataset-api.rejectReasons\", safeRejectReasons(body.getOrDefault(\"rejectReasons\", List.of())));"));
        assertTrue(source.contains("traceDatasetFileDiagnostics(body);"));
        assertTrue(source.contains("putDatasetFileDiagnostics(data, path);"));
        assertTrue(source.contains("data.put(\"datasetFileHash\", fileName.isEmpty() ? \"\" : hashOrEmpty(fileName));"));
        assertTrue(source.contains("data.put(\"datasetFileLength\", fileName.length());"));
        assertTrue(source.contains("datasetFileHash={} datasetFileLength={} datasetPathHash={} requestPathHash={}"));
        assertTrue(source.contains("accepted={} status={} disabledReason={} datasetFileHash={} datasetFileLength={} datasetPathHash={}"));
        assertFalse(source.contains("catch (Throwable ignore)"));
        assertFalse(source.contains("catch (Exception ignored)"));
        assertTrue(source.contains("traceSuppressed(\"traceDatasetApi\", e);"));
        assertTrue(source.contains("traceSuppressed(\"normalizePathForComparison\", e);"));
        assertTrue(source.contains("MDC.put(\"uaw.dataset-api.suppressed.stage\", stage);"));
        assertFalse(source.contains("e.getMessage()"));
    }

    @Test
    void writerRejectionReturnsHonestUnprocessableStatus() {
        UawDatasetWriter writer = mock(UawDatasetWriter.class);
        when(writer.append(any(File.class), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(),
                any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(false);
        InternalDatasetApiController controller = controller(
                writer,
                new MockEnvironment().withProperty("uaw.dataset-api.key", "internal-secret"));

        ResponseEntity<Map<String, Object>> response = controller.appendRag(acceptedRequest(), "internal-secret");
        Map<String, Object> body = response.getBody();

        assertEquals(422, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("rejected", body.get("status"));
        assertEquals(false, body.get("accepted"));
        assertEquals("writer_rejected", body.get("disabledReason"));
        assertEquals("accepted", body.get("validationDecision"));
        assertEquals("writer_rejected", TraceStore.getString("uaw.dataset-api.disabledReason"));
    }

    @Test
    void traceDatasetApiRedactsSecretShapedDiagnosticLabels() throws Exception {
        InternalDatasetApiController controller = controller(
                mock(UawDatasetWriter.class),
                new MockEnvironment().withProperty("uaw.dataset-api.key", "internal-secret"));
        String secretStatus = "sk-" + "datasetstatus01234567890";
        String secretDecision = "pcsk_" + "datasetdecision01234567890";
        Map<String, Object> body = Map.of(
                "status", secretStatus,
                "accepted", false,
                "disabledReason", secretStatus,
                "validationDecision", secretDecision);

        var method = InternalDatasetApiController.class.getDeclaredMethod("traceDatasetApi", Map.class);
        method.setAccessible(true);
        method.invoke(controller, body);

        String tracedStatus = TraceStore.getString("uaw.dataset-api.status");
        String tracedReason = TraceStore.getString("uaw.dataset-api.disabledReason");
        String tracedDecision = TraceStore.getString("uaw.dataset-api.validationDecision");
        assertFalse(tracedStatus.contains(secretStatus), tracedStatus);
        assertFalse(tracedReason.contains(secretStatus), tracedReason);
        assertFalse(tracedDecision.contains(secretDecision), tracedDecision);
        assertTrue(tracedStatus.startsWith("hash:"), tracedStatus);
        assertTrue(tracedReason.startsWith("hash:"), tracedReason);
        assertTrue(tracedDecision.startsWith("hash:"), tracedDecision);
    }

    @Test
    void traceDatasetApiRedactsSupabaseSecretShapedDiagnosticLabels() throws Exception {
        InternalDatasetApiController controller = controller(
                mock(UawDatasetWriter.class),
                new MockEnvironment().withProperty("uaw.dataset-api.key", "internal-secret"));
        String secretStatus = "sb_secret_" + "datasetstatus01234567890";
        String secretDecision = "sb_publishable_" + "datasetdecision01234567890";
        Map<String, Object> body = Map.of(
                "status", secretStatus,
                "accepted", false,
                "disabledReason", secretStatus,
                "validationDecision", secretDecision);

        var method = InternalDatasetApiController.class.getDeclaredMethod("traceDatasetApi", Map.class);
        method.setAccessible(true);
        method.invoke(controller, body);

        String tracedStatus = TraceStore.getString("uaw.dataset-api.status");
        String tracedReason = TraceStore.getString("uaw.dataset-api.disabledReason");
        String tracedDecision = TraceStore.getString("uaw.dataset-api.validationDecision");
        assertFalse(tracedStatus.contains(secretStatus), tracedStatus);
        assertFalse(tracedReason.contains(secretStatus), tracedReason);
        assertFalse(tracedDecision.contains(secretDecision), tracedDecision);
        assertTrue(tracedStatus.startsWith("hash:"), tracedStatus);
        assertTrue(tracedReason.startsWith("hash:"), tracedReason);
        assertTrue(tracedDecision.startsWith("hash:"), tracedDecision);
    }

    private static InternalDatasetApiController controller(UawDatasetWriter writer, MockEnvironment env) {
        UawAutolearnProperties props = new UawAutolearnProperties();
        return new InternalDatasetApiController(
                writer,
                props,
                new LearningSampleValidationMetadataBuilder(props),
                new UawAutolearnQualityTracker(props, null, null),
                env);
    }

    private static InternalDatasetApiController.AppendRagRequest acceptedRequest() {
        InternalDatasetApiController.AppendRagRequest req = request(
                "What is the local RAG dataset retention policy?",
                "The dataset keeps accepted validation-backed RAG samples for offline evaluation.");
        req.setEvidenceCount(5);
        req.setAfterFilterCount(5);
        req.setContextDiversity(1.0d);
        req.setFinalGate(true);
        return req;
    }

    private static InternalDatasetApiController.AppendRagRequest request(String question, String answer) {
        InternalDatasetApiController.AppendRagRequest req = new InternalDatasetApiController.AppendRagRequest();
        req.setQuestion(question);
        req.setAnswer(answer);
        return req;
    }

    private static void verifyNoAppend(UawDatasetWriter writer) {
        verify(writer, never()).append(
                any(File.class), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString());
        verify(writer, never()).append(
                any(File.class), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(),
                any(UawDatasetWriter.TrainingMetadata.class));
    }
}
