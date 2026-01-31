package com.example.lms.replay;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;




/**
 * Command line entry point for offline replay evaluation.  This runner
 * accepts a path to a JSON lines (JSONL) log file where each line is a
 * JSON object with the keys "query", "groundTruth", "rankedResults" and
 * optionally "latencyMs".  The keys are case sensitive.  If groundTruth
 * or latencyMs are absent they default to an empty list and zero,
 * respectively.  After parsing the log the runner computes aggregate
 * metrics via {@link OfflineReplayEvaluator} and prints a concise report
 * to stdout.  Optionally, minimum thresholds for NDCG and MRR may be
 * supplied via system properties (replay.ndcgMin, replay.mrrMin).  When
 * thresholds are provided and the computed metrics fall below them the
 * runner will exit with a non-zero status code to indicate failure.  This
 * behaviour enables integration into a CI/CD pipeline as a gating step.
 */
public final class OfflineReplayRunner {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: OfflineReplayRunner <replay.jsonl> [--quiet]");
            System.exit(1);
        }
        File logFile = new File(args[0]);
        if (!logFile.exists() || !logFile.isFile()) {
            System.err.println("Replay log does not exist: " + logFile);
            System.exit(2);
        }
        boolean quiet = args.length > 1 && "--quiet".equals(args[1]);
        List<ReplayRecord> records = parseReplay(logFile);
        EvaluationMetrics metrics = OfflineReplayEvaluator.evaluate(records);
        if (!quiet) {
            System.out.println("Offline replay evaluation completed");
            System.out.println(metrics);
        }
        double ndcgThreshold = getDoubleProperty("replay.ndcgMin", 0.0);
        double mrrThreshold = getDoubleProperty("replay.mrrMin", 0.0);
        if (metrics.getNdcgAt10() < ndcgThreshold) {
            System.err.printf("NDCG@10 %.4f fell below threshold %.4f%n", metrics.getNdcgAt10(), ndcgThreshold);
            System.exit(3);
        }
        if (metrics.getMrrAt10() < mrrThreshold) {
            System.err.printf("MRR@10 %.4f fell below threshold %.4f%n", metrics.getMrrAt10(), mrrThreshold);
            System.exit(4);
        }
    }

    private static double getDoubleProperty(String key, double defaultValue) {
        String val = System.getProperty(key);
        if (val == null) {
            // Avoid direct environment access; fall back to system properties only
            val = System.getProperty(key);
        }
        if (val != null) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException ignore) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private static List<ReplayRecord> parseReplay(File logFile) throws IOException {
        List<ReplayRecord> records = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    // Parse each line as a generic map via Jackson.  This avoids the
                    // overhead of creating a full POJO and tolerates unknown keys.
                    JsonFactory factory = mapper.getFactory();
                    try (JsonParser parser = factory.createParser(line)) {
                        String query = "";
                        List<String> truth = new ArrayList<>();
                        List<String> ranked = new ArrayList<>();
                        long latency = 0L;
                        if (parser.nextToken() != JsonToken.START_OBJECT) {
                            throw new IOException("Expected JSON object");
                        }
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String fieldName = parser.getCurrentName();
                            parser.nextToken();
                            switch (fieldName) {
                                case "query" -> query = parser.getValueAsString("");
                                case "groundTruth" -> truth = mapper.readValue(parser, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                                case "rankedResults" -> ranked = mapper.readValue(parser, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                                case "latencyMs" -> latency = parser.getValueAsLong(0L);
                                default -> parser.skipChildren();
                            }
                        }
                        records.add(new ReplayRecord(query, truth, ranked, latency));
                    }
                } catch (Exception ex) {
                    // When a line fails to parse we skip it but print a warning to aid debugging.
                    System.err.println("Failed to parse replay line: " + line);
                    ex.printStackTrace(System.err);
                }
            }
        }
        return records;
    }
}