package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsSecurityBoundaryContractTest {

    @Test
    void diagnosticsGetAndSseRequireAdminAuthenticationAtFilterAndChain() throws Exception {
        String app = Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"));
        String filter = Files.readString(Path.of("main/java/com/example/lms/security/AdminTokenGuardFilter.java"));

        assertTrue(app.contains("requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").hasRole(\"ADMIN\")"));
        assertFalse(app.contains("requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").permitAll()"));
        assertTrue(filter.contains("isDiagnosticRequest(path)"));
        assertTrue(filter.contains("path.equals(\"/api/diagnostics\") || path.startsWith(\"/api/diagnostics/\")"));
        assertFalse(filter.contains("isDiagnosticWriteRequest"));
    }

    @Test
    void diagnosticsSseKeepsTheExistingDebugEventWireContract() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/DebugEventsDiagnosticsController.java"));
        Path runtimePath = Path.of("main/java/com/example/lms/api/DebugEventsSseRuntime.java");

        assertFalse(source.contains("Executors.newCachedThreadPool"));
        assertFalse(source.contains("new SseEmitter(0L)"));
        assertTrue(source.contains("FutureTask"));
        assertTrue(source.contains("sseRuntime.execute"));
        assertTrue(source.contains("@Autowired"));
        assertTrue(source.contains("${lms.debug.events.sse.timeout-ms:300000}"));
        assertTrue(source.contains(".name(\"debug-event\")"));
        assertFalse(source.contains("@RequestParam(name = \"view\""));
        assertFalse(source.contains("failure-signals"));
        assertFalse(source.contains("failure-signal.v1"));

        assertTrue(Files.isRegularFile(runtimePath));
        String runtime = Files.readString(runtimePath);
        assertTrue(runtime.contains("SynchronousQueue"));
        assertTrue(runtime.contains("@PreDestroy"));
        assertTrue(runtime.contains("shutdownNow"));
    }
}
