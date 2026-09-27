package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class LocalLlmSmokeHistoryDiagnosticsService {

    private static final String REPORT_NAME = "local-llm-generation.json";
    private static final String HISTORY_NAME = "local-llm-generation.history.jsonl";
    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 50;
    private static final long FRESH_REPORT_MAX_AGE_MS = Duration.ofMinutes(30).toMillis();

    private final Path root;
    private final ObjectMapper objectMapper;

    public LocalLlmSmokeHistoryDiagnosticsService() {
        this(Path.of("").toAbsolutePath().normalize());
    }

    LocalLlmSmokeHistoryDiagnosticsService(Path root) {
        this.root = (root == null ? Path.of("") : root).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Object> snapshot(int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", Instant.now().toString());
        out.put("available", true);
        out.put("limit", limit);

        Optional<Path> reportPath = latestReportPath();
        out.put("reportFound", reportPath.isPresent());
        if (reportPath.isEmpty()) {
            out.put("historyFound", false);
            out.put("reason", "local_llm_smoke_report_missing");
            out.put("latest", Map.of());
            out.put("history", List.of());
            return out;
        }

        Path report = reportPath.get();
        Path history = report.getParent().resolve(HISTORY_NAME).normalize();
        putPathDiagnostics(out, "reportPath", report);
        putPathDiagnostics(out, "historyPath", history);
        long reportAgeMs = reportAgeMs(report);
        boolean reportStale = reportAgeMs > FRESH_REPORT_MAX_AGE_MS;
        out.put("reportAgeMs", reportAgeMs);
        out.put("reportStale", reportStale);
        out.put("evidenceMode", reportStale ? "supporting_stale" : "current");
        out.put("latest", readReport(report));
        out.put("historyFound", Files.isRegularFile(history));
        out.put("history", readHistory(history, limit));
        return out;
    }

    private Optional<Path> latestReportPath() {
        List<Path> candidates = new ArrayList<>();
        addKnownCandidate(candidates, root.resolve("build/desktop/reports/local-llm-smoke").resolve(REPORT_NAME));
        addKnownCandidate(candidates, root.resolve("verification/local-llm-smoke").resolve(REPORT_NAME));
        addReportCandidates(candidates, root.resolve("build/desktop/reports").normalize());
        Path buildRoot = root.resolve("build").normalize();
        if (Files.isDirectory(buildRoot)) {
            try (Stream<Path> stream = Files.list(buildRoot)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.resolve("reports").normalize())
                        .forEach(path -> addReportCandidates(candidates, path));
            } catch (IOException ex) {
                traceSuppressed("build_dir_scan", ex);
                // Missing or unreadable host-split build directories are reflected as reportFound=false.
            }
        }
        return candidates.stream()
                .filter(Files::isRegularFile)
                .max(Comparator.comparing(this::lastModifiedOrEpoch));
    }

    private void addReportCandidates(List<Path> candidates, Path reportsRoot) {
        if (Files.isDirectory(reportsRoot)) {
            try (Stream<Path> stream = Files.list(reportsRoot)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName() != null
                                && path.getFileName().toString().startsWith("local-llm-smoke"))
                        .map(path -> path.resolve(REPORT_NAME))
                        .forEach(path -> addKnownCandidate(candidates, path));
            } catch (IOException ex) {
                traceSuppressed("report_candidates_scan", ex);
                // Missing or unreadable smoke report directories are reflected as reportFound=false.
            }
        }
    }

    private static void addKnownCandidate(List<Path> candidates, Path path) {
        if (path != null) {
            candidates.add(path.normalize());
        }
    }

    private FileTime lastModifiedOrEpoch(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ex) {
            traceSuppressed("report_last_modified", ex);
            return FileTime.fromMillis(0L);
        }
    }

    private long reportAgeMs(Path path) {
        FileTime modified = lastModifiedOrEpoch(path);
        long age = Instant.now().toEpochMilli() - modified.toMillis();
        return Math.max(0L, age);
    }

    private Map<String, Object> readReport(Path report) {
        try {
            JsonNode rootNode = objectMapper.readTree(stripUtf8Bom(Files.readString(report, StandardCharsets.UTF_8)));
            return sanitizeReport(rootNode);
        } catch (IOException ex) {
            traceSuppressed("report_read", ex);
            return Map.of("available", false, "reason", "local_llm_smoke_report_unreadable");
        }
    }

    private List<Map<String, Object>> readHistory(Path history, int limit) {
        if (!Files.isRegularFile(history)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(history, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            traceSuppressed("history_read", ex);
            return List.of();
        }
        int from = Math.max(0, lines.size() - limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : lines.subList(from, lines.size())) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                out.add(sanitizeHistoryRow(objectMapper.readTree(stripUtf8Bom(line))));
            } catch (JsonProcessingException ex) {
                traceSuppressed("history_row_parse", ex);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("parseSkipped", true);
                row.put("reason", "invalid_jsonl_row");
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    private Map<String, Object> sanitizeReport(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        putBool(out, "ok", node, "ok");
        putLabel(out, "endpointHost", node, "endpointHost");
        putLabel(out, "modelHash", node, "modelHash");
        putInt(out, "modelLength", node, "modelLength");
        putLabel(out, "promptHash", node, "promptHash");
        putInt(out, "promptLength", node, "promptLength");
        putLabel(out, "recommendedRoute", node, "recommendedRoute");
        putBool(out, "debugTrigger", node, "debugTrigger");
        putInt(out, "negativeSignalCount", node, "negativeSignalCount");
        putInt(out, "secretPatternHits", node, "secretPatternHits");
        out.put("attemptScores", sanitizeAttemptScores(node.path("attemptScores")));
        out.put("cumulativeSignals", sanitizeCumulativeSignals(node.path("cumulativeSignals")));
        out.put("operatorAction", deriveOperatorAction(out));
        return out;
    }

    private Map<String, Object> sanitizeHistoryRow(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        putLabel(out, "modelHash", node, "modelHash");
        putInt(out, "modelLength", node, "modelLength");
        putLabel(out, "promptHash", node, "promptHash");
        putInt(out, "promptLength", node, "promptLength");
        putInt(out, "openAiScore", node, "openAiScore");
        putLabel(out, "openAiVerdict", node, "openAiVerdict");
        putInt(out, "nativeScore", node, "nativeScore");
        putLabel(out, "nativeVerdict", node, "nativeVerdict");
        putLabel(out, "recommendedRoute", node, "recommendedRoute");
        putBool(out, "debugTrigger", node, "debugTrigger");
        putInt(out, "negativeSignalCount", node, "negativeSignalCount");
        putInt(out, "secretPatternHits", node, "secretPatternHits");
        return out;
    }

    private Map<String, Object> sanitizeAttemptScores(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openAiCompatible", sanitizeAttemptScore(node.path("openAiCompatible")));
        out.put("nativeOllama", sanitizeAttemptScore(node.path("nativeOllama")));
        return out;
    }

    private Map<String, Object> sanitizeAttemptScore(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        putLabel(out, "route", node, "route");
        putInt(out, "score", node, "score");
        putLabel(out, "verdict", node, "verdict");
        putBool(out, "negativeSignal", node, "negativeSignal");
        putInt(out, "negativeSignalCount", node, "negativeSignalCount");
        putInt(out, "status", node, "status");
        putLong(out, "elapsedMs", node, "elapsedMs");
        putInt(out, "contentLength", node, "contentLength");
        putInt(out, "thinkingLength", node, "thinkingLength");
        return out;
    }

    private Map<String, Object> sanitizeCumulativeSignals(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        putInt(out, "sampleCount", node, "sampleCount");
        putInt(out, "window", node, "window");
        putInt(out, "negativeSignalCount", node, "negativeSignalCount");
        putInt(out, "debugTriggerCount", node, "debugTriggerCount");
        putDouble(out, "negativeSignalPressure", node, "negativeSignalPressure");
        putDouble(out, "averageOpenAiScore", node, "averageOpenAiScore");
        putDouble(out, "averageNativeScore", node, "averageNativeScore");
        putBool(out, "thresholdExceeded", node, "thresholdExceeded");
        return out;
    }

    private Map<String, Object> deriveOperatorAction(Map<String, Object> report) {
        Map<String, Object> attempts = mapValue(report.get("attemptScores"));
        Map<String, Object> openAi = mapValue(attempts.get("openAiCompatible"));
        Map<String, Object> nativeOllama = mapValue(attempts.get("nativeOllama"));
        Map<String, Object> cumulative = mapValue(report.get("cumulativeSignals"));
        int openAiScore = intValue(openAi.get("score"));
        int nativeScore = intValue(nativeOllama.get("score"));
        int openAiStatus = intValue(openAi.get("status"));
        int nativeStatus = intValue(nativeOllama.get("status"));
        int negativeSignals = Math.max(intValue(report.get("negativeSignalCount")), intValue(cumulative.get("negativeSignalCount")));
        int scoreDelta = Math.max(0, nativeScore - openAiScore);
        boolean thresholdExceeded = Boolean.TRUE.equals(cumulative.get("thresholdExceeded"));
        boolean debugTrigger = Boolean.TRUE.equals(report.get("debugTrigger"));
        String openAiVerdict = String.valueOf(openAi.getOrDefault("verdict", ""));
        String recommendedRoute = String.valueOf(report.getOrDefault("recommendedRoute", ""));

        boolean modelBlank = "blank_response".equals(openAiVerdict) || Boolean.TRUE.equals(openAi.get("negativeSignal"));
        boolean preferNative = "native_ollama".equals(recommendedRoute) && nativeScore > openAiScore;
        boolean triggered = thresholdExceeded || debugTrigger || (modelBlank && preferNative);
        int actionScore = clampScore(scoreDelta + (negativeSignals * 5) + (thresholdExceeded ? 10 : 0));
        int upstreamStatus = preferNative && nativeStatus > 0 ? nativeStatus : openAiStatus;
        String upstreamFailureClass = upstreamFailureClass(upstreamStatus, false);
        String nextAction = preferNative ? "prefer_native_ollama_route" : "monitor_local_llm_route";
        if (!"none".equals(upstreamFailureClass)) {
            nextAction = upstreamNextAction(upstreamStatus);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggered", triggered);
        out.put("triggerReason", thresholdExceeded ? "threshold_exceeded" : (debugTrigger ? "debug_trigger" : "none"));
        out.put("failureClass", modelBlank ? "model_blank" : "none");
        out.put("nextAction", nextAction);
        out.put("actionScore", actionScore);
        out.put("scoreDelta", scoreDelta);
        out.put("negativeSignalCount", negativeSignals);
        if (upstreamStatus > 0) {
            out.put("upstreamStatus", upstreamStatus);
        }
        if (!"none".equals(upstreamFailureClass)) {
            out.put("upstreamFailureClass", upstreamFailureClass);
            out.put("upstreamNextAction", upstreamNextAction(upstreamStatus));
        }
        return out;
    }

    private static int clampLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(MAX_LIMIT, requestedLimit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String upstreamFailureClass(int status, boolean modelBlank) {
        if (status >= 500) {
            return "ollama_upstream_5xx";
        }
        if (status == 429) {
            return "ollama_rate_limit";
        }
        if (status >= 400) {
            return "ollama_upstream_4xx";
        }
        return modelBlank ? "model_blank" : "none";
    }

    private static String upstreamNextAction(int status) {
        if (status >= 500) {
            return "inspect_ollama_runtime_capacity";
        }
        if (status == 429) {
            return "respect_ollama_retry_after";
        }
        if (status >= 400) {
            return "inspect_ollama_request_contract";
        }
        return "monitor_local_llm_route";
    }

    private static void traceSuppressed(String stage, Exception failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("localLlm.smokeHistory.suppressed." + safeStage, true);
        TraceStore.put("localLlm.smokeHistory.suppressed." + safeStage + ".errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    private static String stripUtf8Bom(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) != '\uFEFF') {
            return value;
        }
        return value.substring(1);
    }

    private static void putPathDiagnostics(Map<String, Object> out, String prefix, Path path) {
        String value = path == null ? "" : path.normalize().toString();
        out.put(prefix + "Hash", SafeRedactor.hashValue(value));
        out.put(prefix + "Length", value.length());
    }

    private static void putLabel(Map<String, Object> out, String outKey, JsonNode node, String nodeKey) {
        JsonNode value = node.path(nodeKey);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        out.put(outKey, SafeRedactor.traceLabelOrFallback(value.asText(), ""));
    }

    private static void putBool(Map<String, Object> out, String outKey, JsonNode node, String nodeKey) {
        JsonNode value = node.path(nodeKey);
        if (!value.isMissingNode() && !value.isNull()) {
            out.put(outKey, value.asBoolean(false));
        }
    }

    private static void putInt(Map<String, Object> out, String outKey, JsonNode node, String nodeKey) {
        JsonNode value = node.path(nodeKey);
        if (!value.isMissingNode() && value.canConvertToInt()) {
            out.put(outKey, Math.max(0, value.asInt()));
        }
    }

    private static void putLong(Map<String, Object> out, String outKey, JsonNode node, String nodeKey) {
        JsonNode value = node.path(nodeKey);
        if (!value.isMissingNode() && value.canConvertToLong()) {
            out.put(outKey, Math.max(0L, value.asLong()));
        }
    }

    private static void putDouble(Map<String, Object> out, String outKey, JsonNode node, String nodeKey) {
        JsonNode value = node.path(nodeKey);
        if (!value.isMissingNode() && value.isNumber() && Double.isFinite(value.asDouble())) {
            out.put(outKey, Math.max(0.0d, value.asDouble()));
        }
    }
}
