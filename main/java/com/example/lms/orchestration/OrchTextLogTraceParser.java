package com.example.lms.orchestration;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parse an EOR-style plain text log into a TraceStore-like map.
 *
 * <p>
 * Expected pattern (most common in EOR dumps):
 * <pre>
 * orch.mode\tBYPASS
 * nightmare.breaker.openAtMs\t{...}
 * ...
 * </pre>
 *
 * <p>
 * This parser is intentionally conservative: it only extracts single-line key/value pairs.
 * Multi-line tables (e.g. orch.parts.table) are ignored.
 */
public final class OrchTextLogTraceParser {

    private OrchTextLogTraceParser() {
    }

    /**
     * Parse a text log file into a map of traceKey -&gt; raw string value.
     */
    public static Map<String, String> parse(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null) {
            return out;
        }

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    // Some dumps may use "key = value" but we avoid false positives here.
                    continue;
                }
                String key = line.substring(0, tab).trim();
                if (key.isEmpty() || key.indexOf(' ') >= 0) {
                    continue;
                }
                String value = (tab + 1 < line.length()) ? line.substring(tab + 1).trim() : "";

                // Heuristic: accept keys that look like trace keys.
                if (!looksLikeTraceKey(key)) {
                    continue;
                }
                out.put(key, value);
            }
        }

        // Helpful for report context
        out.putIfAbsent("__log.file", String.valueOf(file));
        return out;
    }

    private static boolean looksLikeTraceKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        // Most of our TraceStore keys are dot-delimited.
        if (key.indexOf('.') < 0) {
            return false;
        }
        // Avoid matching "Mode"/"Parts Build-up" etc.
        if (key.length() > 200) {
            return false;
        }
        // Common prefixes
        return key.startsWith("orch.")
                || key.startsWith("nightmare.")
                || key.startsWith("aux.")
                || key.startsWith("web.")
                || key.startsWith("plan.")
                || key.startsWith("irregularity.")
                || key.startsWith("gctx.")
                || key.startsWith("cm1_")
                || key.startsWith("trace.");
    }
}
