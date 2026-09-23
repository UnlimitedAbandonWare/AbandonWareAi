package com.example.lms.scheduler;

import com.example.lms.moe.RgbLogSignalParser;
import com.example.lms.moe.RgbMoeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoEvolveDebugStoreRedactionTest {

    @TempDir
    Path tempDir;

    @Test
    void rotationListingHasAnExplicitResourceOwner() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/scheduler/AutoEvolveDebugStore.java"));
        String method = methodBody(source, "private void cleanupOldRotations", "private void writeIndexFile");

        assertTrue(hasTryWithResourcesFilesList(method), method);
    }

    @Test
    void startupListingHasAnExplicitResourceOwner() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/scheduler/AutoEvolveDebugStore.java"));
        String method = methodBody(source, "private List<AutoEvolveRunDebug> loadRecentNdjson", "private static String stripExt");

        assertTrue(hasTryWithResourcesFilesList(method), method);
    }

    @Test
    void persistedNdjsonDoesNotExposeRawRunDebugStrings() throws Exception {
        RgbMoeProperties props = new RgbMoeProperties();
        props.getDebug().setEnabled(true);
        props.getDebug().setPersistEnabled(true);
        props.getDebug().setPersistDir(tempDir.toString());
        props.getDebug().setNdjsonFileName("runs.ndjson");
        props.getDebug().setIndexFileName("runs-index.json");

        AutoEvolveDebugStore store = new AutoEvolveDebugStore(props, new ObjectMapper().findAndRegisterModules());
        String rawSession = "session-private-001";
        String rawQuery = "private student autoevolve query";
        String rawFinalQuery = rawQuery + " final";
        String rawError = "failed on query=" + rawQuery;
        String rawBody = "{\"query\":\"" + rawQuery + "\"}";

        AutoEvolveRunDebug debug = new AutoEvolveRunDebug(
                rawSession,
                "manual trigger",
                false,
                true,
                AutoEvolveRunDebug.Outcome.SUCCESS,
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:01Z"),
                new RgbLogSignalParser.Features(Map.of("timeout", 1), List.of(rawQuery)),
                null,
                null,
                List.of(rawQuery),
                List.of(rawFinalQuery),
                new AutoEvolveRunDebug.ExpansionDebug(true, 12L, 1, 0, "TimeoutException", rawError),
                new AutoEvolveRunDebug.BlueCallDebug(true, false, 34L, 1, 0, 429, "retry later",
                        Map.of("Authorization", "Bearer " + "raw-private-token"),
                        "HttpStatusException", rawError, rawBody, true),
                "C:\\private\\autoevolve\\report.json",
                null,
                "RuntimeException",
                rawError);

        store.record(debug);

        String ndjson = Files.readString(tempDir.resolve("runs.ndjson"), StandardCharsets.UTF_8);
        String index = Files.readString(tempDir.resolve("runs-index.json"), StandardCharsets.UTF_8);
        assertFalse(ndjson.contains(rawSession), ndjson);
        assertFalse(ndjson.contains(rawQuery), ndjson);
        assertFalse(ndjson.contains(rawFinalQuery), ndjson);
        assertFalse(ndjson.contains("raw-private-token"), ndjson);
        assertFalse(ndjson.contains("C:\\private\\autoevolve\\report.json"), ndjson);
        assertFalse(ndjson.contains("RuntimeException"), ndjson);
        assertFalse(ndjson.contains("TimeoutException"), ndjson);
        assertFalse(ndjson.contains("HttpStatusException"), ndjson);
        assertFalse(index.contains(rawSession), index);
        assertFalse(index.contains(rawQuery), index);
        assertFalse(index.contains(rawFinalQuery), index);
        assertFalse(index.contains("raw-private-token"), index);
        assertFalse(index.contains("C:\\private\\autoevolve\\report.json"), index);
        assertFalse(index.contains("RuntimeException"), index);
        assertFalse(index.contains("TimeoutException"), index);
        assertFalse(index.contains("HttpStatusException"), index);
        assertTrue(ndjson.contains("hash:"), ndjson);
        assertTrue(index.contains("hash:"), index);
    }

    @Test
    void inMemoryLastAndRecentDoNotExposeRawRunDebugStrings() {
        RgbMoeProperties props = new RgbMoeProperties();
        props.getDebug().setEnabled(true);
        AutoEvolveDebugStore store = new AutoEvolveDebugStore(props, new ObjectMapper().findAndRegisterModules());
        String rawSession = "session-private-memory-001";
        String rawQuery = "private in-memory autoevolve query";
        String rawError = "failed on query=" + rawQuery;
        String rawBody = "{\"query\":\"" + rawQuery + "\"}";

        store.record(new AutoEvolveRunDebug(
                rawSession,
                "manual trigger",
                false,
                true,
                AutoEvolveRunDebug.Outcome.FAILED,
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:01Z"),
                new RgbLogSignalParser.Features(Map.of("timeout", 1), List.of(rawQuery)),
                null,
                null,
                List.of(rawQuery),
                List.of(rawQuery + " final"),
                new AutoEvolveRunDebug.ExpansionDebug(true, 12L, 1, 0, "TimeoutException", rawError),
                new AutoEvolveRunDebug.BlueCallDebug(true, false, 34L, 1, 0, 429, "retry later",
                        Map.of("Authorization", "Bearer " + "raw-private-token"),
                        "HttpStatusException", rawError, rawBody, true),
                "C:\\private\\autoevolve\\report.json",
                null,
                "RuntimeException",
                rawError));

        AutoEvolveRunDebug last = store.last();
        assertNotNull(last);
        String dump = String.valueOf(last) + store.recent(1);
        assertFalse(dump.contains(rawSession), dump);
        assertFalse(dump.contains(rawQuery), dump);
        assertFalse(dump.contains("raw-private-token"), dump);
        assertFalse(dump.contains("C:\\private\\autoevolve\\report.json"), dump);
        assertFalse(dump.contains("RuntimeException"), dump);
        assertTrue(last.sessionId().startsWith("hash:"), last.sessionId());
        assertTrue(last.baseQueries().get(0).startsWith("hash:"), String.valueOf(last.baseQueries()));
        assertTrue(last.blueCall().responseBodyPreview().startsWith("hash:"),
                String.valueOf(last.blueCall().responseBodyPreview()));
    }

    @Test
    void loadingLegacyRawNdjsonSanitizesInMemoryRing() throws Exception {
        RgbMoeProperties props = new RgbMoeProperties();
        props.getDebug().setEnabled(true);
        props.getDebug().setPersistEnabled(true);
        props.getDebug().setPersistDir(tempDir.toString());
        props.getDebug().setNdjsonFileName("legacy.ndjson");
        props.getDebug().setIndexFileName("legacy-index.json");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String rawSession = "session-private-legacy-001";
        String rawQuery = "private legacy autoevolve query";
        String rawError = "failed on query=" + rawQuery;
        AutoEvolveRunDebug legacyRaw = new AutoEvolveRunDebug(
                rawSession,
                "manual trigger",
                false,
                true,
                AutoEvolveRunDebug.Outcome.FAILED,
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:01Z"),
                new RgbLogSignalParser.Features(Map.of("timeout", 1), List.of(rawQuery)),
                null,
                null,
                List.of(rawQuery),
                List.of(rawQuery + " final"),
                new AutoEvolveRunDebug.ExpansionDebug(true, 12L, 1, 0, "TimeoutException", rawError),
                new AutoEvolveRunDebug.BlueCallDebug(true, false, 34L, 1, 0, 429, "retry later",
                        Map.of("Authorization", "Bearer " + "raw-private-token"),
                        "HttpStatusException", rawError, "{\"query\":\"" + rawQuery + "\"}", true),
                "C:\\private\\autoevolve\\legacy-report.json",
                null,
                "RuntimeException",
                rawError);
        Files.writeString(tempDir.resolve("legacy.ndjson"),
                mapper.writeValueAsString(legacyRaw) + "\n",
                StandardCharsets.UTF_8);

        AutoEvolveDebugStore loaded = new AutoEvolveDebugStore(props, mapper);
        AutoEvolveRunDebug last = loaded.last();
        assertNotNull(last);

        String dump = String.valueOf(last) + loaded.recent(1);
        assertFalse(dump.contains(rawSession), dump);
        assertFalse(dump.contains(rawQuery), dump);
        assertFalse(dump.contains("raw-private-token"), dump);
        assertFalse(dump.contains("C:\\private\\autoevolve\\legacy-report.json"), dump);
        assertFalse(dump.contains("RuntimeException"), dump);
        assertTrue(last.sessionId().startsWith("hash:"), last.sessionId());
        assertTrue(last.baseQueries().get(0).startsWith("hash:"), String.valueOf(last.baseQueries()));
        assertTrue(last.blueCall().responseBodyPreview().startsWith("hash:"),
                String.valueOf(last.blueCall().responseBodyPreview()));
    }

    @Test
    void persistenceFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/scheduler/AutoEvolveDebugStore.java"));

        assertTrue(source.contains("traceSuppressed(\"load.createDirectories\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"load.recentNdjson\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"persist.createDirectories\", e);"));
        assertTrue(source.contains("traceSuppressed(\"persist.writeNdjson\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"persist.writeIndex\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"rotate.ndjson\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"cleanup.lastModified\", e);"));
        assertTrue(source.contains("traceSuppressed(\"cleanup.delete\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"cleanup.outer\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"index.write\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"load.listNdjson\", e);"));
        assertTrue(source.contains("traceSuppressed(\"load.lastModified\", e);"));
        assertTrue(source.contains("traceSuppressed(\"load.parseLine\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"tail.randomAccess\", e);"));
        assertTrue(source.contains("traceSuppressed(\"tail.readAllLines\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"autoevolve.debug.suppressed.\" + safeStage, true);"));
    }

    private static String methodBody(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0, startMarker);
        assertTrue(end > start, endMarker);
        return source.substring(start, end);
    }

    private static boolean hasTryWithResourcesFilesList(String methodBody) {
        return Pattern.compile(
                        "try\\s*\\(\\s*var\\s+\\w+\\s*=\\s*Files\\.list\\(persistDir\\)\\s*\\)")
                .matcher(methodBody)
                .find();
    }
}
