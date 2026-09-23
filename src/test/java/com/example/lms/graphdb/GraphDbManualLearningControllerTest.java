package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import com.example.lms.security.AdminTokenGuardFilter;
import com.example.lms.security.AdminTokenGuardInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GraphDbManualLearningControllerTest {

    @Test
    void learnTextDelegatesWithoutEchoingRawText() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.learnText(eq("s1"), eq("raw private text"), eq("GENERAL"), eq(true)))
                .thenReturn(new GraphDbManualLearningService.LearnReport(
                        true,
                        "graphdb_manual_learning",
                        "dry_run",
                        "",
                        true,
                        "s1",
                        1,
                        2,
                        1,
                        0,
                        "hash-only",
                        Map.of("lane", "graphdb_manual_learning", "dryRun", true),
                        Map.of("backend", "neo4j", "enabled", false, "disabledReason", "disabled")));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/admin/graph/learn-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"text\":\"raw private text\",\"domain\":\"GENERAL\",\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.status").value("dry_run"))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.rawIdentifiersIncluded").value(false))
                .andExpect(jsonPath("$.textHash").value("hash-only"))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("\"s1\"")));

        verify(service).learnText("s1", "raw private text", "GENERAL", true);
    }

    @Test
    void disabledRouteReturnsServiceUnavailableWithReason() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.learnText(eq("s1"), eq("text"), eq("GENERAL"), eq(false)))
                .thenReturn(new GraphDbManualLearningService.LearnReport(
                        false,
                        "graphdb_manual_learning",
                        "disabled",
                        "route_disabled",
                        false,
                        "s1",
                        0,
                        0,
                        0,
                        0,
                        "",
                        Map.of("lane", "graphdb_manual_learning", "disabledReason", "route_disabled"),
                        Map.of("backend", "neo4j", "enabled", false)));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/admin/graph/learn-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"text\":\"text\",\"domain\":\"GENERAL\",\"dryRun\":false}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.disabledReason").value("route_disabled"))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.sessionId").doesNotExist());
    }

    @Test
    void learnFileReadFailureKeepsFileBoundary() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        MultipartFile file = mock(MultipartFile.class);
        IOException failure = new IOException("private file read failure");
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("manual.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getBytes()).thenThrow(failure);
        when(service.fileReadFailed(eq("s-file"), eq("manual.txt"), eq("text/plain"), eq(false), any(Exception.class)))
                .thenReturn(new GraphDbManualLearningService.LearnReport(
                        false,
                        "graphdb_manual_learning",
                        "disabled",
                        "file_read_failed",
                        false,
                        "s-file",
                        0,
                        0,
                        0,
                        0,
                        "",
                        Map.of(
                                "inputKind", "file",
                                "disabledReason", "file_read_failed",
                                "fileNamePresent", true,
                                "mimeTypePresent", true,
                                "failureClass", "IOException",
                                "rawTextIncluded", false),
                        Map.of("backend", "neo4j", "writeBoundary", "graphdb_manual_learning")));

        ResponseEntity<GraphDbManualLearningService.LearnReport> response =
                new GraphDbManualLearningController(service).learnFile(file, "s-file", "GENERAL", false);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        GraphDbManualLearningService.LearnReport body = response.getBody();
        assertEquals("file_read_failed", body.disabledReason());
        assertEquals("file", body.manifest().get("inputKind"));
        assertEquals(true, body.manifest().get("fileNamePresent"));
        assertEquals(true, body.manifest().get("mimeTypePresent"));
        assertFalse(body.toString().contains("private file read failure"));
        verify(service).fileReadFailed(eq("s-file"), eq("manual.txt"), eq("text/plain"), eq(false),
                any(Exception.class));
    }

    @Test
    void controllerSuppressedTraceIncludesSafeStageAndErrorType() throws Exception {
        TraceStore.clear();
        String rawStage = "graphDb.controller.fileRead " + com.example.lms.test.SecretFixtures.openAiKey();
        Method method = GraphDbManualLearningController.class.getDeclaredMethod(
                "traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, rawStage, new IOException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("graphdb.controller.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("graphdb.controller.suppressed." + safeStage));
        assertEquals("IOException", TraceStore.get("graphdb.controller.suppressed.errorType"));
        assertEquals("IOException", TraceStore.get("graphdb.controller.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
        TraceStore.clear();
    }

    @Test
    void statusReturnsReadWriteBoundaryManifest() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.status()).thenReturn(new GraphDbManualLearningService.LearnReport(
                true,
                "graphdb_manual_learning",
                "ready",
                "",
                true,
                "",
                0,
                0,
                0,
                0,
                "",
                Map.of("statusOnly", true),
                Map.of("backend", "neo4j", "writeBoundary", "graphdb_manual_learning")));

        mvc(service).perform(get("/api/admin/graph/learn-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"));
    }

    @Test
    void evidenceReturnsReadBoundaryWithoutRawText() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.learnEvidence(eq("GENERAL"), eq(5))).thenReturn(new GraphDbManualLearningService.LearnReport(
                true,
                "graphdb_manual_learning",
                "ok",
                "",
                true,
                "",
                0,
                0,
                0,
                0,
                "",
                Map.of("readOnly", true),
                Map.of(
                        "backend", "neo4j",
                        "readBoundary", "graphdb_manual_learning",
                        "returnedCount", 1,
                        "candidates", java.util.List.of(Map.of(
                                "chunkId", "graphdb-chunk:1",
                                "textHash", "hash-only",
                                "entityCount", 1,
                                "entityHashes", java.util.List.of("entity-hash-only"))))));

        mvc(service).perform(get("/api/admin/graph/learn-evidence")
                        .param("domain", "GENERAL")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graphDb.readBoundary").value("graphdb_manual_learning"))
                .andExpect(jsonPath("$.graphDb.candidates[0].textHash").value("hash-only"))
                .andExpect(jsonPath("$.graphDb.candidates[0].entityHashes[0]").value("entity-hash-only"))
                .andExpect(jsonPath("$.graphDb.candidates[0].entities").doesNotExist())
                .andExpect(jsonPath("$.graphDb.candidates[0].text").doesNotExist())
                .andExpect(jsonPath("$.graphDb.candidates[0].sourceText").doesNotExist());
    }

    @Test
    void summaryReturnsCommunityProjectionWithoutRawText() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.learnSummary(eq("GENERAL"), eq(5))).thenReturn(new GraphDbManualLearningService.LearnReport(
                true,
                "graphdb_manual_learning",
                "ok",
                "",
                true,
                "",
                0,
                0,
                0,
                0,
                "",
                Map.of("readOnly", true, "projection", "community_summary"),
                Map.of(
                        "backend", "neo4j",
                        "summaryBoundary", "graphdb_manual_learning_community_summary",
                        "communityCount", 1,
                        "rawTextIncluded", false,
                        "communities", java.util.List.of(Map.of(
                                "communityId", "community:hash-only",
                                "entityHashes", java.util.List.of("hash-only"))),
                        "multiHopEvidence", java.util.List.of(Map.of(
                                "targetHash", "target-hash",
                                "pathHash", "path-hash")))));

        mvc(service).perform(get("/api/admin/graph/learn-summary")
                        .param("domain", "GENERAL")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.projection").value("community_summary"))
                .andExpect(jsonPath("$.graphDb.summaryBoundary")
                        .value("graphdb_manual_learning_community_summary"))
                .andExpect(jsonPath("$.graphDb.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.communities[0].entityHashes[0]").value("hash-only"))
                .andExpect(jsonPath("$.graphDb.communities[0].entities").doesNotExist())
                .andExpect(jsonPath("$.graphDb.multiHopEvidence[0].target").doesNotExist());
    }

    @Test
    void snapshotReturnsAdminOnlyGraphDbBrainSnapshotProjection() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.learnSnapshot(eq("GENERAL"), eq(5))).thenReturn(new GraphDbManualLearningService.LearnReport(
                true,
                "graphdb_manual_learning",
                "ok",
                "",
                true,
                "",
                0,
                0,
                0,
                0,
                "",
                Map.of("readOnly", true, "projection", "graphdb_brain_snapshot"),
                Map.of(
                        "backend", "neo4j",
                        "snapshotBoundary", "graphdb_manual_learning_brain_snapshot",
                        "brainStateCoupled", false,
                        "queryTimeRetrievalCoupled", false,
                        "queryTimeAnchorMapCoupled", false,
                        "rawTextIncluded", false,
                        "snapshot", Map.of(
                                "snapshotBoundary", "graphdb_manual_learning_brain_snapshot",
                                "candidateCount", 1,
                                "communityIds", java.util.List.of("community:hash-only"),
                                "brainStateCoupled", false,
                                "queryTimeRetrievalCoupled", false,
                                "queryTimeAnchorMapCoupled", false,
                                "rawTextIncluded", false))));

        mvc(service).perform(get("/api/admin/graph/learn-snapshot")
                        .param("domain", "GENERAL")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.projection").value("graphdb_brain_snapshot"))
                .andExpect(jsonPath("$.graphDb.snapshotBoundary")
                        .value("graphdb_manual_learning_brain_snapshot"))
                .andExpect(jsonPath("$.graphDb.brainStateCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.rawTextIncluded").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.communityIds[0]").value("community:hash-only"))
                .andExpect(jsonPath("$.graphDb.snapshot.queryTimeRetrievalCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.queryTimeAnchorMapCoupled").value(false))
                .andExpect(jsonPath("$.graphDb.snapshot.text").doesNotExist())
                .andExpect(jsonPath("$.graphDb.snapshot.sourceText").doesNotExist());
    }

    @Test
    void adminFilterRejectsMissingTokenBeforeGraphServiceRuns() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        MockMvc guarded = mvcWithAdminFilter(service, "owner-secret");

        guarded.perform(get("/api/admin/graph/learn-status"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));
        guarded.perform(post("/api/admin/graph/learn-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"text\":\"private text\",\"dryRun\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));
        guarded.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                "/api/admin/graph/learn-file")
                        .file("file", "private bytes".getBytes()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));
        guarded.perform(get("/api/admin/graph/learn-evidence"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));
        guarded.perform(get("/api/admin/graph/learn-summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));
        guarded.perform(get("/api/admin/graph/learn-snapshot"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));

        verifyNoInteractions(service);
    }

    @Test
    void adminFilterAcceptsOwnerTokenWithoutEchoingTokenValue() throws Exception {
        GraphDbManualLearningService service = mock(GraphDbManualLearningService.class);
        when(service.status()).thenReturn(new GraphDbManualLearningService.LearnReport(
                true,
                "graphdb_manual_learning",
                "ready",
                "",
                true,
                "",
                0,
                0,
                0,
                0,
                "",
                Map.of(
                        "adminBoundary", "/api/admin/graph",
                        "acceptedTokenHeaders", java.util.List.of("X-Admin-Token", "X-Owner-Token")),
                Map.of("backend", "neo4j", "writeBoundary", "graphdb_manual_learning")));

        mvcWithAdminFilter(service, "owner-secret").perform(get("/api/admin/graph/learn-status")
                        .header(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.adminBoundary").value("/api/admin/graph"))
                .andExpect(jsonPath("$.manifest.acceptedTokenHeaders[1]").value("X-Owner-Token"))
                .andExpect(jsonPath("$.graphDb.writeBoundary").value("graphdb_manual_learning"))
                .andExpect(result -> assertFalse(
                        result.getResponse().getContentAsString().contains("owner-secret")));

        verify(service).status();
    }

    private static MockMvc mvc(GraphDbManualLearningService service) {
        return standaloneSetup(new GraphDbManualLearningController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static MockMvc mvcWithAdminFilter(GraphDbManualLearningService service, String ownerToken) {
        AdminTokenGuardInterceptor interceptor = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", "");
        ReflectionTestUtils.setField(interceptor, "ownerToken", ownerToken);
        ReflectionTestUtils.setField(interceptor, "tokenRequired", true);
        ReflectionTestUtils.setField(interceptor, "activeProfiles", "local");
        return standaloneSetup(new GraphDbManualLearningController(service))
                .addFilters(new AdminTokenGuardFilter(interceptor))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
