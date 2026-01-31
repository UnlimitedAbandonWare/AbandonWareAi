package com.example.lms.moe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ERRORLW_AFR_TR-style logs for failure signals and query samples.
 *
 * <p>Design goal: keep this small and robust (regex list + counters).
 */
@Component
public class RgbLogSignalParser {

    private static final Logger log = LoggerFactory.getLogger(RgbLogSignalParser.class);

    // signal keys
    public static final String SIG_MODEL_REQUIRED = "model_required";
    public static final String SIG_AFTER_RETRIES = "after_retries";
    public static final String SIG_REMOTE_429 = "remote_429";
    public static final String SIG_BREAKER_OPEN = "breaker_open";
    public static final String SIG_VECTOR_POISON_FILTERED = "vector_poison_filtered";
    public static final String SIG_PENDING_ACQUIRE_TIMEOUT = "pending_acquire_timeout";
    public static final String SIG_AUX_DOWN_HARD = "aux_down_hard";
    public static final String SIG_QT_OPEN = "qt_open";

    // additional coarse signals
    public static final String SIG_OOM = "oom";
    public static final String SIG_TIMEOUT = "timeout";
    public static final String SIG_CONN_RESET = "connection_reset";
    public static final String SIG_INVALID_KEY = "invalid_key";
    public static final String SIG_LOW_EVIDENCE = "low_evidence";

    private static final Pattern P_QUERY_EQ = Pattern.compile("\\bquery\\s*=\\s*([^\\n\\r]+)");
    private static final Pattern P_QUERY_JSON = Pattern.compile("\\\"query\\\"\\s*:\\s*\\\"([^\\\"]{1,280})\\\"");

    private static final List<SigRule> RULES = List.of(
            new SigRule(SIG_MODEL_REQUIRED, Pattern.compile("model\\s+is\\s+required", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_AFTER_RETRIES, Pattern.compile("after\\s+retries", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_REMOTE_429, Pattern.compile("\\b429\\b|RESOURCE_EXHAUSTED|rate\\s*limit", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_BREAKER_OPEN, Pattern.compile("NightmareBreaker\\s+OPEN|\\bOPEN\\b.*NightmareBreaker", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_VECTOR_POISON_FILTERED, Pattern.compile("vector\\.poison\\.filtered", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_PENDING_ACQUIRE_TIMEOUT, Pattern.compile("pendingAcquireTimeout", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_AUX_DOWN_HARD, Pattern.compile("aux_down_hard", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_QT_OPEN, Pattern.compile("qt_open", Pattern.CASE_INSENSITIVE)),

            new SigRule(SIG_OOM, Pattern.compile("out\\s+of\\s+memory|CUDA\\s+OOM|OOM", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_TIMEOUT, Pattern.compile("timeout|timed\\s*out", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_CONN_RESET, Pattern.compile("connection\\s+reset|ECONNRESET", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_INVALID_KEY, Pattern.compile("invalid[_\\s-]?api[_\\s-]?key|unauthorized", Pattern.CASE_INSENSITIVE)),
            new SigRule(SIG_LOW_EVIDENCE, Pattern.compile("low\\s+evidence|no\\s+evidence|evidence\\s+missing", Pattern.CASE_INSENSITIVE))
    );

    public Features parse(Path logPath, int tailLines, Duration lookback) {
        if (logPath == null) {
            return Features.empty();
        }
        if (!Files.exists(logPath)) {
            log.debug("[RGB] logPath not found: {}", logPath);
            return Features.empty();
        }

        List<String> lines = tailLines(logPath, Math.max(200, tailLines));

        // best-effort lookback filter when timestamps are absent: keep all lines.
        Instant cutoff = lookback == null ? null : Instant.now().minus(lookback);

        Map<String, Integer> counts = new HashMap<>();
        Set<String> queries = new LinkedHashSet<>();

        for (String line : lines) {
            if (line == null) continue;

            // optional: skip very old lines if timestamps were parseable (kept minimal)
            if (cutoff != null) {
                // no timestamp parsing
            }

            for (SigRule r : RULES) {
                if (r.pattern.matcher(line).find()) {
                    counts.merge(r.key, 1, Integer::sum);
                }
            }

            extractQueries(line, queries);
        }

        List<String> querySamples = new ArrayList<>(queries);
        if (querySamples.size() > 40) {
            querySamples = querySamples.subList(0, 40);
        }

        return new Features(counts, querySamples);
    }

    private static void extractQueries(String line, Set<String> out) {
        if (line == null || out == null) return;

        Matcher m1 = P_QUERY_EQ.matcher(line);
        if (m1.find()) {
            String raw = m1.group(1);
            String q = normalizeQuery(raw);
            if (q != null) out.add(q);
        }

        Matcher m2 = P_QUERY_JSON.matcher(line);
        if (m2.find()) {
            String raw = m2.group(1);
            String q = normalizeQuery(raw);
            if (q != null) out.add(q);
        }
    }

    private static String normalizeQuery(String s) {
        if (s == null) return null;
        String t = s.trim();
        int cut = t.indexOf(" | ");
        if (cut > 0) t = t.substring(0, cut);
        t = t.replaceAll("^\\\"|\\\"$", "").trim();
        if (t.length() > 280) t = t.substring(0, 280);
        if (t.isBlank()) return null;
        return t;
    }

    /**
     * Read last N lines efficiently (best-effort). Falls back to full read when needed.
     */
    private static List<String> tailLines(Path file, int maxLines) {
        try {
            long fileSize = Files.size(file);
            if (fileSize <= 0) return List.of();

            // read up to ~1MB from end, then split.
            int maxBytes = (int) Math.min(fileSize, 1_048_576L);
            byte[] buf = new byte[maxBytes];

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(fileSize - maxBytes);
                raf.readFully(buf);
            }

            String txt = new String(buf, StandardCharsets.UTF_8);
            String[] arr = txt.split("\\r?\\n");
            List<String> out = new ArrayList<>();
            for (int i = Math.max(0, arr.length - maxLines); i < arr.length; i++) {
                out.add(arr[i]);
            }
            return out;

        } catch (Exception e) {
            try {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                int from = Math.max(0, all.size() - maxLines);
                return all.subList(from, all.size());
            } catch (Exception ignore) {
                return List.of();
            }
        }
    }

    private record SigRule(String key, Pattern pattern) {}

    /**
     * Features: simple counters + sampled queries.
     */
    public record Features(Map<String, Integer> counts, List<String> querySamples) {

        public static Features empty() {
            return new Features(Map.of(), List.of());
        }

        public int count(String key) {
            if (key == null || counts == null) return 0;
            return counts.getOrDefault(key, 0);
        }

        public boolean has(String key) {
            return count(key) > 0;
        }
    }
}
