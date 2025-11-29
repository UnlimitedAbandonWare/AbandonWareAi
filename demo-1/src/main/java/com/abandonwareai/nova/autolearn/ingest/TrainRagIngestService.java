package com.abandonwareai.nova.autolearn.ingest;

import com.abandonwareai.nova.vector.EmbeddingDocument;
import com.abandonwareai.nova.vector.FederatedEmbeddingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TrainRagIngestService {

    private static final Logger log = LoggerFactory.getLogger(TrainRagIngestService.class);
    private final FederatedEmbeddingStore embeddingStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrainRagIngestService(FederatedEmbeddingStore embeddingStore) {
        this.embeddingStore = embeddingStore;
    }

    private Map<String, Object> parseJson(String line) {
        try {
            return mapper.readValue(line, new TypeReference<Map<String, Object>>(){});
        } catch (Exception e) {
            log.warn("Ignore invalid JSONL line: {}", line);
            return Collections.emptyMap();
        }
    }

    private EmbeddingDocument toDocument(Map<String, Object> m, String datasetName) {
        String question = Objects.toString(m.getOrDefault("question", ""), "");
        String answer = Objects.toString(m.getOrDefault("answer", ""), "");
        if (answer.isBlank()) {
            throw new IllegalArgumentException("answer is required");
        }
        String content = question.isBlank()? answer : question + "\n\n" + answer;

        Map<String, String> meta = new HashMap<>();
        meta.put("source", Objects.toString(m.getOrDefault("source", "autolearn"), "autolearn"));
        meta.put("dataset", datasetName);
        meta.put("type", "qa");
        Object tags = m.get("tags");
        if (tags instanceof Collection<?>) {
            String joined = ((Collection<?>) tags).stream().map(Object::toString).collect(Collectors.joining(","));
            meta.put("tags", joined);
        }
        return new EmbeddingDocument(null, content, meta);
    }

    public int ingestNewSamples(Path jsonlPath, String datasetName) {
        if (jsonlPath == null || !Files.exists(jsonlPath)) {
            log.info("train jsonl not found: {}", jsonlPath);
            return 0;
        }
        try (Stream<String> lines = Files.lines(jsonlPath, StandardCharsets.UTF_8)) {
            List<EmbeddingDocument> docs = lines
                .filter(l -> l != null && !l.isBlank())
                .map(this::parseJson)
                .filter(m -> m.containsKey("answer") && !Objects.toString(m.get("answer"), "").isBlank())
                .map(m -> toDocument(m, datasetName))
                .collect(Collectors.toList());
            if (!docs.isEmpty()) {
                embeddingStore.upsert(docs);
                return docs.size();
            }
            return 0;
        } catch (IOException e) {
            log.error("Failed reading {}", jsonlPath, e);
            return 0;
        }
    }
}