package com.example.lms.graphdb;

import com.example.lms.file.FileIngestionService;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.graph.BrainStateProperties;
import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.GraphRagChunkingService;
import com.example.lms.service.rag.graph.KgChunk;
import com.example.lms.service.rag.graph.Neo4jKgChunkWriter;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import com.example.lms.service.vector.DocumentChunkingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphDbManualLearningServiceTest {

    @Test
    void graphDbNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String manualLearning = Files.readString(
                Path.of("main/java/com/example/lms/graphdb/GraphDbManualLearningService.java"),
                StandardCharsets.UTF_8);
        String brainSnapshot = Files.readString(
                Path.of("main/java/com/example/lms/graphdb/GraphDbBrainSnapshot.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(manualLearning, "private static int intValue(Object value)");
        assertParserCatchNarrowed(brainSnapshot, "private static int intValue(Object value)");
    }

    @Test
    void graphDbFailSoftFallbacksLeaveFixedStageBreadcrumbs() throws Exception {
        String manualLearning = Files.readString(
                Path.of("main/java/com/example/lms/graphdb/GraphDbManualLearningService.java"),
                StandardCharsets.UTF_8);
        String brainSnapshot = Files.readString(
                Path.of("main/java/com/example/lms/graphdb/GraphDbBrainSnapshot.java"),
                StandardCharsets.UTF_8);
        String client = Files.readString(
                Path.of("main/java/com/example/lms/graphdb/GraphDbClient.java"),
                StandardCharsets.UTF_8);
        String controller = Files.readString(
                Path.of("main/java/com/example/lms/graphdb/GraphDbManualLearningController.java"),
                StandardCharsets.UTF_8);

        assertTrue(brainSnapshot.contains("traceSuppressed(\"graphDb.snapshot.intValue\", ignored);"));
        assertTrue(brainSnapshot.contains("TraceStore.put(\"graphdb.snapshot.suppressed.stage\", safeStage);"));
        assertTrue(brainSnapshot.contains("TraceStore.put(\"graphdb.snapshot.suppressed.errorType\", safeErrorType);"));
        assertTrue(brainSnapshot.contains("TraceStore.put(\"graphdb.snapshot.suppressed.\" + safeStage, true);"));
        assertTrue(brainSnapshot.contains("TraceStore.put(\"graphdb.snapshot.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
        assertTrue(client.contains("traceSuppressed(\"graphDb.client.endpointHost\", ignore);"));
        assertTrue(client.contains("TraceStore.put(\"graphdb.client.suppressed.\" + safeStage, true);"));
        assertTrue(controller.contains("traceSuppressed(\"graphDb.controller.fileRead\", ex);"));
        assertTrue(controller.contains("TraceStore.put(\"graphdb.controller.suppressed.\" + safeStage, true);"));
        assertTrue(manualLearning.contains("traceSuppressed(\"graphDb.manual.fileExtract\", ex);"));
        assertTrue(manualLearning.contains("traceSuppressed(\"graphDb.manual.intValue\", ignored);"));
        assertTrue(manualLearning.contains("TraceStore.put(\"graphdb.manual.suppressed.stage\", safeStage);"));
        assertTrue(manualLearning.contains("TraceStore.put(\"graphdb.manual.suppressed.errorType\", safeErrorType);"));
        assertTrue(manualLearning.contains("TraceStore.put(\"graphdb.manual.suppressed.\" + safeStage, true);"));
        assertTrue(manualLearning.contains("TraceStore.put(\"graphdb.manual.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
    }

    @Test
    void brainSnapshotRejectsNonFiniteCounts() {
        TraceStore.clear();

        GraphDbBrainSnapshot snapshot = GraphDbBrainSnapshot.fromSummary(Map.of(
                "returnedCount", Double.POSITIVE_INFINITY,
                "communityCount", Double.POSITIVE_INFINITY));

        assertEquals(0, snapshot.candidateCount());
        assertEquals(0, snapshot.communityCount());
        assertEquals("invalid_number", TraceStore.get("graphdb.snapshot.suppressed.graphDb.snapshot.intValue.errorType"));
        TraceStore.clear();
    }

    @Test
    void disabledRouteDoesNotInvokeChunking() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbManualLearningService service = service(props, chunking, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s1", "Alpha Beta raw text", "GENERAL", false);

        assertFalse(report.enabled());
        assertEquals("route_disabled", report.disabledReason());
        assertEquals("graphdb_manual_learning", report.lane());
        assertEquals("disabled", report.manifest().get("routeStatus"));
        assertEquals("route_disabled", report.manifest().get("routeDisabledReason"));
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    @Test
    void learnTextUsesGraphDbLaneOptionsAndReturnsManifestOnly() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        when(chunking.ingestText(
                eq("s1"),
                eq("Alpha helps Beta with private source text"),
                eq(GraphDbManualLearningService.SOURCE_TAG),
                eq("GENERAL"),
                any(GraphRagChunkingService.IngestOptions.class)))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true,
                        "s1",
                        "dry_run",
                        2,
                        3,
                        1,
                        0,
                        "hash-only",
                        "",
                        Map.of(
                                "vectorStatus", "dry_run",
                                "brainStateStatus", "dry_run",
                                "neo4jStatus", "dry_run",
                                "neo4jDisabledReason", "dry_run",
                                "failureClass", "")));

        GraphDbManualLearningService service = service(props, chunking, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s1", "Alpha helps Beta with private source text", "GENERAL", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GraphRagChunkingService.IngestOptions> options =
                ArgumentCaptor.forClass(GraphRagChunkingService.IngestOptions.class);
        verify(chunking).ingestText(eq("s1"), any(), eq(GraphDbManualLearningService.SOURCE_TAG),
                eq("GENERAL"), options.capture());
        assertTrue(options.getValue().dryRun());
        assertTrue(options.getValue().vectorEnabled());
        assertTrue(options.getValue().neo4jEnabled());
        assertFalse(options.getValue().brainStateEnabled());
        assertFalse(options.getValue().requireBrainStateGate());
        assertEquals("GRAPHDB_MANUAL_LEARNING", options.getValue().docType());
        assertEquals("MANUAL_GRAPHDB", options.getValue().origin());

        assertEquals("graphdb_manual_learning", report.lane());
        assertEquals("dry_run", report.status());
        assertEquals(hash12("hash-only"), report.textHash());
        assertEquals(hash12("hash-only"), report.manifest().get("textHash"));
        assertEquals("GRAPHDB_MANUAL_LEARNING", report.manifest().get("docType"));
        assertEquals("GRAPHDB_MANUAL", report.manifest().get("sourceTag"));
        assertEquals("MANUAL_GRAPHDB", report.manifest().get("origin"));
        assertEquals("dry_run_ready", report.manifest().get("routeStatus"));
        assertEquals("", report.manifest().get("routeDisabledReason"));
        assertTrue(report.manifest().containsKey("sessionHash"));
        assertFalse(report.manifest().containsKey("sessionId"));
        assertEquals(false, report.manifest().get("rawIdentifiersIncluded"));
        assertFalse(report.toString().contains("private source text"));
        assertFalse(report.toString().contains("sessionId=s1"));
    }

    @Test
    void ingestManifestSanitizesBackendFailureCodesAndRawTextHash() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        when(chunking.ingestText(
                eq("s-secret"),
                eq("Alpha private ingest text"),
                eq(GraphDbManualLearningService.SOURCE_TAG),
                eq("GENERAL"),
                any(GraphRagChunkingService.IngestOptions.class)))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true,
                        "s-secret",
                        "partial_indexed",
                        1,
                        1,
                        0,
                        0,
                        "raw text hash token",
                        "ownerToken=secret",
                        Map.ofEntries(
                                entry("vectorStatus", "ownerToken=secret"),
                                entry("vectorAttemptCount", 1),
                                entry("vectorQueuedCount", 0),
                                entry("vectorFailureCount", 1),
                                entry("brainStateStatus", "disabled"),
                                entry("brainStateDisabledReason", "lane_excludes_brain_state"),
                                entry("anchorMapStatus", "disabled"),
                                entry("anchorMapDisabledReason", "graphdb_manual_lane_excludes_query_time_anchor_map"),
                                entry("neo4jStatus", "written"),
                                entry("neo4jDisabledReason", "Authorization: Bearer secret"),
                                entry("neo4jFailureClass", "AuthorizationBearerToken"),
                                entry("neo4jPortMappingCount", 0),
                                entry("requiredPersistenceTargets", List.of("vector", "neo4j", "ownerToken=secret")),
                                entry("requiredPersistenceSatisfied", false),
                                entry("requiredPersistenceMissingReason", "ownerToken=secret"),
                                entry("persistenceTargetOrder", List.of("vector", "neo4j")),
                                entry("persistenceAttemptedTargets", List.of("vector", "neo4j")),
                                entry("persistenceSucceededTargets", List.of("neo4j")),
                                entry("persistenceIncompleteTargets", List.of("ownerToken=secret")),
                                entry("persistenceFailureIsolationApplied", true),
                                entry("failureClass", "AuthorizationBearerToken"))));
        GraphDbManualLearningService service = service(props, chunking, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s-secret", "Alpha private ingest text", "GENERAL", null);

        assertEquals("partial_indexed", report.status());
        assertEquals("redacted", report.disabledReason());
        assertEquals(hash12("raw text hash token"), report.textHash());
        assertEquals(hash12("raw text hash token"), report.manifest().get("textHash"));
        assertEquals("redacted", report.manifest().get("vectorStatus"));
        assertEquals("redacted", report.manifest().get("neo4jDisabledReason"));
        assertEquals("redacted", report.manifest().get("neo4jFailureClass"));
        assertEquals("redacted", report.manifest().get("requiredPersistenceMissingReason"));
        assertEquals("redacted", report.manifest().get("failureClass"));
        assertTrue(((List<?>) report.manifest().get("requiredPersistenceTargets")).contains("redacted"));
        assertTrue(((List<?>) report.manifest().get("persistenceIncompleteTargets")).contains("redacted"));
        assertFalse(report.toString().contains("ownerToken=secret"));
        assertFalse(report.toString().contains("AuthorizationBearerToken"));
        assertFalse(report.toString().contains("raw text hash token"));
        assertFalse(report.toString().contains("Alpha private ingest text"));
    }

    @Test
    void disabledReportReasonUsesSafeCode() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/example/lms/graphdb/GraphDbManualLearningService.java"));

        assertFalse(source.contains("manifest.put(\"disabledReason\", reason);"));
        assertTrue(source.contains("manifest.put(\"disabledReason\", safeReason);"));
        assertTrue(source.contains("return new LearnReport(false, LANE, \"disabled\", safeReason, dryRun,"));
    }

    @Test
    void learnFileExtractsTextThenUsesManualLane() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        when(files.extractText(eq("note.txt"), eq("text/plain"), any(byte[].class))).thenReturn("Alpha file text");
        when(chunking.ingestText(
                eq("s2"),
                eq("Alpha file text"),
                eq(GraphDbManualLearningService.SOURCE_TAG),
                eq("GENERAL"),
                any(GraphRagChunkingService.IngestOptions.class)))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true, "s2", "indexed", 1, 1, 0, 1, "file-hash", "", Map.of(
                        "vectorStatus", "queued",
                        "brainStateStatus", "disabled",
                        "neo4jStatus", "written",
                        "neo4jDisabledReason", "",
                        "failureClass", "")));
        GraphDbManualLearningService service = service(props, chunking, files);

        GraphDbManualLearningService.LearnReport report = service.learnFile(
                "s2", "note.txt", "text/plain", "bytes".getBytes(StandardCharsets.UTF_8), "GENERAL", null);

        assertEquals("indexed", report.status());
        assertEquals("file", report.manifest().get("inputKind"));
        assertEquals("FileIngestionService.extractText", report.manifest().get("fileContentBoundary"));
        assertEquals("bytes".getBytes(StandardCharsets.UTF_8).length, report.manifest().get("fileByteLength"));
        assertTrue(String.valueOf(report.manifest().get("fileByteHash")).matches("[a-f0-9]{12}"));
        assertEquals(false, report.manifest().get("rawFileNameIncluded"));
        assertEquals(false, report.manifest().get("rawFileBytesIncluded"));
        assertEquals(1, report.neo4jWriteCount());
        assertTrue(report.manifest().containsKey("sessionHash"));
        assertFalse(report.manifest().containsKey("sessionId"));
        assertFalse(report.manifest().containsKey("fileName"));
        assertFalse(report.toString().contains("Alpha file text"));
        assertFalse(report.toString().contains("sessionId=s2"));
    }

    @Test
    void learnFileExtractorFailureStaysOnFileBoundaryWithoutChunking() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        when(files.extractText(eq("manual.txt"), eq("text/plain"), any(byte[].class)))
                .thenThrow(new IllegalStateException("private extractor failure"));
        GraphDbManualLearningService service = service(props, chunking, files);

        GraphDbManualLearningService.LearnReport report = service.learnFile(
                "s-file-fail",
                "manual.txt",
                "text/plain",
                "opaque bytes".getBytes(StandardCharsets.UTF_8),
                "GENERAL",
                null);

        assertFalse(report.enabled());
        assertEquals("disabled", report.status());
        assertEquals("file_extract_failed", report.disabledReason());
        assertEquals("file", report.manifest().get("inputKind"));
        assertEquals(true, report.manifest().get("fileNamePresent"));
        assertEquals(true, report.manifest().get("mimeTypePresent"));
        assertEquals("IllegalStateException", report.manifest().get("failureClass"));
        assertEquals("FileIngestionService.extractText", report.manifest().get("fileContentBoundary"));
        assertEquals("opaque bytes".getBytes(StandardCharsets.UTF_8).length, report.manifest().get("fileByteLength"));
        assertTrue(String.valueOf(report.manifest().get("fileByteHash")).matches("[a-f0-9]{12}"));
        assertEquals(false, report.manifest().get("rawTextIncluded"));
        assertEquals(false, report.manifest().get("rawIdentifiersIncluded"));
        assertEquals(false, report.manifest().get("rawFileNameIncluded"));
        assertEquals(false, report.manifest().get("rawFileBytesIncluded"));
        assertFalse(report.manifest().containsKey("fileName"));
        assertFalse(report.toString().contains("private extractor failure"));
        assertFalse(report.toString().contains("opaque bytes"));
        assertFalse(report.toString().contains("sessionId=s-file-fail"));
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    @Test
    void learnFileExtractorCancellationUsesStableFailureClassWithoutRawMessage() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        when(files.extractText(eq("manual.txt"), eq("text/plain"), any(byte[].class)))
                .thenThrow(new CancellationException("cancelled ownerToken=fake-token"));
        GraphDbManualLearningService service = service(props, chunking, files);

        GraphDbManualLearningService.LearnReport report = service.learnFile(
                "s-file-cancel",
                "manual.txt",
                "text/plain",
                "opaque bytes".getBytes(StandardCharsets.UTF_8),
                "GENERAL",
                null);

        assertFalse(report.enabled());
        assertEquals("disabled", report.status());
        assertEquals("file_extract_failed", report.disabledReason());
        assertEquals("cancelled", report.manifest().get("failureClass"));
        assertFalse(report.toString().contains("fake-token"));
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    @Test
    void statusManifestDocumentsAdminTokenBoundaryWithoutRawSecrets() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        GraphDbManualLearningService service = service(
                props,
                mock(GraphRagChunkingService.class),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.status();

        assertEquals("disabled", report.status());
        assertEquals("route_disabled", report.disabledReason());
        assertEquals("disabled", report.manifest().get("routeStatus"));
        assertEquals("route_disabled", report.manifest().get("routeDisabledReason"));
        assertEquals("/api/admin/graph", report.manifest().get("adminBoundary"));
        assertEquals("graphdb.manual-learning.enabled", report.manifest().get("featureFlagProperty"));
        assertEquals("GRAPHDB_MANUAL_LEARNING_ENABLED", report.manifest().get("featureFlagEnv"));
        assertEquals("retrieval.kg.neo4j", report.manifest().get("backendConfigPrefix"));
        assertEquals("RETRIEVAL_KG_NEO4J_ENABLED", report.manifest().get("backendEnabledEnv"));
        assertEquals(List.of("RETRIEVAL_KG_NEO4J_URI", "NEO4J_URI"), report.manifest().get("backendUriEnv"));
        assertEquals(List.of("RETRIEVAL_KG_NEO4J_USER", "NEO4J_USER"), report.manifest().get("backendUserEnv"));
        assertEquals(List.of("RETRIEVAL_KG_NEO4J_PASSWORD", "NEO4J_PASSWORD"),
                report.manifest().get("backendPasswordEnv"));
        assertEquals(true, report.manifest().get("nonDryRunRequiresReachableBolt"));
        assertEquals(true, report.manifest().get("nonDryRunRequiresNonPlaceholderCredentials"));
        assertEquals(true, report.manifest().get("liveWriteReadProofRequired"));
        assertEquals(true, report.manifest().get("operatorDiagnosticsRedacted"));
        assertTrue(String.valueOf(report.manifest().get("nonDryRunReadinessCommand"))
                .contains("smoke_graphdb_manual_learning.ps1 -ReadinessOnly"));
        assertTrue(String.valueOf(report.manifest().get("nonDryRunSmokeCommand"))
                .contains("smoke_graphdb_manual_learning.ps1 -NonDryRun"));
        assertEquals(List.of(
                        "uriPresent",
                        "uriSource",
                        "userPresent",
                        "userSource",
                        "passwordPresent",
                        "passwordSource",
                        "unsafeDefaultPassword",
                        "uriParseable",
                        "endpointHost",
                        "endpointPort",
                        "tcp",
                        "neo4jService",
                        "neo4jCli",
                        "neo4jAdmin",
                        "docker",
                        "podman"),
                report.manifest().get("nonDryRunReadinessEvidenceFields"));
        assertEquals(List.of(
                        "missing_uri",
                        "missing_user",
                        "missing_password",
                        "unsafe_default_credentials",
                        "unparseable_uri",
                        "missing_host",
                        "bolt_unreachable",
                        "live_write_read_unverified"),
                report.manifest().get("nonDryRunReadinessFailureClasses"));
        assertEquals(List.of(
                        "readiness_env",
                        "readiness_bolt_tcp",
                        "boot_status",
                        "learn_text_write",
                        "learn_file_write",
                        "learn_evidence_readback",
                        "learn_summary_projection",
                        "learn_snapshot_projection"),
                report.manifest().get("nonDryRunLiveProofStages"));
        assertEquals(false, report.manifest().get("nonDryRunReadinessRawSecretsIncluded"));
        assertEquals("graphdb_manual_learning", report.manifest().get("writeBoundary"));
        assertEquals("graphdb_manual_learning", report.manifest().get("readBoundary"));
        assertEquals("VectorStoreService.enqueue", report.manifest().get("vectorWriteBoundary"));
        assertEquals("buffered_enqueue", report.manifest().get("vectorWriteMode"));
        assertEquals("Neo4jKgChunkWriter.writeChunks", report.manifest().get("neo4jWriteBoundary"));
        assertEquals("Neo4jKgChunkWriter.readManualEvidence", report.manifest().get("neo4jReadBoundary"));
        assertEquals("KgChunkNode.sessionHash_textHash_ingestLane", report.manifest().get("neo4jChunkUpsertScope"));
        assertEquals("ingestLane=graphdb_manual_learning", report.manifest().get("neo4jManualEvidenceScope"));
        assertEquals("RELATED_TO.source=graphdb_manual_learning", report.manifest().get("neo4jManualRelationScope"));
        assertEquals(false, report.manifest().get("neo4jRawTextPersisted"));
        assertEquals(false, report.manifest().get("neo4jRawSessionIdPersisted"));
        assertEquals(false, report.manifest().get("neo4jRawEntityValuesReturned"));
        assertEquals(true, report.manifest().get("simultaneousIngestRequired"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("simultaneousIngestTargets"));
        assertEquals("same_request_vector_then_neo4j", report.manifest().get("simultaneousIngestMode"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("simultaneousIngestExecutionOrder"));
        assertEquals(false, report.manifest().get("simultaneousIngestAtomic"));
        assertEquals("continue_remaining_targets", report.manifest().get("simultaneousIngestFailureIsolation"));
        assertEquals("partial_indexed", report.manifest().get("simultaneousIngestPartialStatus"));
        assertEquals(List.of(
                        "vectorStatus",
                        "vectorQueuedCount",
                        "neo4jStatus",
                        "neo4jWriteCount",
                        "requiredPersistenceSatisfied",
                        "requiredPersistenceMissingReason",
                        "persistenceAttemptedTargets",
                        "persistenceSucceededTargets",
                        "persistenceIncompleteTargets"),
                report.manifest().get("simultaneousIngestProofFields"));
        assertEquals(true, report.manifest().get("simultaneousIngestConfigured"));
        assertEquals("", report.manifest().get("simultaneousIngestDisabledReason"));
        assertEquals(false, report.manifest().get("neo4jBackendEnabled"));
        assertEquals("disabled", report.manifest().get("neo4jBackendDisabledReason"));
        assertEquals(false, report.manifest().get("simultaneousIngestBackendConfigured"));
        assertEquals("blocked", report.manifest().get("nonDryRunLiveProofStatus"));
        assertEquals("route_disabled", report.manifest().get("nonDryRunLiveProofBlockedReason"));
        assertEquals(false, report.manifest().get("brainStateFallbackCoupled"));
        assertEquals(false, report.manifest().get("queryTimeRetrievalCoupled"));
        assertEquals(false, report.manifest().get("rawTextIncluded"));
        assertEquals(false, report.manifest().get("rawIdentifiersIncluded"));
        assertEquals(false, report.manifest().get("rawSecretsIncluded"));
        assertEquals("ROLE_ADMIN", report.manifest().get("securityRole"));
        assertEquals(true, report.manifest().get("adminTokenGuarded"));
        assertEquals(true, report.manifest().get("adminTokenHeaderAccepted"));
        assertEquals(true, report.manifest().get("ownerTokenHeaderAccepted"));
        assertEquals(false, report.manifest().get("authTokenValuesIncluded"));
        assertEquals("AdminTokenGuardFilter+ROLE_ADMIN", report.manifest().get("authGuardBoundary"));
        assertEquals(true, report.manifest().get("csrfExempt"));

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) report.manifest().get("acceptedTokenHeaders");
        assertTrue(headers.contains("X-Admin-Token"));
        assertTrue(headers.contains("X-Owner-Token"));

        @SuppressWarnings("unchecked")
        List<String> routes = (List<String>) report.manifest().get("routes");
        assertTrue(routes.contains("POST /api/admin/graph/learn-text"));
        assertTrue(routes.contains("POST /api/admin/graph/learn-file"));
        assertTrue(routes.contains("GET /api/admin/graph/learn-evidence"));
        assertTrue(routes.contains("GET /api/admin/graph/learn-summary"));
        assertTrue(routes.contains("GET /api/admin/graph/learn-snapshot"));
        assertFalse(report.toString().contains("owner-secret"));
        assertFalse(report.toString().contains("admin-secret"));
    }

    @Test
    void statusManifestSeparatesConfiguredBackendFromLiveWriteReadProof() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.status()).thenReturn(Map.of(
                "backend", "neo4j",
                "writeBoundary", "graphdb_manual_learning",
                "enabled", true,
                "endpointHost", "neo4j.local",
                "disabledReason", "",
                "lastWriteStatus", "not_attempted"));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.status();

        assertEquals("dry_run_ready", report.status());
        assertEquals("", report.disabledReason());
        assertEquals("dry_run_ready", report.manifest().get("routeStatus"));
        assertEquals("", report.manifest().get("routeDisabledReason"));
        assertEquals(true, report.manifest().get("simultaneousIngestConfigured"));
        assertEquals("same_request_vector_then_neo4j", report.manifest().get("simultaneousIngestMode"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("simultaneousIngestExecutionOrder"));
        assertEquals(false, report.manifest().get("simultaneousIngestAtomic"));
        assertEquals("continue_remaining_targets", report.manifest().get("simultaneousIngestFailureIsolation"));
        assertEquals("partial_indexed", report.manifest().get("simultaneousIngestPartialStatus"));
        assertEquals(true, report.manifest().get("neo4jBackendEnabled"));
        assertEquals("neo4j.local", report.manifest().get("neo4jEndpointHost"));
        assertEquals(true, report.manifest().get("simultaneousIngestBackendConfigured"));
        assertEquals("required_unverified", report.manifest().get("nonDryRunLiveProofStatus"));
        assertEquals("", report.manifest().get("nonDryRunLiveProofBlockedReason"));
        assertEquals(false, report.manifest().get("nonDryRunReadinessRawSecretsIncluded"));
        assertFalse(report.toString().contains("NEO4J_PASSWORD="));
        assertFalse(report.toString().contains("raw-secret-value"));
        verify(client).status();
    }

    @Test
    void statusManifestReportsWhenSimultaneousVectorNeo4jLaneIsDegraded() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setVectorEnabled(true);
        props.setNeo4jEnabled(false);
        GraphDbManualLearningService service = service(
                props,
                mock(GraphRagChunkingService.class),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.status();

        assertEquals("dry_run_ready", report.status());
        assertEquals("", report.disabledReason());
        assertEquals("dry_run_ready", report.manifest().get("routeStatus"));
        assertEquals("", report.manifest().get("routeDisabledReason"));
        assertEquals(true, report.manifest().get("simultaneousIngestRequired"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("simultaneousIngestTargets"));
        assertEquals(false, report.manifest().get("simultaneousIngestConfigured"));
        assertEquals("neo4j_disabled", report.manifest().get("simultaneousIngestDisabledReason"));
        assertEquals(false, report.manifest().get("simultaneousIngestBackendConfigured"));
        assertEquals("blocked", report.manifest().get("nonDryRunLiveProofStatus"));
        assertEquals("neo4j_disabled", report.manifest().get("nonDryRunLiveProofBlockedReason"));
        assertFalse(report.toString().contains("NEO4J_PASSWORD="));
    }

    @Test
    void statusReportsBlockedWhenNonDryRunRouteCannotReachRequiredBackend() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        GraphDbManualLearningService service = service(
                props,
                mock(GraphRagChunkingService.class),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.status();

        assertEquals("blocked", report.status());
        assertEquals("neo4j_backend_disabled:disabled", report.disabledReason());
        assertEquals("blocked", report.manifest().get("routeStatus"));
        assertEquals("neo4j_backend_disabled:disabled", report.manifest().get("routeDisabledReason"));
        assertEquals("blocked", report.manifest().get("nonDryRunLiveProofStatus"));
        assertEquals("neo4j_backend_disabled:disabled", report.manifest().get("nonDryRunLiveProofBlockedReason"));
    }

    @Test
    void statusReportsReadyUnverifiedWhenNonDryRunBackendIsConfiguredButLiveProofIsPending() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.status()).thenReturn(Map.of(
                "backend", "neo4j",
                "writeBoundary", "graphdb_manual_learning",
                "enabled", true,
                "endpointHost", "neo4j.local",
                "disabledReason", ""));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.status();

        assertEquals("ready_unverified", report.status());
        assertEquals("non_dry_run_live_proof_required", report.disabledReason());
        assertEquals("ready_unverified", report.manifest().get("routeStatus"));
        assertEquals("non_dry_run_live_proof_required", report.manifest().get("routeDisabledReason"));
        assertEquals("required_unverified", report.manifest().get("nonDryRunLiveProofStatus"));
        assertEquals("", report.manifest().get("nonDryRunLiveProofBlockedReason"));
    }

    @Test
    void dryRunWithRealChunkingDoesNotPersistToVectorBrainStateOrNeo4j() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(true);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        GraphDbManualLearningService service = service(
                props,
                realChunking(vectorStoreService, writer, brain),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s3", "Alpha helps Beta with dry run text", "GENERAL", null);

        assertEquals("dry_run", report.status());
        assertTrue(report.dryRun());
        assertEquals("dry_run", report.manifest().get("vectorStatus"));
        assertEquals("dry_run", report.manifest().get("brainStateStatus"));
        assertEquals("dry_run", report.manifest().get("neo4jStatus"));
        assertEquals("dry_run", report.manifest().get("neo4jDisabledReason"));
        assertEquals("GRAPHDB_MANUAL_LEARNING", report.manifest().get("docType"));
        assertEquals("GRAPHDB_MANUAL", report.manifest().get("sourceTag"));
        assertEquals("VectorStoreService.enqueue", report.manifest().get("vectorWriteBoundary"));
        assertEquals("Neo4jKgChunkWriter.writeChunks", report.manifest().get("neo4jWriteBoundary"));
        assertEquals(false, report.manifest().get("brainStateFallbackCoupled"));
        assertEquals(false, report.manifest().get("queryTimeRetrievalCoupled"));
        assertFalse(report.toString().contains("dry run text"));
        verify(vectorStoreService, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
        verify(brain, never()).recordChunks(anyList());
        verify(writer, never()).writeChunks(anyList());
    }

    @Test
    void nonDryRunWithRealChunkingQueuesVectorAndWritesNeo4jWithoutBrainStateMirror() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                true, "written", null, 1, 2, 1, 1, "neo4j.local", null));
        GraphDbManualLearningService service = service(
                props,
                realChunking(vectorStoreService, writer, brain),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s4", "Alpha helps Beta with indexed source text", "GENERAL", null);

        assertEquals("indexed", report.status());
        assertFalse(report.dryRun());
        assertEquals(1, report.chunkCount());
        assertEquals(2, report.entityCount());
        assertEquals(1, report.relationCount());
        assertEquals(1, report.neo4jWriteCount());
        assertTrue(report.manifest().containsKey("sessionHash"));
        assertFalse(report.manifest().containsKey("sessionId"));
        assertEquals("queued", report.manifest().get("vectorStatus"));
        assertEquals(1, report.manifest().get("vectorAttemptCount"));
        assertEquals(1, report.manifest().get("vectorQueuedCount"));
        assertEquals(0, report.manifest().get("vectorFailureCount"));
        assertEquals("disabled", report.manifest().get("brainStateStatus"));
        assertEquals("lane_excludes_brain_state", report.manifest().get("brainStateDisabledReason"));
        assertEquals("disabled", report.manifest().get("anchorMapStatus"));
        assertEquals("graphdb_manual_lane_excludes_query_time_anchor_map",
                report.manifest().get("anchorMapDisabledReason"));
        assertEquals("written", report.manifest().get("neo4jStatus"));
        assertEquals("", report.manifest().get("neo4jFailureClass"));
        assertEquals(1, report.manifest().get("neo4jPortMappingCount"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("requiredPersistenceTargets"));
        assertEquals(true, report.manifest().get("requiredPersistenceSatisfied"));
        assertEquals("", report.manifest().get("requiredPersistenceMissingReason"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceTargetOrder"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceAttemptedTargets"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceSucceededTargets"));
        assertEquals(List.of(), report.manifest().get("persistenceIncompleteTargets"));
        assertEquals(false, report.manifest().get("persistenceFailureIsolationApplied"));
        assertEquals(false, report.manifest().get("brainStateMirrorEnabled"));
        assertFalse(report.toString().contains("indexed source text"));
        assertFalse(report.toString().contains("sessionId=s4"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("graphdb-chunk:"),
                eq(vectorSid("s4")),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("GRAPHDB_MANUAL_LEARNING", meta.get("doc_type"));
        assertEquals("GRAPHDB_MANUAL", meta.get("source_tag"));
        assertEquals("MANUAL_GRAPHDB", meta.get("origin"));
        assertEquals("graphdb_manual_learning", meta.get("ingest_lane"));
        assertEquals(vectorSid("s4"), meta.get(VectorMetaKeys.META_SID_LOGICAL));
        assertEquals(vectorSid("s4"), meta.get(VectorMetaKeys.META_ORIGINAL_SID));
        assertEquals(hash12("s4"), meta.get("graphdb_manual_session_hash"));
        assertEquals("false", meta.get("raw_session_id_included"));
        assertFalse(meta.containsValue("s4"));
        assertFalse(meta.containsValue("Alpha helps Beta with indexed source text"));
        assertEquals("graphdb_manual_learning", report.manifest().get("writeBoundary"));
        assertEquals("VectorStoreService.enqueue", report.manifest().get("vectorWriteBoundary"));
        assertEquals("buffered_enqueue", report.manifest().get("vectorWriteMode"));
        assertEquals("hash_only_namespace", report.manifest().get("vectorSessionIdMode"));
        assertEquals(false, report.manifest().get("vectorRawSessionIdIncluded"));
        assertEquals(true, report.manifest().get("vectorPayloadRawTextRequired"));
        assertEquals(false, report.manifest().get("vectorRawTextMetadataIncluded"));
        assertEquals("Neo4jKgChunkWriter.writeChunks", report.manifest().get("neo4jWriteBoundary"));
        assertEquals("KgChunkNode.sessionHash_textHash_ingestLane", report.manifest().get("neo4jChunkUpsertScope"));
        assertEquals("ingestLane=graphdb_manual_learning", report.manifest().get("neo4jManualEvidenceScope"));
        assertEquals("RELATED_TO.source=graphdb_manual_learning", report.manifest().get("neo4jManualRelationScope"));
        assertEquals(false, report.manifest().get("neo4jRawTextPersisted"));
        assertEquals(false, report.manifest().get("neo4jRawSessionIdPersisted"));
        assertEquals(false, report.manifest().get("neo4jRawEntityValuesReturned"));
        assertEquals(false, report.manifest().get("brainStateFallbackCoupled"));
        assertEquals(false, report.manifest().get("queryTimeRetrievalCoupled"));
        assertEquals(false, report.manifest().get("queryTimeAnchorMapCoupled"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeChunks(chunksCaptor.capture());
        assertEquals(1, chunksCaptor.getValue().size());
        assertTrue(chunksCaptor.getValue().get(0).chunkId().startsWith("graphdb-chunk:"));
        assertEquals("GRAPHDB_MANUAL", chunksCaptor.getValue().get(0).sourceTag());
        assertEquals("GRAPHDB_MANUAL_LEARNING", chunksCaptor.getValue().get(0).docType());
        assertEquals("MANUAL_GRAPHDB", chunksCaptor.getValue().get(0).origin());
        assertEquals("graphdb_manual_learning", chunksCaptor.getValue().get(0).ingestLane());
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void graphDbManualLaneSuppressesBrainStateMirrorEvenWhenLegacyFlagRequested() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        props.setBrainStateMirrorEnabled(true);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                true, "written", null, 1, 2, 1, 1, "neo4j.local", null));
        GraphDbManualLearningService service = service(
                props,
                realChunking(vectorStoreService, writer, brain),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s4-suppress", "Alpha helps Beta with suppressed mirror text", "GENERAL", null);

        assertEquals("indexed", report.status());
        assertEquals(true, report.manifest().get("brainStateMirrorRequested"));
        assertEquals(false, report.manifest().get("brainStateMirrorEnabled"));
        assertEquals("graphdb_manual_lane_excludes_brain_state",
                report.manifest().get("brainStateMirrorSuppressedReason"));
        assertTrue(report.manifest().containsKey("sessionHash"));
        assertFalse(report.manifest().containsKey("sessionId"));
        assertEquals("disabled", report.manifest().get("brainStateStatus"));
        assertEquals("lane_excludes_brain_state", report.manifest().get("brainStateDisabledReason"));
        assertEquals("disabled", report.manifest().get("anchorMapStatus"));
        assertEquals("graphdb_manual_lane_excludes_query_time_anchor_map",
                report.manifest().get("anchorMapDisabledReason"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("requiredPersistenceTargets"));
        assertFalse(report.toString().contains("suppressed mirror text"));
        assertFalse(report.toString().contains("sessionId=s4-suppress"));
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("graphdb-chunk:"),
                eq(vectorSid("s4-suppress")),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                anyMap());
        verify(writer).writeChunks(anyList());
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void nonDryRunWithNeo4jDisabledReportsPartialInsteadOfIndexed() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, 0, "", null));
        GraphDbManualLearningService service = service(
                props,
                realChunking(vectorStoreService, writer, brain),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s4-partial", "Alpha helps Beta with partial graphdb source text", "GENERAL", null);

        assertEquals("partial_indexed", report.status());
        assertEquals("neo4j_persistence_incomplete", report.disabledReason());
        assertEquals("queued", report.manifest().get("vectorStatus"));
        assertEquals(1, report.manifest().get("vectorAttemptCount"));
        assertEquals(1, report.manifest().get("vectorQueuedCount"));
        assertEquals(0, report.manifest().get("vectorFailureCount"));
        assertEquals("disabled", report.manifest().get("neo4jStatus"));
        assertEquals("disabled", report.manifest().get("neo4jDisabledReason"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("requiredPersistenceTargets"));
        assertEquals(false, report.manifest().get("requiredPersistenceSatisfied"));
        assertEquals("neo4j_persistence_incomplete", report.manifest().get("requiredPersistenceMissingReason"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceTargetOrder"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceAttemptedTargets"));
        assertEquals(List.of("vector"), report.manifest().get("persistenceSucceededTargets"));
        assertEquals(List.of("neo4j"), report.manifest().get("persistenceIncompleteTargets"));
        assertEquals(true, report.manifest().get("persistenceFailureIsolationApplied"));
        assertEquals("continue_remaining_targets", report.manifest().get("simultaneousIngestFailureIsolation"));
        assertEquals("partial_indexed", report.manifest().get("simultaneousIngestPartialStatus"));
        assertEquals(false, report.manifest().get("brainStateFallbackCoupled"));
        assertFalse(report.toString().contains("partial graphdb source text"));
        assertFalse(report.toString().contains("sessionId=s4-partial"));

        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("graphdb-chunk:"),
                eq(vectorSid("s4-partial")),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                anyMap());
        verify(writer).writeChunks(anyList());
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void nonDryRunWithVectorFailureReportsPartialEvenWhenNeo4jWrites() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("ownerToken=secret raw graphdb text"))
                .when(vectorStoreService)
                .enqueue(anyString(), anyString(), anyString(), anyMap());
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                true, "written", null, 1, 2, 1, 1, "neo4j.local", null));
        GraphDbManualLearningService service = service(
                props,
                realChunking(vectorStoreService, writer, brain),
                mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnText(
                "s4-vector-partial", "Alpha helps Beta with vector failure graphdb source text", "GENERAL", null);

        assertEquals("partial_indexed", report.status());
        assertEquals("vector_persistence_incomplete", report.disabledReason());
        assertEquals("failed", report.manifest().get("vectorStatus"));
        assertEquals(1, report.manifest().get("vectorAttemptCount"));
        assertEquals(0, report.manifest().get("vectorQueuedCount"));
        assertEquals(1, report.manifest().get("vectorFailureCount"));
        assertEquals("written", report.manifest().get("neo4jStatus"));
        assertEquals(1, report.neo4jWriteCount());
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("requiredPersistenceTargets"));
        assertEquals(false, report.manifest().get("requiredPersistenceSatisfied"));
        assertEquals("vector_persistence_incomplete", report.manifest().get("requiredPersistenceMissingReason"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceTargetOrder"));
        assertEquals(List.of("vector", "neo4j"), report.manifest().get("persistenceAttemptedTargets"));
        assertEquals(List.of("neo4j"), report.manifest().get("persistenceSucceededTargets"));
        assertEquals(List.of("vector"), report.manifest().get("persistenceIncompleteTargets"));
        assertEquals(true, report.manifest().get("persistenceFailureIsolationApplied"));
        assertEquals("continue_remaining_targets", report.manifest().get("simultaneousIngestFailureIsolation"));
        assertEquals("partial_indexed", report.manifest().get("simultaneousIngestPartialStatus"));
        assertEquals("RuntimeException", report.manifest().get("failureClass"));
        assertFalse(report.toString().contains("vector failure graphdb source text"));
        assertFalse(report.toString().contains("ownerToken=secret"));
        assertFalse(report.toString().contains("sessionId=s4-vector-partial"));

        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("graphdb-chunk:"),
                eq(vectorSid("s4-vector-partial")),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                anyMap());
        verify(writer).writeChunks(anyList());
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void learnEvidenceUsesGraphDbReadBoundaryWithoutChunking() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.manualEvidence(eq("GENERAL"), eq(7))).thenReturn(Map.ofEntries(
                entry("backend", "neo4j"),
                entry("writeBoundary", "graphdb_manual_learning"),
                entry("readBoundary", "graphdb_manual_learning"),
                entry("rawTextIncluded", false),
                entry("rawEntityValuesIncluded", false),
                entry("rawIdentifiersIncluded", false),
                entry("rawSecretsIncluded", false),
                entry("enabled", true),
                entry("status", "ok"),
                entry("disabledReason", ""),
                entry("returnedCount", 1),
                entry("candidates", List.of(Map.of(
                        "chunkId", "graphdb-chunk:1",
                        "textHash", "hash-only",
                        "entityCount", 1,
                        "entityHashes", List.of("entity-hash-only"))))));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnEvidence("GENERAL", 7);

        assertTrue(report.enabled());
        assertEquals("ok", report.status());
        assertEquals(true, report.manifest().get("readOnly"));
        assertEquals("ready_unverified", report.manifest().get("routeStatus"));
        assertEquals("non_dry_run_live_proof_required", report.manifest().get("routeDisabledReason"));
        assertEquals("graphdb_manual_learning", report.graphDb().get("writeBoundary"));
        assertEquals("graphdb_manual_learning", report.graphDb().get("readBoundary"));
        assertEquals(false, report.graphDb().get("rawSecretsIncluded"));
        assertFalse(report.toString().contains("raw private text"));
        assertFalse(report.toString().contains("entities=["));
        verify(client).manualEvidence("GENERAL", 7);
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    @Test
    void learnSummaryBuildsRedactedCommunityAndMultiHopProjection() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.manualEvidence(eq("GENERAL"), eq(10))).thenReturn(Map.of(
                "backend", "neo4j",
                "writeBoundary", "graphdb_manual_learning",
                "readBoundary", "graphdb_manual_learning",
                "enabled", true,
                "status", "ok",
                "disabledReason", "",
                "returnedCount", 1,
                "candidates", List.of(Map.of(
                        "chunkId", "graphdb-chunk:private",
                        "textHash", "hash-only",
                        "entityHashes", List.of("alpha-hash-only", "gamma-hash-only"),
                        "hops", List.of(Map.of(
                                "targetHash", "beta-hash-only",
                                "kind", "RELATED_TO",
                                "pathHash", "path-secret",
                                "relationSource", "graphdb_manual_learning"))))));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnSummary("GENERAL", 10);

        assertEquals("summary", report.manifest().get("inputKind"));
        assertEquals("community_summary", report.manifest().get("projection"));
        assertEquals("ready_unverified", report.manifest().get("routeStatus"));
        assertEquals("non_dry_run_live_proof_required", report.manifest().get("routeDisabledReason"));
        assertEquals("graphdb_manual_learning", report.graphDb().get("writeBoundary"));
        assertEquals("graphdb_manual_learning", report.graphDb().get("readBoundary"));
        assertEquals("graphdb_manual_learning_community_summary", report.graphDb().get("summaryBoundary"));
        assertEquals("graphdb_manual_learning_multi_hop_evidence", report.graphDb().get("multiHopBoundary"));
        assertEquals("Neo4jKgChunkWriter.readManualEvidence", report.graphDb().get("projectionSource"));
        assertEquals(false, report.graphDb().get("brainStateCoupled"));
        assertEquals(false, report.graphDb().get("queryTimeRetrievalCoupled"));
        assertEquals(false, report.graphDb().get("queryTimeAnchorMapCoupled"));
        assertEquals(false, report.graphDb().get("rawTextIncluded"));
        assertEquals(false, report.graphDb().get("rawEntityValuesIncluded"));
        assertEquals(false, report.graphDb().get("rawIdentifiersIncluded"));
        assertEquals(false, report.graphDb().get("rawSecretsIncluded"));
        assertEquals(2, report.graphDb().get("communityCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> communities = (List<Map<String, Object>>) report.graphDb().get("communities");
        assertFalse(communities.isEmpty());
        assertTrue(String.valueOf(communities.get(0).get("communityId")).startsWith("community:"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> multiHop = (List<Map<String, Object>>) report.graphDb().get("multiHopEvidence");
        assertFalse(multiHop.isEmpty());
        assertTrue(multiHop.get(0).containsKey("targetHash"));
        assertEquals(hash12("path-secret"), multiHop.get(0).get("connectorHash"));
        assertTrue(String.valueOf(multiHop.get(0).get("targetHash")).matches("[a-f0-9]{12}"));
        assertEquals("graphdb_manual_learning", multiHop.get(0).get("relationSource"));
        assertFalse(report.toString().contains("Alpha Private"));
        assertFalse(report.toString().contains("Beta Private"));
        assertFalse(report.toString().contains("graphdb-chunk:private"));
        assertFalse(report.toString().contains("path-secret"));
        assertFalse(report.toString().contains("beta-hash-only"));
        verify(client).manualEvidence("GENERAL", 10);
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    @Test
    void learnSnapshotWrapsSummaryAsAdminOnlyGraphDbBrainSnapshot() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.manualEvidence(eq("GENERAL"), eq(10))).thenReturn(Map.of(
                "backend", "neo4j",
                "writeBoundary", "graphdb_manual_learning",
                "readBoundary", "graphdb_manual_learning",
                "enabled", true,
                "status", "ok",
                "disabledReason", "",
                "returnedCount", 1,
                "candidates", List.of(Map.of(
                        "chunkId", "graphdb-chunk:private",
                        "textHash", "hash-only",
                        "entityHashes", List.of("alpha-hash-only"),
                        "hops", List.of(Map.of(
                                "targetHash", "beta-hash-only",
                                "kind", "RELATED_TO",
                                "pathHash", "path-secret",
                                "relationSource", "graphdb_manual_learning"))))));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnSnapshot("GENERAL", 10);

        assertEquals("snapshot", report.manifest().get("inputKind"));
        assertEquals("graphdb_brain_snapshot", report.manifest().get("projection"));
        assertEquals("ready_unverified", report.manifest().get("routeStatus"));
        assertEquals("non_dry_run_live_proof_required", report.manifest().get("routeDisabledReason"));
        assertEquals("graphdb_manual_learning_brain_snapshot", report.graphDb().get("snapshotBoundary"));
        assertEquals("graphdb_manual_learning", report.graphDb().get("writeBoundary"));
        assertEquals("graphdb_manual_learning", report.graphDb().get("readBoundary"));
        assertEquals(false, report.graphDb().get("brainStateCoupled"));
        assertEquals(false, report.graphDb().get("queryTimeRetrievalCoupled"));
        assertEquals(false, report.graphDb().get("queryTimeAnchorMapCoupled"));
        assertEquals(false, report.graphDb().get("rawTextIncluded"));
        assertEquals(false, report.graphDb().get("rawEntityValuesIncluded"));
        assertEquals(false, report.graphDb().get("rawIdentifiersIncluded"));
        assertEquals(false, report.graphDb().get("rawSecretsIncluded"));

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) report.graphDb().get("snapshot");
        assertEquals("graphdb_manual_learning_brain_snapshot", snapshot.get("snapshotBoundary"));
        assertEquals("graphdb_manual_learning", snapshot.get("writeBoundary"));
        assertEquals("graphdb_manual_learning", snapshot.get("readBoundary"));
        assertEquals("graphdb_manual_learning_community_summary", snapshot.get("summaryBoundary"));
        assertEquals("graphdb_manual_learning_multi_hop_evidence", snapshot.get("multiHopBoundary"));
        assertEquals(false, snapshot.get("brainStateCoupled"));
        assertEquals(false, snapshot.get("queryTimeRetrievalCoupled"));
        assertEquals(false, snapshot.get("queryTimeAnchorMapCoupled"));
        assertEquals(false, snapshot.get("rawTextIncluded"));
        assertEquals(false, snapshot.get("rawEntityValuesIncluded"));
        assertEquals(false, snapshot.get("rawIdentifiersIncluded"));
        assertEquals(false, snapshot.get("rawSecretsIncluded"));
        assertEquals(1, snapshot.get("candidateCount"));
        assertEquals(List.of(hash12("path-secret")), snapshot.get("connectorHashes"));
        assertEquals(List.of("graphdb_manual_learning"), snapshot.get("relationSources"));
        assertFalse(report.toString().contains("Alpha Private"));
        assertFalse(report.toString().contains("Beta Private"));
        assertFalse(report.toString().contains("graphdb-chunk:private"));
        assertFalse(report.toString().contains("path-secret"));
        verify(client).manualEvidence("GENERAL", 10);
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    @Test
    void learnSnapshotRejectsNonFiniteManualEvidenceCounts() {
        TraceStore.clear();
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.manualEvidence(eq("GENERAL"), eq(10))).thenReturn(Map.of(
                "backend", "neo4j",
                "enabled", true,
                "status", "ok",
                "returnedCount", Double.POSITIVE_INFINITY,
                "candidates", List.of()));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnSnapshot("GENERAL", 10);

        assertEquals(0, report.graphDb().get("returnedCount"));
        assertEquals("graphDb.manual.intValue", TraceStore.get("graphdb.manual.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("graphdb.manual.suppressed.errorType"));
        assertEquals("invalid_number", TraceStore.get("graphdb.manual.suppressed.graphDb.manual.intValue.errorType"));
        TraceStore.clear();
    }

    @Test
    void learnSummaryExcludesUnknownOrNonManualRelationSourceHops() {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        GraphDbClient client = mock(GraphDbClient.class);
        List<Map<String, Object>> hops = List.of(
                Map.of(
                        "targetHash", "manual-target-hash",
                        "kind", "RELATED_TO",
                        "pathHash", "manual-path",
                        "relationSource", "graphdb_manual_learning"),
                Map.of(
                        "targetHash", "brain-target-hash",
                        "kind", "RELATED_TO",
                        "pathHash", "brain-path",
                        "relationSource", "conversation-brain-state"),
                Map.of(
                        "targetHash", "unknown-target-hash",
                        "kind", "RELATED_TO",
                        "pathHash", "unknown-path"));
        when(client.manualEvidence(eq("GENERAL"), eq(10))).thenReturn(Map.of(
                "backend", "neo4j",
                "readBoundary", "graphdb_manual_learning",
                "enabled", true,
                "status", "ok",
                "disabledReason", "",
                "returnedCount", 1,
                "candidates", List.of(Map.of(
                        "chunkId", "graphdb-chunk:private",
                        "textHash", "hash-only",
                        "entityHashes", List.of("entity-hash-only"),
                        "hops", hops))));
        GraphDbManualLearningService service = new GraphDbManualLearningService(
                props, chunking, client, mock(FileIngestionService.class));

        GraphDbManualLearningService.LearnReport report = service.learnSummary("GENERAL", 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> multiHop = (List<Map<String, Object>>) report.graphDb().get("multiHopEvidence");
        assertEquals(1, multiHop.size());
        assertEquals(hash12("manual-target-hash"), multiHop.get(0).get("targetHash"));
        assertEquals("graphdb_manual_learning", multiHop.get(0).get("relationSource"));
        assertFalse(report.toString().contains("manual-target-hash"));
        assertFalse(report.toString().contains("brain-target-hash"));
        assertFalse(report.toString().contains("unknown-target-hash"));
        verify(chunking, never()).ingestText(
                any(), any(), any(), any(), any(GraphRagChunkingService.IngestOptions.class));
    }

    private static GraphDbManualLearningService service(GraphDbManualLearningProperties props,
                                                        GraphRagChunkingService chunking,
                                                        FileIngestionService files) {
        GraphDbClient client = mock(GraphDbClient.class);
        when(client.status()).thenReturn(Map.of(
                "backend", "neo4j",
                "enabled", false,
                "disabledReason", "disabled"));
        when(client.manualEvidence(any(), anyInt())).thenReturn(Map.of(
                "backend", "neo4j",
                "readBoundary", "graphdb_manual_learning",
                "enabled", false,
                "status", "disabled",
                "disabledReason", "disabled",
                "returnedCount", 0,
                "candidates", List.of()));
        return new GraphDbManualLearningService(props, chunking, client, files);
    }

    private static GraphRagChunkingService realChunking(VectorStoreService vectorStoreService,
                                                        Neo4jKgChunkWriter writer,
                                                        BrainStateService brain) {
        return new GraphRagChunkingService(
                new BrainStateProperties(),
                new DocumentChunkingService(),
                text -> List.of("Alpha", "Beta"),
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));
    }

    private static String vectorSid(String sessionId) {
        return "graphdb-manual:" + hash12(sessionId);
    }

    private static String hash12(String value) {
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(value == null ? "" : value).substring(0, 12);
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
