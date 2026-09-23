package com.example.lms.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ApiStreamDiagnosticRedactionContractTest {

    @Test
    void apiStreamAndDiagnosticLogsDoNotExposeRawThrowableMessagesOrSessionIds() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/api/TasksApiController.java"),
                Path.of("main/java/com/example/lms/service/chat/ChatStreamEmitter.java"),
                Path.of("main/java/com/example/lms/service/diagnostic/DiagnosticsDumpService.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }

        String diagnostics = Files.readString(
                Path.of("main/java/com/example/lms/service/diagnostic/DiagnosticsDumpService.java"),
                StandardCharsets.UTF_8);
        assertFalse(diagnostics.contains("Could not create diagnostics dump directory {}: {}"));
        assertFalse(diagnostics.contains("Failed to write diagnostics dump for session {}: {}"));
        assertTrue(diagnostics.contains("dumpDirHash={}") && diagnostics.contains("sessionHash={}"));
    }

    @Test
    void tasksApiFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String code = Files.readString(
                Path.of("main/java/com/example/lms/api/TasksApiController.java"),
                StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(code.contains("[TasksApi] askSync failed errorHash={} errorLength={}"));
        assertTrue(code.contains("[TasksApi] callback notification failed errorHash={} errorLength={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(code.contains("SafeRedactor.hashValue(messageOf(ex))"));
    }

    @Test
    void chatStreamEmitterFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String code = Files.readString(
                Path.of("main/java/com/example/lms/service/chat/ChatStreamEmitter.java"),
                StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains("[Emitter] Failed to serialize understanding. errorHash={} errorLength={}"));
        assertTrue(code.contains("[Emitter] Failed to send SSE event. errorHash={} errorLength={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void diagnosticsDumpFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String code = Files.readString(
                Path.of("main/java/com/example/lms/service/diagnostic/DiagnosticsDumpService.java"),
                StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(code.contains("Could not create diagnostics dump directory dumpDirHash={} errorHash={} errorLength={}"));
        assertTrue(code.contains("Failed to write diagnostics dump for sessionHash={} errorHash={} errorLength={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(code.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void chatApiControllerDoesNotLogRawThrowableObjectsOnChatFailures() throws Exception {
        String code = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);
        List<String> rawThrowableArgumentLogs = code.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("log.error(")
                        || line.startsWith("log.warn(")
                        || line.startsWith("log.debug("))
                .filter(line -> line.matches(".*,[\\s]*(ex|e|t|throwable)\\);"))
                .toList();

        assertTrue(rawThrowableArgumentLogs.isEmpty(),
                "ChatApiController logs raw throwable objects: " + rawThrowableArgumentLogs);
        assertFalse(code.contains("(err != null && err.getMessage() != null) ? err.getMessage() : \"\""));
        assertFalse(code.contains("SafeRedactor.safeMessage(err == null ? null : err.getMessage(), 180)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(ex.getMessage(), 180)"));
        assertFalse(code.contains("String errMessage = SafeRedactor.safeMessage(ex == null ? null : ex.getMessage(), 180)"));
        assertFalse(code.contains("String errMsg = String.format(\"%s\", SafeRedactor.safeMessage(ex.getMessage(), 180))"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(code.contains("Error: errorHash=%s errorLength=%d"));
        assertTrue(code.contains("errorHash=%s errorLength=%d"));
        assertTrue(code.contains("SafeRedactor.hashValue(err == null ? null : err.getMessage())"));
        assertTrue(code.contains("SafeRedactor.hashValue(ex.getMessage())"));
        assertTrue(code.contains("SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()"));
        assertTrue(code.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
        assertTrue(code.contains("SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()"));
    }

    @Test
    void chatApiStreamErrorsUseStableCodeOwnedLabel() throws Exception {
        String code = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);
        String stableLabel = "Chat stream failed errorHash=%s errorLength=%d";
        int first = code.indexOf(stableLabel);

        assertTrue(first > 0, "replay-sink stream failures should use the stable label");
        assertTrue(code.indexOf(stableLabel, first + stableLabel.length()) > first,
                "direct stream failures should use the same stable label");
        assertFalse(code.contains("String errMsg = String.format(\"???"),
                "stream errors must not expose mojibake labels");
    }
}
