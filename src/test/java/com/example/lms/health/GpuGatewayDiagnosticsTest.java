package com.example.lms.health;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuGatewayDiagnosticsTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        TraceStore.clear();
    }

    @Test
    void preflightChecksOpenAiCompatibleModelsEndpointWithoutLeakingOwnerToken() throws Exception {
        AtomicReference<String> ownerHeader = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            ownerHeader.set(exchange.getRequestHeaders().getFirst("X-Owner-Token"));
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://127.0.0.1:" + port() + "/v1")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true")
                .withProperty("llm.owner-token", ownerToken);

        Map<String, Object> preflight = GpuGatewayDiagnostics.preflight(env);
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) preflight.get("endpoints");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) endpoints.get("primaryChat");

        assertEquals("ok", preflight.get("status"));
        assertEquals(1, preflight.get("configuredCount"));
        assertEquals(1, preflight.get("reachableCount"));
        assertEquals("ok", primary.get("status"));
        assertEquals(Boolean.TRUE, primary.get("reachable"));
        assertEquals("/v1/models", primary.get("checkPath"));
        assertEquals(ownerToken, ownerHeader.get());
        assertEquals("ok", TraceStore.get("uaw.gpu-gateway.preflight.status"));
        assertFalse(preflight.toString().contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void preflightSkipsRemoteEndpointWhenGuardRequiresMissingAuth() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true")
                .withProperty("llm.owner-token", "");

        Map<String, Object> preflight = GpuGatewayDiagnostics.preflight(env);
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) preflight.get("endpoints");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) endpoints.get("primaryChat");

        assertEquals("unreachable", preflight.get("status"));
        assertEquals("skipped_remote_auth_missing", primary.get("status"));
        assertEquals(Boolean.FALSE, primary.get("reachable"));
        assertEquals("unreachable", TraceStore.get("uaw.gpu-gateway.preflight.status"));
    }

    @Test
    void invalidGatewayEndpointLeavesStableParseReasonWithoutLeakingUrl() {
        String ownerToken = "private-owner-token";
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "true")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url",
                        "http://bad host/" + ownerToken)
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "false");

        Map<String, Object> snapshot = GpuGatewayDiagnostics.snapshot(env);

        assertEquals("invalid_url", TraceStore.get("uaw.gpu-gateway.suppressed.gpuGateway.parseUri.errorType"));
        assertFalse(String.valueOf(snapshot).contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void gpuGatewaySuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method method = GpuGatewayDiagnostics.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "gpuGateway.parseUri " + secret, new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("uaw.gpu-gateway.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.gpu-gateway.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("uaw.gpu-gateway.suppressed.errorType"));
        assertEquals("IllegalStateException",
                TraceStore.get("uaw.gpu-gateway.suppressed." + safeStage + ".errorType"));
        assertFalse(TraceStore.getAll().toString().contains(secret));
    }

    @Test
    void diagnosticEndpointsFollowEffectiveRequestBaseUrlWhenGatewayPropsDisagree() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "true")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "false")
                .withProperty("llm.base-url", "http://request-primary.example:11435/v1")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://diag-primary.example:11435/v1")
                .withProperty("llm.fast.base-url", "http://request-fast.example:11435/v1")
                .withProperty("awx.gpu-gateway.fast-base-url", "http://diag-fast.example:11435/v1")
                .withProperty("embedding.base-url", "http://request-embed.example:11435/api/embed")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://diag-embed.example:11435/api/embed");

        Map<String, Object> snapshot = GpuGatewayDiagnostics.snapshot(env);
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) snapshot.get("endpoints");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) endpoints.get("primaryChat");
        @SuppressWarnings("unchecked")
        Map<String, Object> fast = (Map<String, Object>) endpoints.get("fastHelper");
        @SuppressWarnings("unchecked")
        Map<String, Object> embed = (Map<String, Object>) endpoints.get("embedding");

        assertEquals("request-primary.example", primary.get("endpointHost"));
        assertEquals("request-fast.example", fast.get("endpointHost"));
        assertEquals("request-embed.example", embed.get("endpointHost"));
        assertFalse(String.valueOf(snapshot).contains("diag-primary.example"));
        assertFalse(String.valueOf(snapshot).contains("diag-fast.example"));
        assertFalse(String.valueOf(snapshot).contains("diag-embed.example"));
    }

    @Test
    void diagnosticEndpointsFallBackToGatewayPropsWhenRequestPropsMissing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "true")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "false")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://diag-primary.example:11435/v1");

        Map<String, Object> snapshot = GpuGatewayDiagnostics.snapshot(env);
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) snapshot.get("endpoints");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) endpoints.get("primaryChat");

        assertEquals("diag-primary.example", primary.get("endpointHost"));
    }

    @Test
    void gpuGatewayDiagnosticsDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/health/GpuGatewayDiagnostics.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "GPU gateway diagnostics fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void gpuGatewayFailSoftPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/health/GpuGatewayDiagnostics.java"));

        assertTrue(source.contains("traceSuppressed(\"gpuGateway.guard\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuGateway.timeout\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuGateway.interrupted\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuGateway.io\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuGateway.endpoint\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuGateway.parseUri\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuGateway.intProp\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"uaw.gpu-gateway.suppressed.\" + safeStage, true);"));
    }

    private int port() {
        if (server == null) {
            throw new IllegalStateException("server not started");
        }
        return server.getAddress().getPort();
    }
}
