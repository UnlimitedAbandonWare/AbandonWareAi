package com.example.lms.service.diagnostic;

import com.example.lms.diag.RetrievalDiagnosticsCollector;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsDumpServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void dumpWritesHashOnlyIdentifiersAndSelectedDocs() throws Exception {
        RetrievalDiagnosticsCollector collector = new RetrievalDiagnosticsCollector();
        DiagnosticsDumpService service = new DiagnosticsDumpService(collector);
        ReflectionTestUtils.setField(service, "dumpDir", tempDir.toString());

        String rawSession = "raw-session-diagnostics-123";
        String rawTrace = "raw-trace-diagnostics-456";
        String rawModel = "private-model-name";
        String rawDoc = "https://private.example.test/doc?token=secret-value";
        String rawFeedback = "ownerToken=private-token";

        MDC.put("traceId", rawTrace);
        try {
            service.dump(rawSession, rawModel, 10L, 5L, 0.01d, List.of(rawDoc), "SUCCESS", rawFeedback, 0.2d);
        } finally {
            MDC.clear();
        }

        Path expected = tempDir.resolve(SafeRedactor.hash12(rawSession) + ".jsonl");
        assertTrue(Files.exists(expected));
        assertFalse(Files.exists(tempDir.resolve(rawSession + ".jsonl")));

        String body = Files.readString(expected);
        assertFalse(body.contains(rawSession));
        assertFalse(body.contains(rawTrace));
        assertFalse(body.contains(rawModel));
        assertFalse(body.contains(rawDoc));
        assertFalse(body.contains(rawFeedback));
        assertTrue(body.contains("\"sessionHash\""));
        assertTrue(body.contains("\"traceHash\""));
        assertTrue(body.contains("\"modelHash\""));
        assertTrue(body.contains("\"selectedDocHashes\""));
        assertTrue(body.contains("\"userFeedbackHash\""));
    }
}
