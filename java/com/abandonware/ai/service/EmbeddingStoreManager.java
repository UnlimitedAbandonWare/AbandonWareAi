package com.abandonware.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Service
public class EmbeddingStoreManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreManager.class);
    private final Set<String> idempotencySet = new HashSet<>();

    public void indexIncremental() {
        // Example impl: iterate new docs and insert with idempotency key
        String docId = "example";
        String version = "v1";
        String key = docId + "#" + version;
        if (!idempotencySet.add(key)) {
            log.debug("skip duplicate insert {}", key);
            return;
        }
        try {
            // /* ... */ perform indexing
        } catch (Exception e) {
            dlq("indexing_dlq", key + "\t" + e.getMessage());
            throw e;
        }
    }

    private void dlq(String name, String line) {
        try {
            Path dir = Path.of("build", "dlq");
            Files.createDirectories(dir);
            Path f = dir.resolve(name + ".log");
            String msg = Instant.now().toString() + "\t" + line + System.lineSeparator();
            Files.writeString(f, msg, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.warn("DLQ write failed", ex);
        }
    }
}