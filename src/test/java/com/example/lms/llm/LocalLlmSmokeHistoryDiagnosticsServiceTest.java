package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLlmSmokeHistoryDiagnosticsServiceTest {

    @TempDir
    Path temp;

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceAfter() {
        TraceStore.clear();
    }

    @Test
    void snapshotReadsLatestSmokeHistoryWithoutRawPromptModelBodyOrPaths() throws Exception {
        Path dir = temp.resolve("build/desktop/reports/local-llm-smoke-goal-continuation");
        Files.createDirectories(dir);
        Path report = dir.resolve("local-llm-generation.json");
        Files.writeString(report, """
                {
                  "ok": true,
                  "endpointHost": "127.0.0.1:11434",
                  "modelHash": "hash:model",
                  "modelLength": 8,
                  "promptHash": "hash:prompt",
                  "promptLength": 22,
                  "recommendedRoute": "native_ollama",
                  "debugTrigger": true,
                  "negativeSignalCount": 1,
                  "secretPatternHits": 0,
                  "attemptScores": {
                    "openAiCompatible": {
                      "route": "openai_compatible",
                      "score": 15,
                      "verdict": "blank_response",
                      "negativeSignal": true,
                      "negativeSignalCount": 1,
                      "status": 200,
                      "elapsedMs": 1018,
                      "contentLength": 0,
                      "thinkingLength": 0,
                      "rawPrompt": "Authorization=private-token should not surface"
                    },
                    "nativeOllama": {
                      "route": "native_ollama",
                      "score": 100,
                      "verdict": "usable",
                      "negativeSignal": false,
                      "negativeSignalCount": 0,
                      "status": 200,
                      "elapsedMs": 1410,
                      "contentLength": 6,
                      "thinkingLength": 0
                    }
                  },
                  "cumulativeSignals": {
                    "sampleCount": 1,
                    "negativeSignalPressure": 1,
                    "thresholdExceeded": true,
                    "averageOpenAiScore": 15,
                    "averageNativeScore": 100
                  },
                  "rawModel": "qwen3:8b",
                  "rawBody": "choices raw body should not surface"
                }
                """, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(report, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        Files.writeString(dir.resolve("local-llm-generation.history.jsonl"),
                """
                {"modelHash":"hash:model","promptHash":"hash:prompt","openAiScore":15,"openAiVerdict":"blank_response","nativeScore":100,"nativeVerdict":"usable","recommendedRoute":"native_ollama","debugTrigger":true,"negativeSignalCount":1,"secretPatternHits":0,"rawModel":"qwen3:8b","rawPrompt":"AWX_OK Authorization=private-token"}
                """, StandardCharsets.UTF_8);

        Map<String, Object> snapshot = new LocalLlmSmokeHistoryDiagnosticsService(temp).snapshot(5);

        assertEquals(true, snapshot.get("available"));
        assertEquals(true, snapshot.get("reportFound"));
        assertEquals(true, snapshot.get("historyFound"));
        assertTrue(String.valueOf(snapshot.get("reportPathHash")).startsWith("hash:"));
        assertFalse(String.valueOf(snapshot).contains(temp.toString()), String.valueOf(snapshot));
        assertEquals(false, snapshot.get("reportStale"));
        assertTrue(((Number) snapshot.get("reportAgeMs")).longValue() >= 0L);

        Map<?, ?> latest = map(snapshot.get("latest"));
        assertEquals("native_ollama", latest.get("recommendedRoute"));
        assertEquals(true, latest.get("debugTrigger"));
        assertEquals(1, latest.get("negativeSignalCount"));
        assertEquals(0, latest.get("secretPatternHits"));
        Map<?, ?> attempts = map(latest.get("attemptScores"));
        assertEquals(15, map(attempts.get("openAiCompatible")).get("score"));
        assertEquals("blank_response", map(attempts.get("openAiCompatible")).get("verdict"));
        assertEquals(100, map(attempts.get("nativeOllama")).get("score"));

        Map<?, ?> cumulative = map(latest.get("cumulativeSignals"));
        assertEquals(1, cumulative.get("sampleCount"));
        assertEquals(true, cumulative.get("thresholdExceeded"));
        Map<?, ?> operatorAction = map(latest.get("operatorAction"));
        assertEquals(true, operatorAction.get("triggered"));
        assertEquals("threshold_exceeded", operatorAction.get("triggerReason"));
        assertEquals("model_blank", operatorAction.get("failureClass"));
        assertEquals("prefer_native_ollama_route", operatorAction.get("nextAction"));
        assertEquals(85, operatorAction.get("scoreDelta"));
        assertEquals(100, operatorAction.get("actionScore"));

        List<?> history = list(snapshot.get("history"));
        assertEquals(1, history.size());
        assertEquals("native_ollama", map(history.get(0)).get("recommendedRoute"));

        String dump = String.valueOf(snapshot);
        assertFalse(dump.contains("qwen3:8b"), dump);
        assertFalse(dump.contains("AWX_OK"), dump);
        assertFalse(dump.contains("Authorization"), dump);
        assertFalse(dump.contains("private-token"), dump);
        assertFalse(dump.contains("choices raw"), dump);
    }

    @Test
    void snapshotReadsPowerShellUtf8BomReportAndHistoryRows() throws Exception {
        Path dir = temp.resolve("build/desktop/reports/local-llm-smoke-goal-ui");
        Files.createDirectories(dir);
        Path report = dir.resolve("local-llm-generation.json");
        Files.writeString(report,
                "\uFEFF{\"recommendedRoute\":\"native_ollama\",\"debugTrigger\":true,"
                        + "\"attemptScores\":{\"openAiCompatible\":{\"score\":15,\"verdict\":\"blank_response\"},"
                        + "\"nativeOllama\":{\"score\":100,\"verdict\":\"usable\"}},"
                        + "\"cumulativeSignals\":{\"sampleCount\":1,\"thresholdExceeded\":true}}",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(report, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        Files.writeString(dir.resolve("local-llm-generation.history.jsonl"),
                "\uFEFF{\"recommendedRoute\":\"native_ollama\",\"openAiScore\":15,"
                        + "\"openAiVerdict\":\"blank_response\",\"nativeScore\":100,"
                        + "\"nativeVerdict\":\"usable\",\"debugTrigger\":true}\n",
                StandardCharsets.UTF_8);

        Map<String, Object> snapshot = new LocalLlmSmokeHistoryDiagnosticsService(temp).snapshot(5);

        Map<?, ?> latest = map(snapshot.get("latest"));
        assertEquals("native_ollama", latest.get("recommendedRoute"));
        assertEquals(15, map(map(latest.get("attemptScores")).get("openAiCompatible")).get("score"));
        assertEquals(true, map(latest.get("cumulativeSignals")).get("thresholdExceeded"));
        List<?> history = list(snapshot.get("history"));
        assertEquals("native_ollama", map(history.get(0)).get("recommendedRoute"));
        assertFalse(String.valueOf(snapshot).contains("parseSkipped"), String.valueOf(snapshot));
    }

    @Test
    void snapshotRecordsRedactedBreadcrumbForMalformedHistoryRows() throws Exception {
        Path dir = temp.resolve("build/desktop/reports/local-llm-smoke-malformed-history");
        Files.createDirectories(dir);
        Path report = dir.resolve("local-llm-generation.json");
        Files.writeString(report,
                "{\"recommendedRoute\":\"native_ollama\"}",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(report, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        Files.writeString(dir.resolve("local-llm-generation.history.jsonl"),
                "{\"recommendedRoute\":\"native_ollama\"\n",
                StandardCharsets.UTF_8);

        Map<String, Object> snapshot = new LocalLlmSmokeHistoryDiagnosticsService(temp).snapshot(5);

        assertEquals(true, snapshot.get("reportFound"));
        List<?> history = list(snapshot.get("history"));
        assertEquals(1, history.size());
        assertEquals(true, map(history.get(0)).get("parseSkipped"));
        assertEquals(Boolean.TRUE, TraceStore.get("localLlm.smokeHistory.suppressed.history_row_parse"));
        assertEquals("JsonEOFException",
                TraceStore.get("localLlm.smokeHistory.suppressed.history_row_parse.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(temp.toString()));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("native_ollama"));
    }

    @Test
    void snapshotFindsHostSplitGradleSmokeReports() throws Exception {
        Path dir = temp.resolve("build/desktop-sse-e2e/reports/local-llm-smoke-sse-e2e");
        Files.createDirectories(dir);
        Path report = dir.resolve("local-llm-generation.json");
        Files.writeString(report, """
                {
                  "recommendedRoute": "native_ollama",
                  "debugTrigger": true,
                  "negativeSignalCount": 1,
                  "attemptScores": {
                    "openAiCompatible": {"score": 15, "verdict": "blank_response", "negativeSignal": true},
                    "nativeOllama": {"score": 100, "verdict": "usable"}
                  },
                  "cumulativeSignals": {
                    "sampleCount": 1,
                    "negativeSignalCount": 1,
                    "thresholdExceeded": true
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(report, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));

        Map<String, Object> snapshot = new LocalLlmSmokeHistoryDiagnosticsService(temp).snapshot(5);

        assertEquals(true, snapshot.get("reportFound"));
        Map<?, ?> latest = map(snapshot.get("latest"));
        assertEquals("native_ollama", latest.get("recommendedRoute"));
        Map<?, ?> operatorAction = map(latest.get("operatorAction"));
        assertEquals(true, operatorAction.get("triggered"));
        assertEquals("threshold_exceeded", operatorAction.get("triggerReason"));
        assertEquals("prefer_native_ollama_route", operatorAction.get("nextAction"));
    }

    @Test
    void snapshotMarksOldSmokeReportAsSupportingStaleEvidence() throws Exception {
        Path dir = temp.resolve("verification/local-llm-smoke");
        Files.createDirectories(dir);
        Path report = dir.resolve("local-llm-generation.json");
        Files.writeString(report, """
                {
                  "recommendedRoute": "native_ollama",
                  "debugTrigger": true,
                  "negativeSignalCount": 1,
                  "attemptScores": {
                    "openAiCompatible": {"score": 15, "verdict": "blank_response", "negativeSignal": true},
                    "nativeOllama": {"score": 100, "verdict": "usable"}
                  },
                  "cumulativeSignals": {"sampleCount": 1, "thresholdExceeded": true}
                }
                """, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(report,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2))));

        Map<String, Object> snapshot = new LocalLlmSmokeHistoryDiagnosticsService(temp).snapshot(1);

        assertEquals(true, snapshot.get("reportFound"));
        assertEquals(true, snapshot.get("reportStale"));
        assertEquals("supporting_stale", snapshot.get("evidenceMode"));
        assertTrue(((Number) snapshot.get("reportAgeMs")).longValue()
                >= java.time.Duration.ofHours(1).toMillis());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
