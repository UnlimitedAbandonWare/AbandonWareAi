package com.example.lms.cfvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CFVM-Raw minimal recorder - stores build/run error patterns into NDJSON.
 * Safe to include; it is not wired to any runtime and does not impact behavior.
 */
public final class CfvmRawRecorder {
    private CfvmRawRecorder() {}

    public static void appendPattern(Path projectRoot, String sourcePath, int line, String level, String message) {
        try {
            Path buildfix = projectRoot.resolve("buildfix");
            Files.createDirectories(buildfix);
            Path out = buildfix.resolve("error-patterns.ndjson");
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toString());
            rec.put("path", sourcePath);
            rec.put("line", line);
            rec.put("level", level);
            rec.put("message", message);
            String json = toJson(rec);
            Files.write(out, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // fail-soft
        }
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(String.valueOf(v));
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\", "\\").replace(""", "\"");
    }
}