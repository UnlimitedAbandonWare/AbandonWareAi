package com.abandonwareai.nova.autolearn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Component
public class DatasetWriter {
    private final Path outPath;
    private final double minContextDiversity;
    private final boolean allowRuleBreak;

    public DatasetWriter(@Value("${idle.dataset.path:build/idle/train_rag.jsonl}") String path,
                         @Value("${idle.dataset.minContextDiversity:0.35}") double minContextDiversity,
                         @Value("${idle.dataset.allowRuleBreak:false}") boolean allowRuleBreak) {
        this.outPath = Path.of(path);
        this.minContextDiversity = minContextDiversity;
        this.allowRuleBreak = allowRuleBreak;
    }

    public synchronized void appendRecord(String sessionId, String plan, String q, String a) {
        try {
            Files.createDirectories(outPath.getParent());
            String json = toJson(Map.of(
                    "ts", Instant.now().toString(),
                    "sessionId", sessionId,
                    "planId", plan,
                    "source", "idle_autolearn",
                    "question", q,
                    "answer", a));
            Files.writeString(outPath, json + System.lineSeparator(), StandardCharsets.UTF_8,
                    Files.exists(outPath) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write dataset record", e);
        }
    }

    private String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(""").append(escape(e.getKey())).append("":");
            Object v = e.getValue();
            sb.append(""").append(escape(v == null ? "" : v.toString())).append(""");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
    private String escape(String s) {
        return s.replace("\\", "\\\\").replace(""", "\\"").replace("\n", "\\n");
    }
}
