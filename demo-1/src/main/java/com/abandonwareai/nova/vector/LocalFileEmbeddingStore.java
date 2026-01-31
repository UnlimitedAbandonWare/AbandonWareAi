package com.abandonwareai.nova.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.abandonwareai.nova.config.IdleTrainProperties;

@Component
public class LocalFileEmbeddingStore implements FederatedEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFileEmbeddingStore.class);
    private final ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    private final IdleTrainProperties props;

    public LocalFileEmbeddingStore(IdleTrainProperties props) {
        this.props = props;
    }

    @Override
    public void upsert(List<EmbeddingDocument> docs) {
        if (docs == null || docs.isEmpty()) return;
        Path p = Path.of(props.getVectorIndexPath());
        try {
            Files.createDirectories(p.getParent());
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(Files.exists(p)? p.toAbsolutePath() : p), StandardCharsets.UTF_8))) {
                for (EmbeddingDocument d : docs) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", d.getId());
                    row.put("text", d.getText());
                    row.put("metadata", d.getMetadata());
                    w.write(mapper.writeValueAsString(row));
                    w.write("\n");
                }
            }
            log.info("LocalFileEmbeddingStore: upserted {} docs into {}", docs.size(), p.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write vector index to {}", p.toAbsolutePath(), e);
        }
    }
}