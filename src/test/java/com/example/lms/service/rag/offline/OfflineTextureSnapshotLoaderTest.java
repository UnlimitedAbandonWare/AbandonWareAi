package com.example.lms.service.rag.offline;

import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineTextureSnapshotLoaderTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void missingManifestIsColdStartNotFailure() {
        OfflineTextureProperties props = props();
        OfflineTextureSnapshotLoader loader = new OfflineTextureSnapshotLoader(props, new ObjectMapper());

        OfflineTextureSnapshotLoader.TextureLookup lookup = loader.lookup("latest KG news", List.of("kg"), true);

        assertTrue(lookup.coldStart());
        assertEquals("offline_texture_missing", lookup.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("rag.offlineTexture.onlineSearchNeeded"));
    }

    @Test
    void writerStoresHashesAndLoaderMatchesWithoutRawPrompt() throws Exception {
        OfflineTextureProperties props = props();
        props.setWriteEnabled(true);
        ObjectMapper mapper = new ObjectMapper();
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, mapper);
        OfflineTextureSnapshotLoader loader = new OfflineTextureSnapshotLoader(props, mapper);
        String rawAnchor = "private GraphRAG prompt with sk-local secret";
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of(rawAnchor), 0.90d, 0.10d, 2, 0, "ok");

        writer.writeFrom("queryhash", anchor, Map.of(
                "rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop"), "finalRetention", 0.0d),
                "rag.fusion.final.kgCount", 0,
                "rawPrompt", rawAnchor,
                "rawQuery", "raw sensitive query",
                "Authorization", "Bearer secret",
                "api_key", "sk-local-secret",
                "url", "https://example.test/private"));

        String manifest = Files.readString(Path.of(props.getManifestPath()), StandardCharsets.UTF_8);
        assertFalse(manifest.contains(rawAnchor));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = mapper.readValue(Files.readAllLines(Path.of(props.getManifestPath())).get(0), Map.class);
        String snapshotPath = String.valueOf(row.get("snapshotPath"));
        assertFalse(Path.of(snapshotPath).isAbsolute());
        assertTrue(snapshotPath.startsWith("snapshots/"));
        Path snapshot = Path.of(props.getManifestPath()).getParent().resolve(snapshotPath);
        String json = Files.readString(snapshot, StandardCharsets.UTF_8);
        assertFalse(json.contains(rawAnchor));
        assertFalse(json.contains("raw sensitive query"));
        assertFalse(json.contains("Bearer secret"));
        assertFalse(json.contains("sk-local-secret"));
        assertFalse(json.contains("example.test/private"));
        assertTrue(json.contains("kg_final_drop"));

        OfflineTextureSnapshotLoader.TextureLookup lookup = loader.lookup("GraphRAG", List.of(rawAnchor), false);
        assertEquals(1, lookup.loadedSnapshots());
        assertEquals(1, lookup.matchedSnapshots());
        assertEquals("offline_texture_hit", lookup.reason());
    }

    @Test
    void writerDoesNotCopyRawQueryTokenIntoSnapshotIdentifiers() throws Exception {
        OfflineTextureProperties props = props();
        props.setWriteEnabled(true);
        ObjectMapper mapper = new ObjectMapper();
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, mapper);
        String rawQueryToken = "private offline texture owner token";
        String normalizedRaw = "private_offline_texture_owner_token";
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of("GraphRAG"), 0.90d, 0.10d, 1, 0, "ok");

        writer.writeFrom(rawQueryToken, anchor, Map.of("rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop"))));

        String manifest = Files.readString(Path.of(props.getManifestPath()), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = mapper.readValue(Files.readAllLines(Path.of(props.getManifestPath())).get(0), Map.class);
        Path snapshot = Path.of(props.getManifestPath()).getParent().resolve(String.valueOf(row.get("snapshotPath")));
        String json = Files.readString(snapshot, StandardCharsets.UTF_8);
        String tracedId = String.valueOf(TraceStore.get("rag.offlineTexture.write.snapshotId"));

        assertFalse(manifest.contains(rawQueryToken), manifest);
        assertFalse(manifest.contains(normalizedRaw), manifest);
        assertFalse(json.contains(rawQueryToken), json);
        assertFalse(json.contains(normalizedRaw), json);
        assertFalse(tracedId.contains(rawQueryToken), tracedId);
        assertFalse(tracedId.contains(normalizedRaw), tracedId);
        assertTrue(tracedId.startsWith("ot-"), tracedId);
    }

    @Test
    void writerDefaultDisabledCreatesNoFiles() {
        OfflineTextureProperties props = props();
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, new ObjectMapper());

        writer.writeFrom("queryhash", null, Map.of("rag.eval.kgAxis", Map.of()));

        assertEquals("write_disabled", TraceStore.get("rag.offlineTexture.write.status"));
        assertFalse(Files.exists(Path.of(props.getManifestPath())));
        assertFalse(Files.exists(Path.of(props.getSnapshotDir())));
    }

    @Test
    void writerWriteFailureUsesStableStatusWithoutExceptionClassName() throws Exception {
        OfflineTextureProperties props = props();
        props.setWriteEnabled(true);
        Path snapshotDirAsFile = Path.of(props.getSnapshotDir());
        Files.writeString(snapshotDirAsFile, "not-a-directory", StandardCharsets.UTF_8);
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, new ObjectMapper());
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of("GraphRAG"), 0.90d, 0.10d, 1, 0, "ok");

        writer.writeFrom("queryhash", anchor, Map.of("rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop"))));

        assertEquals("write_error:offline_texture_write_failed",
                TraceStore.get("rag.offlineTexture.write.status"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("FileAlreadyExistsException"));
        assertFalse(trace.contains("Exception"));
    }

    @Test
    void disabledLoaderTraceReportsEnabledFalse() {
        OfflineTextureProperties props = props();
        props.setEnabled(false);
        OfflineTextureSnapshotLoader loader = new OfflineTextureSnapshotLoader(props, new ObjectMapper());

        OfflineTextureSnapshotLoader.TextureLookup lookup = loader.lookup("kg status", List.of("kg"), false);

        assertEquals("disabled", lookup.reason());
        assertEquals(Boolean.FALSE, TraceStore.get("rag.offlineTexture.enabled"));
        assertEquals("disabled", TraceStore.get("rag.offlineTexture.reason"));
    }

    @Test
    void malformedManifestUsesStableReadErrorReasonWithoutParserClassName() throws Exception {
        OfflineTextureProperties props = props();
        Path manifest = Path.of(props.getManifestPath());
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{", StandardCharsets.UTF_8);
        OfflineTextureSnapshotLoader loader = new OfflineTextureSnapshotLoader(props, new ObjectMapper());

        OfflineTextureSnapshotLoader.TextureLookup lookup = loader.lookup("latest KG status", List.of("kg"), true);

        assertEquals("offline_texture_read_error", lookup.reason());
        assertEquals("offline_texture_read_error", TraceStore.get("rag.offlineTexture.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("JsonParseException"));
        assertFalse(trace.contains("Exception"));
    }

    @Test
    void expiredSnapshotIsStaleOnlyForFreshnessQueries() throws Exception {
        OfflineTextureProperties props = props();
        ObjectMapper mapper = new ObjectMapper();
        OfflineTextureSnapshot expired = new OfflineTextureSnapshot(
                "expired",
                1,
                "GENERAL",
                Instant.now().minusSeconds(7200).toString(),
                Instant.now().minusSeconds(3600).toString(),
                List.of("anchorhash"),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of("kg_final_drop"));
        Files.createDirectories(Path.of(props.getManifestPath()).getParent());
        Files.writeString(Path.of(props.getManifestPath()), mapper.writeValueAsString(expired) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        OfflineTextureSnapshotLoader loader = new OfflineTextureSnapshotLoader(props, mapper);
        OfflineTextureSnapshotLoader.TextureLookup lookup = loader.lookup("latest KG status", List.of("anchorhash"), true);

        assertTrue(lookup.stale());
        assertEquals("offline_texture_stale", lookup.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("rag.offlineTexture.onlineSearchNeeded"));

        OfflineTextureSnapshotLoader.TextureLookup nonFresh = loader.lookup("KG status", List.of("anchorhash"), false);

        assertFalse(nonFresh.stale());
        assertEquals("offline_texture_expired_ignored", nonFresh.reason());
        assertEquals(Boolean.FALSE, TraceStore.get("rag.offlineTexture.onlineSearchNeeded"));
    }

    @Test
    void writerTrimRemovesOrphanSnapshotFilesBestEffort() throws Exception {
        OfflineTextureProperties props = props();
        props.setWriteEnabled(true);
        props.setMaxSnapshots(1);
        ObjectMapper mapper = new ObjectMapper();
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, mapper);
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of("GraphRAG"), 0.90d, 0.10d, 1, 0, "ok");

        writer.writeFrom("first", anchor, Map.of("rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRow = mapper.readValue(Files.readAllLines(Path.of(props.getManifestPath())).get(0), Map.class);
        Path firstSnapshot = Path.of(props.getManifestPath()).getParent().resolve(String.valueOf(firstRow.get("snapshotPath")));
        assertTrue(Files.exists(firstSnapshot));

        writer.writeFrom("second", anchor, Map.of("rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop"))));

        assertFalse(Files.exists(firstSnapshot));
        assertEquals(1, Files.readAllLines(Path.of(props.getManifestPath())).stream()
                .filter(line -> !line.isBlank())
                .count());
    }

    @Test
    void writerOrphanCleanupFailureUsesStableStatusWithoutExceptionClassName() throws Exception {
        OfflineTextureProperties props = props();
        props.setWriteEnabled(true);
        props.setMaxSnapshots(1);
        Path manifest = Path.of(props.getManifestPath());
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\n", StandardCharsets.UTF_8);
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, new ObjectMapper());
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of("GraphRAG"), 0.90d, 0.10d, 1, 0, "ok");

        writer.writeFrom("queryhash", anchor, Map.of("rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop"))));

        assertEquals("skipped:offline_texture_orphan_cleanup_failed",
                TraceStore.get("rag.offlineTexture.write.orphanCleanup"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("JsonParseException"));
        assertFalse(trace.contains("Exception"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void orchestratorOfflineTextureProjectionExcludesRawTraceKeys() {
        TraceStore.put("rag.eval.kgAxis", Map.of("signals", List.of("kg_final_drop")));
        TraceStore.put("rag.metrics.textureHitRate", 0.5d);
        TraceStore.put("rawPrompt", "raw prompt");
        TraceStore.put("rawQuery", "raw query");
        TraceStore.put("Authorization", "Bearer secret");
        TraceStore.put("ownerToken", "owner-secret");
        TraceStore.put("url", "https://example.test/private");

        Map<String, Object> projection = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                UnifiedRagOrchestrator.class,
                "offlineTextureTraceProjection");

        assertTrue(projection.containsKey("rag.eval.kgAxis"));
        assertTrue(projection.containsKey("rag.metrics.textureHitRate"));
        assertFalse(projection.containsKey("rawPrompt"));
        assertFalse(projection.containsKey("rawQuery"));
        assertFalse(projection.containsKey("Authorization"));
        assertFalse(projection.containsKey("ownerToken"));
        assertFalse(projection.containsKey("url"));
    }

    @Test
    void writerNumericProjectionParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/offline/OfflineTextureSnapshotWriter.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private static double toDouble");
        int parse = source.indexOf("Double.parseDouble", start);
        int end = source.indexOf("\n    }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "toDouble helper should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }

    @Test
    void offlineTextureFailSoftCatchesLeaveStageOnlyBreadcrumbs() throws Exception {
        String loader = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/offline/OfflineTextureSnapshotLoader.java"), StandardCharsets.UTF_8);
        String writer = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/offline/OfflineTextureSnapshotWriter.java"), StandardCharsets.UTF_8);

        for (String stage : List.of(
                "stage=read.manifest",
                "stage=expiry.parse",
                "stage=lookup.trace")) {
            assertTrue(loader.contains(stage), "OfflineTextureSnapshotLoader needs stage breadcrumb: " + stage);
        }
        for (String stage : List.of(
                "stage=write.snapshot",
                "stage=write.trace",
                "stage=write.statusTrace",
                "stage=number.parse",
                "stage=orphan.cleanup",
                "stage=relative.path")) {
            assertTrue(writer.contains(stage), "OfflineTextureSnapshotWriter needs stage breadcrumb: " + stage);
        }
    }

    private OfflineTextureProperties props() {
        OfflineTextureProperties props = new OfflineTextureProperties();
        props.setManifestPath(tempDir.resolve("offline_texture_manifest.jsonl").toString());
        props.setSnapshotDir(tempDir.resolve("snapshots").toString());
        return props;
    }
}
