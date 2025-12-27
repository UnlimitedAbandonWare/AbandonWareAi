package com.abandonwareai.nova.autolearn.ingest;

import com.abandonwareai.nova.autolearn.PreemptionToken;
import com.abandonwareai.nova.vector.EmbeddingDocument;
import com.abandonwareai.nova.vector.FederatedEmbeddingStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TrainRagIngestService {

    private static final Logger log = LoggerFactory.getLogger(TrainRagIngestService.class);

    private final FederatedEmbeddingStore embeddingStore;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int maxLinesPerRun;
    private final String statePathOverride;

    public TrainRagIngestService(FederatedEmbeddingStore embeddingStore,
                                @Value("${idle.ingest.maxLinesPerRun:200}") int maxLinesPerRun,
                                @Value("${idle.ingest.statePath:}") String statePathOverride) {
        this.embeddingStore = embeddingStore;
        this.maxLinesPerRun = maxLinesPerRun;
        this.statePathOverride = statePathOverride == null ? "" : statePathOverride.trim();
    }

    private static final class IngestState {
        public long offset;
        public String file;
        public String updatedAt;
    }

    public int ingestNewSamples(Path jsonlPath, String datasetName, PreemptionToken token) {
        if (jsonlPath == null || !Files.exists(jsonlPath)) {
            log.info("train jsonl not found: {}", jsonlPath);
            return 0;
        }

        Path statePath = resolveStatePath(jsonlPath, datasetName);
        IngestState state = loadState(statePath, jsonlPath);

        long lastProcessedOffset = state.offset;
        int acceptedDocs = 0;

        int maxLines = Math.max(1, maxLinesPerRun);
        int batchSize = Math.min(50, Math.max(5, maxLines / 4));

        try (RandomAccessFile raf = new RandomAccessFile(jsonlPath.toFile(), "r")) {
            long len = raf.length();
            if (state.offset > len) {
                state.offset = 0L;
                lastProcessedOffset = 0L;
            }
            raf.seek(state.offset);

            List<EmbeddingDocument> batch = new ArrayList<>();

            int processed = 0;
            while (processed < maxLines) {
                if (token != null && token.shouldAbort()) break;

                String line = readUtf8Line(raf);
                if (line == null) break;
                lastProcessedOffset = raf.getFilePointer();
                processed++;

                if (line.isBlank()) continue;

                Map<String, Object> m = parseJson(line);
                String answer = Objects.toString(m.getOrDefault("answer", ""), "");
                if (answer.isBlank()) continue;

                String question = Objects.toString(m.getOrDefault("question", ""), "");
                String content = question.isBlank() ? answer : question + "\n\n" + answer;

                String id = sha1((datasetName == null ? "" : datasetName) + "|" + question + "|" + answer);

                Map<String, String> meta = new HashMap<>();
                meta.put("source", Objects.toString(m.getOrDefault("source", "autolearn"), "autolearn"));
                meta.put("dataset", datasetName);
                meta.put("type", "qa");
                meta.put("uaw_id", id);
                meta.put("ts", Objects.toString(m.getOrDefault("ts", Instant.now().toString()), Instant.now().toString()));

                batch.add(new EmbeddingDocument(id, content, meta));

                if (batch.size() >= batchSize) {
                    if (token != null && token.shouldAbort()) break;
                    embeddingStore.upsert(batch);
                    acceptedDocs += batch.size();
                    batch.clear();
                    saveState(statePath, jsonlPath, lastProcessedOffset);
                }
            }

            if (!batch.isEmpty() && (token == null || !token.shouldAbort())) {
                embeddingStore.upsert(batch);
                acceptedDocs += batch.size();
                batch.clear();
                saveState(statePath, jsonlPath, lastProcessedOffset);
            }
        } catch (Exception e) {
            log.error("Failed reading {}", jsonlPath, e);
        }

        return acceptedDocs;
    }

    private Map<String, Object> parseJson(String line) {
        try {
            return mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Path resolveStatePath(Path jsonlPath, String datasetName) {
        if (!statePathOverride.isBlank()) {
            return Path.of(statePathOverride);
        }
        String dn = (datasetName == null || datasetName.isBlank()) ? "train" : datasetName;
        return jsonlPath.resolveSibling("ingest_state_" + dn + ".json");
    }

    private IngestState loadState(Path statePath, Path jsonlPath) {
        try {
            if (statePath == null || !Files.exists(statePath)) {
                IngestState s = new IngestState();
                s.offset = 0L;
                s.file = jsonlPath.toAbsolutePath().toString();
                s.updatedAt = Instant.now().toString();
                return s;
            }
            IngestState s = mapper.readValue(Files.readString(statePath, StandardCharsets.UTF_8), IngestState.class);
            String f = jsonlPath.toAbsolutePath().toString();
            if (s.file == null || !s.file.equals(f)) {
                IngestState r = new IngestState();
                r.offset = 0L;
                r.file = f;
                r.updatedAt = Instant.now().toString();
                return r;
            }
            return s;
        } catch (Exception e) {
            IngestState s = new IngestState();
            s.offset = 0L;
            s.file = jsonlPath.toAbsolutePath().toString();
            s.updatedAt = Instant.now().toString();
            return s;
        }
    }

    private void saveState(Path statePath, Path jsonlPath, long offset) {
        if (statePath == null) return;
        try {
            Files.createDirectories(statePath.getParent());
            IngestState s = new IngestState();
            s.offset = Math.max(0L, offset);
            s.file = jsonlPath.toAbsolutePath().toString();
            s.updatedAt = Instant.now().toString();

            Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(s), StandardCharsets.UTF_8);
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignore) {
        }
    }

    private static String readUtf8Line(RandomAccessFile raf) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            int b;
            boolean gotAny = false;
            while ((b = raf.read()) != -1) {
                gotAny = true;
                if (b == '\n') break;
                if (b != '\r') out.write(b);
            }
            if (!gotAny && out.size() == 0) return null;
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte v : b) sb.append(String.format("%02x", v));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
