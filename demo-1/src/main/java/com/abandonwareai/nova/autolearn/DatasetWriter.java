package com.abandonwareai.nova.autolearn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Component
public class DatasetWriter {
    private final Path outPath;
    private final double minContextDiversity;
    private final boolean allowRuleBreak;

    private final ObjectMapper om = new ObjectMapper();

    public DatasetWriter(@Value("${idle.dataset.path:${nova.idle-train.trainRagJsonlPath:build/idle/train_rag.jsonl}}") String path,
                         @Value("${idle.dataset.minContextDiversity:0.35}") double minContextDiversity,
                         @Value("${idle.dataset.allowRuleBreak:false}") boolean allowRuleBreak) {
        this.outPath = Path.of(path);
        this.minContextDiversity = minContextDiversity;
        this.allowRuleBreak = allowRuleBreak;
    }

    public synchronized boolean appendRecord(String sessionId, String plan, String q, String a) {
        if (a == null || a.isBlank()) return false;
        try {
            Files.createDirectories(outPath.getParent());

            ObjectNode n = om.createObjectNode();
            n.put("ts", Instant.now().toString());
            n.put("sessionId", sessionId == null ? "" : sessionId);
            n.put("planId", plan == null ? "" : plan);
            n.put("source", "idle_autolearn");
            n.put("question", q == null ? "" : q);
            n.put("answer", a);

            Files.writeString(
                    outPath,
                    om.writeValueAsString(n) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(outPath) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE
            );
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write dataset record", e);
        }
    }

    public double getMinContextDiversity() {
        return minContextDiversity;
    }

    public boolean isAllowRuleBreak() {
        return allowRuleBreak;
    }
}
