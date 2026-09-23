package com.example.lms.graphdb;

import com.example.lms.file.FileIngestionService;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.security.AdminTokenGuardFilter;
import com.example.lms.security.AdminTokenGuardInterceptor;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GraphDbManualLearningApiFlowTest {

    @Test
    void ownerTokenLearnTextQueuesVectorAndWritesNeo4jWithoutRawEcho() throws Exception {
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = writer();
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        MockMvc mvc = mvc(service(vectorStore, writer, brain, files));

        String rawText = "Alpha helps Beta with private graphdb source text";
        mvc.perform(post("/api/admin/graph/learn-text")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "api-flow",
                                  "text": "Alpha helps Beta with private graphdb source text",
                                  "domain": "GENERAL",
                                  "dryRun": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.manifest.sessionHash").exists())
                .andExpect(jsonPath("$.manifest.sessionId").doesNotExist())
                .andExpect(jsonPath("$.manifest.vectorStatus").value("queued"))
                .andExpect(jsonPath("$.manifest.vectorAttemptCount").value(1))
                .andExpect(jsonPath("$.manifest.vectorQueuedCount").value(1))
                .andExpect(jsonPath("$.manifest.vectorFailureCount").value(0))
                .andExpect(jsonPath("$.manifest.brainStateStatus").value("disabled"))
                .andExpect(jsonPath("$.manifest.brainStateDisabledReason").value("lane_excludes_brain_state"))
                .andExpect(jsonPath("$.manifest.anchorMapStatus").value("disabled"))
                .andExpect(jsonPath("$.manifest.anchorMapDisabledReason")
                        .value("graphdb_manual_lane_excludes_query_time_anchor_map"))
                .andExpect(jsonPath("$.manifest.neo4jStatus").value("written"))
                .andExpect(jsonPath("$.manifest.neo4jFailureClass").value(""))
                .andExpect(jsonPath("$.manifest.neo4jPortMappingCount").value(1))
                .andExpect(jsonPath("$.manifest.routeStatus").value("ready_unverified"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason")
                        .value("non_dry_run_live_proof_required"))
                .andExpect(jsonPath("$.manifest.requiredPersistenceTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.requiredPersistenceTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.requiredPersistenceSatisfied").value(true))
                .andExpect(jsonPath("$.manifest.requiredPersistenceMissingReason").value(""))
                .andExpect(jsonPath("$.manifest.persistenceTargetOrder[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.persistenceTargetOrder[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.persistenceAttemptedTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.persistenceAttemptedTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.persistenceSucceededTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.persistenceSucceededTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.persistenceIncompleteTargets[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.persistenceFailureIsolationApplied").value(false))
                .andExpect(jsonPath("$.manifest.adminBoundary").value("/api/admin/graph"))
                .andExpect(jsonPath("$.manifest.authGuardBoundary").value("AdminTokenGuardFilter+ROLE_ADMIN"))
                .andExpect(jsonPath("$.manifest.adminTokenHeaderAccepted").value(true))
                .andExpect(jsonPath("$.manifest.ownerTokenHeaderAccepted").value(true))
                .andExpect(jsonPath("$.manifest.authTokenValuesIncluded").value(false))
                .andExpect(jsonPath("$.manifest.featureFlagProperty").value("graphdb.manual-learning.enabled"))
                .andExpect(jsonPath("$.manifest.backendConfigPrefix").value("retrieval.kg.neo4j"))
                .andExpect(jsonPath("$.manifest.origin").value("MANUAL_GRAPHDB"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestRequired").value(true))
                .andExpect(jsonPath("$.manifest.simultaneousIngestTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestMode")
                        .value("same_request_vector_then_neo4j"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestExecutionOrder[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestExecutionOrder[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestAtomic").value(false))
                .andExpect(jsonPath("$.manifest.simultaneousIngestFailureIsolation")
                        .value("continue_remaining_targets"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestPartialStatus")
                        .value("partial_indexed"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[0]")
                        .value("vectorStatus"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[1]")
                        .value("vectorQueuedCount"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[2]")
                        .value("neo4jStatus"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[3]")
                        .value("neo4jWriteCount"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[4]")
                        .value("requiredPersistenceSatisfied"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[5]")
                        .value("requiredPersistenceMissingReason"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[6]")
                        .value("persistenceAttemptedTargets"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[7]")
                        .value("persistenceSucceededTargets"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestProofFields[8]")
                        .value("persistenceIncompleteTargets"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestConfigured").value(true))
                .andExpect(jsonPath("$.manifest.simultaneousIngestDisabledReason").value(""))
                .andExpect(jsonPath("$.manifest.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.manifest.vectorWriteBoundary").value("VectorStoreService.enqueue"))
                .andExpect(jsonPath("$.manifest.vectorWriteMode").value("buffered_enqueue"))
                .andExpect(jsonPath("$.manifest.vectorSessionIdMode").value("hash_only_namespace"))
                .andExpect(jsonPath("$.manifest.vectorRawSessionIdIncluded").value(false))
                .andExpect(jsonPath("$.manifest.vectorPayloadRawTextRequired").value(true))
                .andExpect(jsonPath("$.manifest.vectorRawTextMetadataIncluded").value(false))
                .andExpect(jsonPath("$.manifest.neo4jWriteBoundary").value("Neo4jKgChunkWriter.writeChunks"))
                .andExpect(jsonPath("$.manifest.neo4jChunkUpsertScope")
                        .value("KgChunkNode.sessionHash_textHash_ingestLane"))
                .andExpect(jsonPath("$.manifest.neo4jManualEvidenceScope")
                        .value("ingestLane=graphdb_manual_learning"))
                .andExpect(jsonPath("$.manifest.neo4jManualRelationScope")
                        .value("RELATED_TO.source=graphdb_manual_learning"))
                .andExpect(jsonPath("$.manifest.neo4jRawTextPersisted").value(false))
                .andExpect(jsonPath("$.manifest.neo4jRawSessionIdPersisted").value(false))
                .andExpect(jsonPath("$.manifest.neo4jRawEntityValuesReturned").value(false))
                .andExpect(jsonPath("$.manifest.brainStateFallbackCoupled").value(false))
                .andExpect(jsonPath("$.manifest.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.manifest.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.manifest.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("owner-secret"));
                    assertFalse(body.contains("api-flow"));
                    assertFalse(body.contains(rawText));
                    assertFalse(body.contains("sourceText"));
                });

        assertVectorQueued(vectorStore, "api-flow");
        assertNeo4jWritten(writer);
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void ownerTokenLearnTextDryRunDoesNotQueueVectorOrWriteNeo4j() throws Exception {
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = writer();
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        MockMvc mvc = mvc(service(vectorStore, writer, brain, files));

        String rawText = "Alpha dry run Beta private graphdb source text";
        mvc.perform(post("/api/admin/graph/learn-text")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "dryrun-flow",
                                  "text": "Alpha dry run Beta private graphdb source text",
                                  "domain": "GENERAL",
                                  "dryRun": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.status").value("dry_run"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.manifest.dryRun").value(true))
                .andExpect(jsonPath("$.manifest.vectorStatus").value("dry_run"))
                .andExpect(jsonPath("$.manifest.vectorAttemptCount").value(0))
                .andExpect(jsonPath("$.manifest.vectorQueuedCount").value(0))
                .andExpect(jsonPath("$.manifest.vectorFailureCount").value(0))
                .andExpect(jsonPath("$.manifest.brainStateStatus").value("dry_run"))
                .andExpect(jsonPath("$.manifest.neo4jStatus").value("dry_run"))
                .andExpect(jsonPath("$.manifest.neo4jDisabledReason").value("dry_run"))
                .andExpect(jsonPath("$.neo4jWriteCount").value(0))
                .andExpect(jsonPath("$.manifest.requiredPersistenceTargets").isArray())
                .andExpect(jsonPath("$.manifest.requiredPersistenceTargets[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.requiredPersistenceSatisfied").value(true))
                .andExpect(jsonPath("$.manifest.requiredPersistenceMissingReason").value(""))
                .andExpect(jsonPath("$.manifest.persistenceTargetOrder[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.persistenceAttemptedTargets[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.persistenceSucceededTargets[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.persistenceIncompleteTargets[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.persistenceFailureIsolationApplied").value(false))
                .andExpect(jsonPath("$.manifest.routeStatus").value("dry_run_ready"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason").value(""))
                .andExpect(jsonPath("$.manifest.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.manifest.vectorWriteBoundary").value("VectorStoreService.enqueue"))
                .andExpect(jsonPath("$.manifest.vectorSessionIdMode").value("hash_only_namespace"))
                .andExpect(jsonPath("$.manifest.vectorRawSessionIdIncluded").value(false))
                .andExpect(jsonPath("$.manifest.vectorRawTextMetadataIncluded").value(false))
                .andExpect(jsonPath("$.manifest.neo4jWriteBoundary").value("Neo4jKgChunkWriter.writeChunks"))
                .andExpect(jsonPath("$.manifest.brainStateFallbackCoupled").value(false))
                .andExpect(jsonPath("$.manifest.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.manifest.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.manifest.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.manifest.authTokenValuesIncluded").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("owner-secret"));
                    assertFalse(body.contains("dryrun-flow"));
                    assertFalse(body.contains(rawText));
                    assertFalse(body.contains("sourceText"));
                });

        verify(vectorStore, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
        verify(writer, never()).writeChunks(anyList());
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void ownerTokenLearnFileExtractsTextAndIndexesWithoutRawEcho() throws Exception {
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = writer();
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        when(files.extractText(eq("manual.txt"), eq("text/plain"), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn("Alpha file Beta private graphdb text");
        MockMvc mvc = mvc(service(vectorStore, writer, brain, files));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manual.txt",
                "text/plain",
                "opaque file bytes".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/admin/graph/learn-file")
                        .file(file)
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .param("sessionId", "file-flow")
                        .param("domain", "GENERAL")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.manifest.inputKind").value("file"))
                .andExpect(jsonPath("$.manifest.fileNamePresent").value(true))
                .andExpect(jsonPath("$.manifest.mimeTypePresent").value(true))
                .andExpect(jsonPath("$.manifest.fileContentBoundary")
                        .value("FileIngestionService.extractText"))
                .andExpect(jsonPath("$.manifest.fileByteLength").value("opaque file bytes".getBytes(StandardCharsets.UTF_8).length))
                .andExpect(jsonPath("$.manifest.fileByteHash").exists())
                .andExpect(jsonPath("$.manifest.rawFileNameIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawFileBytesIncluded").value(false))
                .andExpect(jsonPath("$.manifest.vectorStatus").value("queued"))
                .andExpect(jsonPath("$.manifest.vectorQueuedCount").value(1))
                .andExpect(jsonPath("$.manifest.vectorWriteBoundary").value("VectorStoreService.enqueue"))
                .andExpect(jsonPath("$.manifest.vectorWriteMode").value("buffered_enqueue"))
                .andExpect(jsonPath("$.manifest.vectorSessionIdMode").value("hash_only_namespace"))
                .andExpect(jsonPath("$.manifest.vectorRawSessionIdIncluded").value(false))
                .andExpect(jsonPath("$.manifest.vectorRawTextMetadataIncluded").value(false))
                .andExpect(jsonPath("$.manifest.neo4jStatus").value("written"))
                .andExpect(jsonPath("$.manifest.neo4jWriteBoundary").value("Neo4jKgChunkWriter.writeChunks"))
                .andExpect(jsonPath("$.manifest.neo4jReadBoundary")
                        .value("Neo4jKgChunkWriter.readManualEvidence"))
                .andExpect(jsonPath("$.manifest.neo4jChunkUpsertScope")
                        .value("KgChunkNode.sessionHash_textHash_ingestLane"))
                .andExpect(jsonPath("$.manifest.neo4jManualEvidenceScope")
                        .value("ingestLane=graphdb_manual_learning"))
                .andExpect(jsonPath("$.manifest.neo4jManualRelationScope")
                        .value("RELATED_TO.source=graphdb_manual_learning"))
                .andExpect(jsonPath("$.manifest.neo4jRawTextPersisted").value(false))
                .andExpect(jsonPath("$.manifest.neo4jRawSessionIdPersisted").value(false))
                .andExpect(jsonPath("$.manifest.neo4jRawEntityValuesReturned").value(false))
                .andExpect(jsonPath("$.manifest.routeStatus").value("ready_unverified"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason")
                        .value("non_dry_run_live_proof_required"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestRequired").value(true))
                .andExpect(jsonPath("$.manifest.simultaneousIngestTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestMode")
                        .value("same_request_vector_then_neo4j"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestExecutionOrder[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestExecutionOrder[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestAtomic").value(false))
                .andExpect(jsonPath("$.manifest.simultaneousIngestFailureIsolation")
                        .value("continue_remaining_targets"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestPartialStatus")
                        .value("partial_indexed"))
                .andExpect(jsonPath("$.manifest.simultaneousIngestConfigured").value(true))
                .andExpect(jsonPath("$.manifest.simultaneousIngestDisabledReason").value(""))
                .andExpect(jsonPath("$.manifest.requiredPersistenceTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.requiredPersistenceTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.requiredPersistenceSatisfied").value(true))
                .andExpect(jsonPath("$.manifest.persistenceAttemptedTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.persistenceAttemptedTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.persistenceSucceededTargets[0]").value("vector"))
                .andExpect(jsonPath("$.manifest.persistenceSucceededTargets[1]").value("neo4j"))
                .andExpect(jsonPath("$.manifest.persistenceIncompleteTargets[0]").doesNotExist())
                .andExpect(jsonPath("$.manifest.persistenceFailureIsolationApplied").value(false))
                .andExpect(jsonPath("$.manifest.brainStateStatus").value("disabled"))
                .andExpect(jsonPath("$.manifest.brainStateDisabledReason").value("lane_excludes_brain_state"))
                .andExpect(jsonPath("$.manifest.brainStateFallbackCoupled").value(false))
                .andExpect(jsonPath("$.manifest.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.manifest.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.manifest.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.manifest.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("owner-secret"));
                    assertFalse(body.contains("file-flow"));
                    assertFalse(body.contains("Alpha file Beta private graphdb text"));
                    assertFalse(body.contains("opaque file bytes"));
                    assertFalse(body.contains("manual.txt"));
                });

        verify(files).extractText(eq("manual.txt"), eq("text/plain"), org.mockito.ArgumentMatchers.any(byte[].class));
        assertVectorQueued(vectorStore, "file-flow");
        assertNeo4jWritten(writer);
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void ownerTokenLearnTextThenEvidenceSummarySnapshotReadBackGraphDbLaneWithoutRawEcho() throws Exception {
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        AtomicReference<List<KgChunk>> writtenChunks = new AtomicReference<>(List.of());
        Neo4jKgChunkWriter writer = readbackWriter(writtenChunks);
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        MockMvc mvc = mvc(service(vectorStore, writer, brain, files));

        String rawText = "Alpha and Beta private graphdb readback text";
        mvc.perform(post("/api/admin/graph/learn-text")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "readback-flow",
                                  "text": "Alpha and Beta private graphdb readback text",
                                  "domain": "GENERAL",
                                  "dryRun": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.manifest.sessionHash").exists())
                .andExpect(jsonPath("$.manifest.sessionId").doesNotExist())
                .andExpect(jsonPath("$.manifest.vectorStatus").value("queued"))
                .andExpect(jsonPath("$.manifest.neo4jStatus").value("written"))
                .andExpect(jsonPath("$.manifest.routeStatus").value("ready_unverified"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason")
                        .value("non_dry_run_live_proof_required"));

        mvc.perform(get("/api/admin/graph/learn-evidence")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .param("domain", "GENERAL")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.routeStatus").value("ready_unverified"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason")
                        .value("non_dry_run_live_proof_required"))
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.readBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawEntityValuesIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.returnedCount").value(1))
                .andExpect(jsonPath("$.graphDb.candidates[0].ingestLane").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.candidates[0].origin").value("MANUAL_GRAPHDB"))
                .andExpect(jsonPath("$.graphDb.candidates[0].chunkHash").exists())
                .andExpect(jsonPath("$.graphDb.candidates[0].sessionHash").exists())
                .andExpect(jsonPath("$.graphDb.candidates[0].chunkId").doesNotExist())
                .andExpect(jsonPath("$.graphDb.candidates[0].sessionId").doesNotExist())
                .andExpect(jsonPath("$.graphDb.candidates[0].entityCount").value(2))
                .andExpect(jsonPath("$.graphDb.candidates[0].entityHashes[0]").exists())
                .andExpect(jsonPath("$.graphDb.candidates[0].entities").doesNotExist())
                .andExpect(jsonPath("$.graphDb.candidates[0].hops[0].targetHash").exists())
                .andExpect(jsonPath("$.graphDb.candidates[0].hops[0].connectorHash").exists())
                .andExpect(jsonPath("$.graphDb.candidates[0].hops[0].relationSource")
                        .value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.candidates[0].hops[0].target").doesNotExist())
                .andExpect(result -> assertNoPrivateGraphDbEcho(result.getResponse().getContentAsString(), rawText));

        mvc.perform(get("/api/admin/graph/learn-summary")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .param("domain", "GENERAL")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.routeStatus").value("ready_unverified"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason")
                        .value("non_dry_run_live_proof_required"))
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.readBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.summaryBoundary")
                        .value("graphdb_manual_learning_community_summary"))
                .andExpect(jsonPath("$.graphDb.multiHopBoundary")
                        .value("graphdb_manual_learning_multi_hop_evidence"))
                .andExpect(jsonPath("$.graphDb.projectionSource")
                        .value("Neo4jKgChunkWriter.readManualEvidence"))
                .andExpect(jsonPath("$.graphDb.brainStateCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawEntityValuesIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.communityCount").value(2))
                .andExpect(jsonPath("$.graphDb.communities[0].entityHashes[0]").exists())
                .andExpect(jsonPath("$.graphDb.communities[0].entities").doesNotExist())
                .andExpect(jsonPath("$.graphDb.multiHopEvidence[0].targetHash").exists())
                .andExpect(jsonPath("$.graphDb.multiHopEvidence[0].connectorHash").exists())
                .andExpect(jsonPath("$.graphDb.multiHopEvidence[0].relationSource")
                        .value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.multiHopEvidence[0].target").doesNotExist())
                .andExpect(result -> assertNoPrivateGraphDbEcho(result.getResponse().getContentAsString(), rawText));

        mvc.perform(get("/api/admin/graph/learn-snapshot")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret")
                        .param("domain", "GENERAL")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.routeStatus").value("ready_unverified"))
                .andExpect(jsonPath("$.manifest.routeDisabledReason")
                        .value("non_dry_run_live_proof_required"))
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.readBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.snapshotBoundary")
                        .value("graphdb_manual_learning_brain_snapshot"))
                .andExpect(jsonPath("$.graphDb.brainStateCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawEntityValuesIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.brainStateCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.rawEntityValuesIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.rawSecretsIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.writeBoundary")
                        .value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.snapshot.readBoundary")
                        .value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.snapshot.summaryBoundary")
                        .value("graphdb_manual_learning_community_summary"))
                .andExpect(jsonPath("$.graphDb.snapshot.multiHopBoundary")
                        .value("graphdb_manual_learning_multi_hop_evidence"))
                .andExpect(jsonPath("$.graphDb.snapshot.relationSources[0]")
                        .value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.snapshot.connectorHashes[0]").exists())
                .andExpect(result -> assertNoPrivateGraphDbEcho(result.getResponse().getContentAsString(), rawText));

        assertVectorQueued(vectorStore, "readback-flow");
        assertNeo4jWritten(writer);
        verify(writer, org.mockito.Mockito.atLeast(3)).readManualEvidence(eq("GENERAL"), eq(5));
        verify(brain, never()).recordChunks(anyList());
    }

    private static GraphDbManualLearningService service(VectorStoreService vectorStore,
                                                        Neo4jKgChunkWriter writer,
                                                        BrainStateService brain,
                                                        FileIngestionService files) {
        GraphDbManualLearningProperties props = new GraphDbManualLearningProperties();
        props.setEnabled(true);
        props.setDryRunDefault(false);
        props.setVectorEnabled(true);
        props.setNeo4jEnabled(true);
        props.setBrainStateMirrorEnabled(false);
        GraphRagChunkingService chunking = new GraphRagChunkingService(
                new BrainStateProperties(),
                new DocumentChunkingService(),
                text -> List.of("Alpha", "Beta"),
                writer,
                new UniversalContextLexicon(),
                vectorStore,
                brain,
                mock(ChatMessageRepository.class));
        return new GraphDbManualLearningService(props, chunking, new GraphDbClient(writer), files);
    }

    private static Neo4jKgChunkWriter writer() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                true, "written", null, 1, 2, 1, 1, "neo4j.local", null));
        when(writer.status()).thenReturn(Map.of(
                "enabled", true,
                "endpointHost", "neo4j.local",
                "disabledReason", "",
                "lastWriteStatus", "written",
                "lastWrittenChunks", 1,
                "lastWrittenEntities", 2,
                "lastWrittenRelations", 1,
                "lastWrittenPortMappings", 1));
        return writer;
    }

    private static Neo4jKgChunkWriter readbackWriter(AtomicReference<List<KgChunk>> writtenChunks) {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.writeChunks(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<KgChunk> chunks = List.copyOf((List<KgChunk>) invocation.getArgument(0));
            writtenChunks.set(chunks);
            int entities = chunks.stream().mapToInt(chunk -> chunk.entities().size()).sum();
            int relations = chunks.stream().mapToInt(chunk -> chunk.relations().size()).sum();
            return new Neo4jKgChunkWriter.WriteReport(
                    true, "written", null, chunks.size(), entities, relations, relations, "neo4j.local", null);
        });
        when(writer.status()).thenReturn(Map.of(
                "enabled", true,
                "endpointHost", "neo4j.local",
                "disabledReason", "",
                "lastWriteStatus", "written",
                "lastWrittenChunks", 1,
                "lastWrittenEntities", 2,
                "lastWrittenRelations", 1,
                "lastWrittenPortMappings", 1));
        when(writer.readManualEvidence(eq("GENERAL"), anyInt())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1);
            return manualEvidenceReport(writtenChunks.get(), limit);
        });
        return writer;
    }

    private static Neo4jKgChunkWriter.ManualEvidenceReport manualEvidenceReport(List<KgChunk> chunks, int limit) {
        List<Neo4jKgChunkWriter.ManualEvidenceCandidate> candidates = chunks.stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(chunk -> new Neo4jKgChunkWriter.ManualEvidenceCandidate(
                        chunk.chunkId(),
                        hash12(chunk.sessionId()),
                        "text-hash-only",
                        chunk.sourceText() == null ? 0 : chunk.sourceText().length(),
                        chunk.domain(),
                        chunk.confidence(),
                        chunk.sourceTag(),
                        chunk.docType(),
                        chunk.origin(),
                        chunk.ingestLane(),
                        chunk.entities().stream()
                                .map(entity -> hash12(entity.name()))
                                .distinct()
                                .toList(),
                        chunk.relations().stream()
                                .map(relation -> new Neo4jKgChunkWriter.ManualHop(
                                        hash12(relation.target()),
                                        relation.kind(),
                                        relation.connectorHash12(),
                                        chunk.ingestLane()))
                                .toList()))
                .toList();
        return new Neo4jKgChunkWriter.ManualEvidenceReport(
                true,
                "ok",
                "",
                "neo4j.local",
                candidates.size(),
                candidates,
                null);
    }

    private static MockMvc mvc(GraphDbManualLearningService service) {
        AdminTokenGuardInterceptor interceptor = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", "");
        ReflectionTestUtils.setField(interceptor, "ownerToken", "owner-secret");
        ReflectionTestUtils.setField(interceptor, "tokenRequired", true);
        ReflectionTestUtils.setField(interceptor, "activeProfiles", "local");
        return standaloneSetup(new GraphDbManualLearningController(service))
                .addFilters(new AdminTokenGuardFilter(interceptor))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static void assertVectorQueued(VectorStoreService vectorStore, String sessionId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStore).enqueue(
                org.mockito.ArgumentMatchers.startsWith("graphdb-chunk:"),
                eq("graphdb-manual:" + hash12(sessionId)),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("GRAPHDB_MANUAL_LEARNING", meta.get("doc_type"));
        assertEquals("GRAPHDB_MANUAL", meta.get("source_tag"));
        assertEquals("MANUAL_GRAPHDB", meta.get("origin"));
        assertEquals("graphdb_manual_learning", meta.get("ingest_lane"));
        assertEquals("graphdb-manual:" + hash12(sessionId), meta.get(VectorMetaKeys.META_SID_LOGICAL));
        assertEquals("graphdb-manual:" + hash12(sessionId), meta.get(VectorMetaKeys.META_ORIGINAL_SID));
        assertEquals(hash12(sessionId), meta.get("graphdb_manual_session_hash"));
        assertEquals("false", meta.get("raw_session_id_included"));
        assertFalse(meta.containsValue(sessionId));
        assertFalse(meta.containsKey("ownerToken"));
        assertFalse(meta.containsKey("apiKey"));
    }

    private static void assertNeo4jWritten(Neo4jKgChunkWriter writer) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeChunks(chunksCaptor.capture());
        assertEquals(1, chunksCaptor.getValue().size());
        KgChunk chunk = chunksCaptor.getValue().get(0);
        assertTrue(chunk.chunkId().startsWith("graphdb-chunk:"));
        assertEquals("GRAPHDB_MANUAL", chunk.sourceTag());
        assertEquals("GRAPHDB_MANUAL_LEARNING", chunk.docType());
        assertEquals("MANUAL_GRAPHDB", chunk.origin());
        assertEquals("graphdb_manual_learning", chunk.ingestLane());
    }

    private static void assertNoPrivateGraphDbEcho(String body, String rawText) {
        assertFalse(body.contains("owner-secret"));
        assertFalse(body.contains(rawText));
        assertFalse(body.contains("readback-flow"));
        assertFalse(body.contains("graphdb-chunk:"));
        assertFalse(body.contains("sourceText"));
        assertFalse(body.contains("entities\""));
        assertFalse(body.contains("\"target\""));
    }

    private static String hash12(String value) {
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(value == null ? "" : value).substring(0, 12);
    }
}
