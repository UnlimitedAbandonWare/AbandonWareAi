package com.example.lms.conversation.archive;

import ai.abandonware.nova.orch.trace.OrchTrace;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class ConversationArchiveIngestService {

    private static final int MAX_ENTRIES = 500;
    private static final int MAX_TREE = 200;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TOTAL_DECOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECORDS = 5000;
    private static final int CHUNK_CHARS = 3200;
    private static final int CHUNK_MESSAGES = 10;

    private final VectorStoreService vectorStoreService;
    private final ObjectProvider<DebugEventStore> debugEventStore;

    private final ConversationExportParser parser = new ConversationExportParser();
    private final ConversationNoiseClassifier classifier = new ConversationNoiseClassifier();
    private final ConversationTopicTimelineBuilder chunkBuilder = new ConversationTopicTimelineBuilder();

    public ConversationArchiveIngestReport ingest(List<MultipartFile> files, String sessionId) {
        List<MultipartFile> safeFiles = files == null ? List.of() : files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        if (safeFiles.isEmpty() || safeFiles.stream().anyMatch(f -> !isZip(f))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_archive_type");
        }

        long started = System.currentTimeMillis();
        String sid = (sessionId == null || sessionId.isBlank()) ? "__TRANSIENT__" : sessionId.trim();
        Stats stats = new Stats();
        stats.zipCount = safeFiles.size();
        List<StagedChunk> stagedChunks = new ArrayList<>();

        for (MultipartFile file : safeFiles) {
            readZip(file, sid, stats, stagedChunks);
        }

        for (StagedChunk chunk : stagedChunks) {
            vectorStoreService.enqueue(
                    chunk.id(), chunk.sessionId(), chunk.text(), chunk.metadata());
            stats.ingestedCount++;
        }

        Map<String, Integer> counts = counts(stats.counts);
        int skipped = counts.get(ConversationMessageKind.QUARANTINED.wireName());
        Map<String, Object> trace = tracePayload(sid, stats, counts, System.currentTimeMillis() - started);
        emitTrace(trace, stats, counts);
        emitDebug(trace);

        return new ConversationArchiveIngestReport(
                true,
                SafeRedactor.hashValue(sid),
                stats.zipCount,
                stats.entryCount,
                stats.txtEntryCount,
                stats.truncated,
                List.copyOf(stats.tree),
                counts,
                stats.ingestedCount,
                skipped,
                trace);
    }

    private void readZip(
            MultipartFile file,
            String sessionId,
            Stats stats,
            List<StagedChunk> stagedChunks) {
        try (ZipInputStream zin = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (stats.entryCount >= MAX_ENTRIES) {
                    stats.truncated = true;
                    readBounded(zin, 0, stats);
                    continue;
                }
                stats.entryCount++;
                String entryName = safeEntryName(entry.getName());
                if (stats.tree.size() < MAX_TREE) {
                    stats.tree.add(entry.isDirectory() ? entryName + "/" : entryName);
                }
                boolean directoryEntry = entry.isDirectory();
                boolean textEntry = !directoryEntry
                        && entryName.toLowerCase(Locale.ROOT).endsWith(".txt");
                boolean parseTextEntry = textEntry && stats.recordCount < MAX_RECORDS;
                EntryRead read = readBounded(zin, parseTextEntry ? MAX_ENTRY_BYTES : 0, stats);
                if (directoryEntry || !textEntry) {
                    continue;
                }
                if (!parseTextEntry) {
                    stats.truncated = true;
                    continue;
                }
                stats.txtEntryCount++;
                if (read.truncated() || read.bytes().length >= MAX_ENTRY_BYTES) {
                    stats.truncated = true;
                }
                processTextEntry(
                        entryName,
                        new String(read.bytes(), StandardCharsets.UTF_8),
                        sessionId,
                        stats,
                        stagedChunks);
                if (stats.recordCount >= MAX_RECORDS) {
                    stats.truncated = true;
                }
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_zip_archive");
        }
    }

    private void processTextEntry(
            String entryName,
            String body,
            String sessionId,
            Stats stats,
            List<StagedChunk> stagedChunks) {
        List<ConversationTopicTimelineBuilder.ClassifiedRecord> accepted = new ArrayList<>();
        for (ConversationMessageRecord record : parser.parse(entryName, body)) {
            if (stats.recordCount >= MAX_RECORDS) {
                stats.truncated = true;
                break;
            }
            stats.recordCount++;
            ConversationNoiseClassifier.Decision decision = classifier.classify(record);
            stats.counts.merge(decision.kind(), 1, Integer::sum);
            if (!decision.ingestible()) {
                continue;
            }
            accepted.add(new ConversationTopicTimelineBuilder.ClassifiedRecord(record, decision));
        }
        List<ConversationTopicTimelineBuilder.Chunk> chunks = chunkBuilder.build(sessionId, accepted, CHUNK_CHARS, CHUNK_MESSAGES);
        for (ConversationTopicTimelineBuilder.Chunk chunk : chunks) {
            if (chunk == null || chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(chunk.metadata()));
            stagedChunks.add(new StagedChunk(
                    chunk.id(), sessionId, chunk.text(), metadata));
        }
    }

    private static boolean isZip(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip")
                || type.equals("application/zip")
                || type.equals("application/x-zip-compressed")
                || type.equals("multipart/x-zip");
    }

    private static EntryRead readBounded(ZipInputStream zin, int maxBytes, Stats stats) throws IOException {
        int captureLimit = Math.max(0, maxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(captureLimit, 8192));
        byte[] buf = new byte[8192];
        int captured = 0;
        boolean truncated = false;
        int n;
        while ((n = zin.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            if ((long) n > MAX_TOTAL_DECOMPRESSED_BYTES - stats.decompressedBytes) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archive_total_too_large");
            }
            stats.decompressedBytes += n;
            int allowed = Math.min(n, Math.max(0, captureLimit - captured));
            if (allowed > 0) {
                out.write(buf, 0, allowed);
                captured += allowed;
            }
            if (allowed < n) {
                truncated = true;
            }
        }
        return new EntryRead(out.toByteArray(), truncated);
    }

    private static String safeEntryName(String value) {
        String raw = value == null ? "" : value.replace('\\', '/').trim();
        raw = raw.replaceAll("^/+", "");
        if (raw.isBlank()) {
            return "unnamed";
        }
        return raw.replaceAll("[^A-Za-z0-9._ /()-]+", "_");
    }

    private static Map<String, Integer> counts(EnumMap<ConversationMessageKind, Integer> source) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ConversationMessageKind kind : ConversationMessageKind.values()) {
            out.put(kind.wireName(), source.getOrDefault(kind, 0));
        }
        return out;
    }

    private static Map<String, Object> tracePayload(String sessionId, Stats stats, Map<String, Integer> counts, long tookMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceIdHash", hashIfPresent(TraceStore.get("traceId")));
        trace.put("requestIdHash", hashIfPresent(TraceStore.get("requestId")));
        trace.put("sessionIdHash", SafeRedactor.hashValue(sessionId));
        trace.put("zipCount", stats.zipCount);
        trace.put("entryCount", stats.entryCount);
        trace.put("txtEntryCount", stats.txtEntryCount);
        trace.put("humanMessageCount", counts.get(ConversationMessageKind.HUMAN_MESSAGE.wireName()));
        trace.put("botSummaryCount", counts.get(ConversationMessageKind.BOT_SUMMARY.wireName()));
        trace.put("linkArtifactCount", counts.get(ConversationMessageKind.LINK_ARTIFACT.wireName()));
        trace.put("quarantineCount", counts.get(ConversationMessageKind.QUARANTINED.wireName()));
        trace.put("ingestedCount", stats.ingestedCount);
        trace.put("skippedCount", counts.get(ConversationMessageKind.QUARANTINED.wireName()));
        trace.put("truncated", stats.truncated);
        trace.put("tookMs", tookMs);
        return trace;
    }

    private static void emitTrace(Map<String, Object> trace, Stats stats, Map<String, Integer> counts) {
        trace.forEach((k, v) -> TraceStore.put("conversation.archive." + k, v));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("queryHash", SafeRedactor.hash12(String.valueOf(trace.get("sessionIdHash"))));
        input.put("queryLen", 0);
        input.put("requestedTopK", stats.zipCount);
        input.put("planId", "conversation_archive_ingest");
        input.put("mode", "conversation_archive");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("returnedCount", stats.recordCount);
        output.put("afterFilterCount", stats.recordCount - counts.get(ConversationMessageKind.QUARANTINED.wireName()));
        output.put("selectedCount", stats.ingestedCount);
        output.put("promotedCount", stats.ingestedCount);
        output.put("stageMs", trace.get("tookMs"));
        output.put("sourceDiversity", stats.txtEntryCount);
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("action", "enqueue_clean_chunks");
        control.put("applied", true);
        control.put("reasonCode", "zip_only_conversation_archive");
        control.put("breadcrumbId", "conversation.archive.ingest");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("v", 1);
        ev.put("kind", "rag.ingest");
        ev.put("phase", "MLA-05");
        ev.put("stage", "ingest");
        ev.put("step", "complete");
        ev.put("component", "ConversationArchiveIngestService");
        ev.put("status", "ok");
        ev.put("sessionIdHash", trace.get("sessionIdHash"));
        ev.put("traceIdHash", trace.get("traceIdHash"));
        ev.put("requestIdHash", trace.get("requestIdHash"));
        ev.put("input", input);
        ev.put("output", output);
        ev.put("failure", Map.of());
        ev.put("control", control);
        OrchTrace.appendEvent(ev);
    }

    private void emitDebug(Map<String, Object> trace) {
        DebugEventStore store = debugEventStore == null ? null : debugEventStore.getIfAvailable();
        if (store == null) {
            return;
        }
        store.emit(DebugProbeType.ORCHESTRATION,
                DebugEventLevel.INFO,
                "conversation.archive.ingest",
                "conversation archive ingest summary",
                "ConversationArchiveIngestService.ingest",
                trace,
                null);
    }

    private static final class Stats {
        final EnumMap<ConversationMessageKind, Integer> counts = new EnumMap<>(ConversationMessageKind.class);
        final List<String> tree = new ArrayList<>();
        int zipCount;
        int entryCount;
        int txtEntryCount;
        int recordCount;
        int ingestedCount;
        long decompressedBytes;
        boolean truncated;
    }

    private record EntryRead(byte[] bytes, boolean truncated) {
    }

    private record StagedChunk(
            String id,
            String sessionId,
            String text,
            Map<String, Object> metadata) {
    }

    private static String hashIfPresent(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? null : SafeRedactor.hashValue(s);
    }
}
