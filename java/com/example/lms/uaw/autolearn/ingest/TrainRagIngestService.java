package com.example.lms.uaw.autolearn.ingest;

import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.uaw.autolearn.PreemptionToken;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Incremental ingest of train_rag.jsonl with a persistent checkpoint.
 */

// MERGE_HOOK:PROJ_AGENT::UAW_TRAIN_INGEST_IDS_SID_V1
@Service
public class TrainRagIngestService {

    private static final Logger log = LoggerFactory.getLogger(TrainRagIngestService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final UawAutolearnProperties props;
    private final ObjectMapper om = new ObjectMapper();

    private record Indexed(String id, TextSegment segment) {
    }

    public TrainRagIngestService(EmbeddingStore<TextSegment> embeddingStore,
                                EmbeddingModel embeddingModel,
                                UawAutolearnProperties props) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    private static final class IngestState {
        public long offset;
        public String file;
        public String updatedAt;

        public static IngestState empty(Path file) {
            IngestState s = new IngestState();
            s.offset = 0L;
            s.file = (file == null) ? null : file.toAbsolutePath().toString();
            s.updatedAt = Instant.now().toString();
            return s;
        }
    }

    public int ingestNewSamples(Path jsonlPath, String datasetName, PreemptionToken token) {
        if (jsonlPath == null || !Files.exists(jsonlPath)) {
            log.debug("[UAW] train jsonl not found: {}", jsonlPath);
            return 0;
        }
        if (token != null && token.shouldAbort()) {
            return 0;
        }

        Path statePath = Path.of(props.getRetrain().getIngestStatePath());
        IngestState state = loadState(statePath, jsonlPath);

        int maxLines = Math.max(1, props.getRetrain().getMaxIngestLinesPerRun());
        int batchSize = Math.min(50, Math.max(5, maxLines / 4));

        long lastProcessedOffset = state.offset;
        int acceptedDocs = 0;

        try (RandomAccessFile raf = new RandomAccessFile(jsonlPath.toFile(), "r")) {
            long len = raf.length();
            if (state.offset > len) {
                // file truncated/rotated
                state.offset = 0L;
                lastProcessedOffset = 0L;
            }
            raf.seek(state.offset);

            List<Indexed> batch = new ArrayList<>();

            int processedLines = 0;
            while (processedLines < maxLines) {
                if (token != null && token.shouldAbort()) {
                    break;
                }

                String line = readUtf8Line(raf);
                if (line == null) {
                    break;
                }
                lastProcessedOffset = raf.getFilePointer();
                processedLines++;

                if (line.isBlank()) {
                    continue;
                }

                Map<String, Object> m = parseJson(line);
                String answer = Objects.toString(m.getOrDefault("answer", ""), "");
                if (answer.isBlank()) {
                    continue;
                }

                String question = Objects.toString(m.getOrDefault("question", ""), "");
                String content = question.isBlank() ? answer : (question + "\n\n" + answer);

                String stableId = sha1((datasetName == null ? "" : datasetName) + "|" + question + "|" + answer);
                String vectorId = "uaw:" + stableId;

                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "qa");
                meta.put("dataset", datasetName);
                meta.put("source", Objects.toString(m.getOrDefault("source", "uaw_autolearn"), "uaw_autolearn"));
                meta.put("uaw_id", stableId);
                meta.put("ts", Objects.toString(m.getOrDefault("ts", Instant.now().toString()), Instant.now().toString()));

                // Ensure the chunk is searchable by the default sid filter.
                meta.put(LangChainRAGService.META_SID, LangChainRAGService.GLOBAL_SID);

                Object sessionId = m.get("sessionId");
                if (sessionId != null) {
                    meta.put("sessionId", sessionId.toString());
                }

                batch.add(new Indexed(vectorId, TextSegment.from(content, Metadata.from(meta))));

                if (batch.size() >= batchSize) {
                    if (token != null && token.shouldAbort()) {
                        break;
                    }
                    boolean ok = upsertSegments(batch);
                    if (!ok) {
                        break;
                    }
                    acceptedDocs += batch.size();
                    batch.clear();
                    saveState(statePath, jsonlPath, lastProcessedOffset);
                }
            }

            if (!batch.isEmpty() && (token == null || !token.shouldAbort())) {
                boolean ok = upsertSegments(batch);
                if (ok) {
                    acceptedDocs += batch.size();
                    saveState(statePath, jsonlPath, lastProcessedOffset);
                }
            }
        } catch (Exception e) {
            log.warn("[UAW] ingest error: {}", e.toString());
        }

        return acceptedDocs;
    }

    private boolean upsertSegments(List<Indexed> batch) {
        try {
            if (batch == null || batch.isEmpty()) {
                return true;
            }

            List<String> ids = batch.stream().map(Indexed::id).toList();
            List<TextSegment> segments = batch.stream().map(Indexed::segment).toList();

            var embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);

            return true;
        } catch (Exception e) {
            log.warn("[UAW] vector upsert failed: {}", e.toString());
            return false;
        }
    }

    private Map<String, Object> parseJson(String line) {
        try {
            return om.readValue(line, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private IngestState loadState(Path statePath, Path jsonlPath) {
        try {
            if (statePath == null || !Files.exists(statePath)) {
                return IngestState.empty(jsonlPath);
            }
            IngestState s = om.readValue(Files.readString(statePath, StandardCharsets.UTF_8), IngestState.class);
            if (s.file == null || jsonlPath == null || !s.file.equals(jsonlPath.toAbsolutePath().toString())) {
                // different file -> reset
                return IngestState.empty(jsonlPath);
            }
            return s;
        } catch (Exception e) {
            return IngestState.empty(jsonlPath);
        }
    }

    private void saveState(Path statePath, Path jsonlPath, long offset) {
        if (statePath == null) return;
        try {
            Files.createDirectories(statePath.getParent());
            IngestState s = new IngestState();
            s.offset = Math.max(0L, offset);
            s.file = jsonlPath == null ? null : jsonlPath.toAbsolutePath().toString();
            s.updatedAt = Instant.now().toString();

            Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            Files.writeString(tmp, om.writeValueAsString(s), StandardCharsets.UTF_8);
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
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    out.write(b);
                }
            }
            if (!gotAny && out.size() == 0) {
                return null;
            }
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
