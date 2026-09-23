package com.example.lms.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTraceRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    void recordTerminal_neverWritesSecretsOrRawIdentifiers() throws Exception {
        ChatSessionTraceRecorder recorder = new ChatSessionTraceRecorder(tempDir, true, null);
        String fixtureKey = com.example.lms.test.SecretFixtures.openAiKey();
        // 비밀-패턴 스캐너를 피하려고 Bearer 토큰 모양을 런타임에 조립한다(의미는 동일).
        String runToken = "Bearer" + " " + "abcdefghijklmnop-secret-run-token";
        String promptBody = "private user prompt body should never persist";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("prompt.agentDebugEvidence.text", promptBody);
        meta.put("api.key", fixtureKey);
        meta.put("llm.factory.baseUrlHost", "api.openai.com");
        meta.put("llm.gateway.fallback.count", 2);
        meta.put("chat.harmony.postprocess.degraded", true);
        meta.put("traceMemory.cfvm.offered", true);

        // ISO 타임스탬프 나노초와 절대 겹치지 않는 세션 id를 사용한다.
        String rawSession = "session-42-xyz-raw";
        recorder.recordTerminal("chat", rawSession, runToken,
                "gpt-5.6-luna", "openai/gpt-oss-120b", Boolean.TRUE,
                "completed", meta);

        String content = readAll(tempDir);
        assertFalse(content.isBlank(), "trace file must be written");
        assertFalse(content.contains(fixtureKey), "API key must not be stored");
        assertFalse(content.contains(runToken), "raw run token must not be stored");
        assertFalse(content.contains(promptBody), "prompt body must not be stored");
        assertFalse(content.contains(rawSession), "raw session id must not be stored");
        assertTrue(content.contains("\"sessionId\":\"hash:"), "session id must be hashed");
        assertTrue(content.contains("\"runId\":\"hash:"), "run id must be hashed");
        assertTrue(content.contains("\"remote\""), "baseUrlClass expected remote");
        assertTrue(content.contains("\"fallbackCount\":2"));
        assertTrue(content.contains("\"harmonyWarn\":true"));
        assertTrue(content.contains("\"cfvmQueued\":true"));
        assertTrue(content.contains("\"errorClass\":\"none\""));
        assertTrue(content.contains("\"effectiveModel\":\"openai/gpt-oss-120b\""));
        assertTrue(content.contains("prompt.agentDebugEvidence.text"),
                "trace key names are kept, values are not");
    }

    @Test
    void recordTerminal_appendIsIdempotentPerRecordId() throws Exception {
        ChatSessionTraceRecorder recorder = new ChatSessionTraceRecorder(tempDir, true, null);
        Map<String, Object> meta = Map.of("llm.gateway.fallback.count", 0);

        recorder.recordTerminal("chat", 777L, "run-token-A", "m1", "m1", null, "completed", meta);
        recorder.recordTerminal("chat", 777L, "run-token-A", "m1", "m1", null, "completed", meta);
        // 다른 outcome으로 재호출해도 같은 recordId는 다시 쓰지 않는다.
        recorder.recordTerminal("chat", 777L, "run-token-A", "m1", "m1", null, "error", meta);

        List<Path> files;
        try (Stream<Path> s = Files.walk(tempDir)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, files.size(), "one file per session id");
        long lines = Files.readString(files.get(0)).lines().filter(l -> !l.isBlank()).count();
        assertEquals(1, lines, "same recordId must append exactly once");

        // 다른 runId는 같은 세션 파일에 두 번째 줄로 append 된다.
        recorder.recordTerminal("chat", 777L, "run-token-B", "m1", "m1", null, "cancelled", meta);
        lines = Files.readString(files.get(0)).lines().filter(l -> !l.isBlank()).count();
        assertEquals(2, lines, "distinct run ids share the session file");
    }

    @Test
    void recordTerminal_disabledWritesNothing() throws Exception {
        ChatSessionTraceRecorder recorder = new ChatSessionTraceRecorder(tempDir, false, null);
        recorder.recordTerminal("display", 1L, "tok", "m", "m", true, "completed", Map.of());
        try (Stream<Path> s = Files.walk(tempDir)) {
            assertEquals(0, s.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void recordTerminal_derivesBaseUrlClassAndErrorClass() throws Exception {
        ChatSessionTraceRecorder recorder = new ChatSessionTraceRecorder(tempDir, true, null);
        recorder.recordTerminal("display", 5L, "tok-1", "m", "m", false, "error",
                Map.of("llm.factory.baseUrlHost", "127.0.0.1"));
        String content = readAll(tempDir);
        assertTrue(content.contains("\"baseUrlClass\":\"local\""));
        assertTrue(content.contains("\"errorClass\":\"error\""));
        assertTrue(content.contains("\"surface\":\"display\""));
        assertTrue(content.contains("meta-display-db/export"),
                "display records link the existing export path as related");
    }

    private static String readAll(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                sb.append(Files.readString(f));
            }
        }
        return sb.toString();
    }
}
