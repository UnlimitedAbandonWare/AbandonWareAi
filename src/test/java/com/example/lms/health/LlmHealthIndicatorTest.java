package com.example.lms.health;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmHealthIndicatorTest {

    @Test
    void exposesMacMiniRouteWithoutLeakingOwnerToken() {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://localhost:11434/v1")
                .withProperty("llm.chat-model", "gemma3:4b")
                .withProperty("llm.api-key", "sk-local")
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.provider-guard.allow-private-remote", "true")
                .withProperty("llm.provider-guard.allowed-hosts", "macmini-ollama.internal:11434")
                .withProperty("llm.provider-guard.require-auth-for-remote", "true")
                .withProperty("llmrouter.models.macmini.enabled", "true")
                .withProperty("llmrouter.models.macmini.name", "gemma3:4b")
                .withProperty("llmrouter.models.macmini.base-url", "http://macmini-ollama.internal:11434/v1")
                .withProperty("awx.node.role", "macmini-control-plane")
                .withProperty("awx.node.execution-node", "macmini-m4-16gb")
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.node.learning-loop-mode", "curate-observe-schedule");

        Health health = new LlmHealthIndicator(env).health();
        Map<?, ?> routes = (Map<?, ?>) health.getDetails().get("llmrouter.routes");
        Map<?, ?> macmini = (Map<?, ?>) routes.get("macmini");
        Map<?, ?> runtimeNode = (Map<?, ?>) health.getDetails().get("runtimeNode");

        assertEquals("macmini", macmini.get("routeKey"));
        assertEquals(Boolean.TRUE, macmini.get("enabled"));
        assertEquals("local", macmini.get("provider"));
        assertEquals("macmini-ollama.internal", macmini.get("endpointHost"));
        assertEquals(Boolean.TRUE, macmini.get("hasOwnerToken"));
        assertNull(macmini.get("disabledReason"));
        assertEquals("macmini-control-plane", runtimeNode.get("role"));
        assertEquals("macmini-m4-16gb", runtimeNode.get("executionNode"));
        assertEquals(Boolean.TRUE, runtimeNode.get("controlPlane"));
        assertEquals(Boolean.FALSE, runtimeNode.get("heavyWorkloadsAllowed"));
        assertEquals("curate-observe-schedule", runtimeNode.get("learningLoopMode"));
        assertFalse(health.getDetails().toString().contains(ownerToken));
    }

    @Test
    void exposesDesktopGpuGatewayPlanForMacMiniWithoutLeakingOwnerToken() {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://localhost:11434/v1")
                .withProperty("llm.chat-model", "gemma4:26b")
                .withProperty("llm.api-key", "sk-local")
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("awx.node.role", "macmini-control-plane")
                .withProperty("awx.node.execution-node", "macmini-m4-16gb")
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.target-execution-node", "desktop-rtx3090-rtx3060")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.fast-base-url", "http://desktop-gpu.internal:11436/v1")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://desktop-gpu.internal:11436/api/embed")
                .withProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435,desktop-gpu.internal:11436")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true");

        Health health = new LlmHealthIndicator(env).health();
        Map<?, ?> gateway = (Map<?, ?>) health.getDetails().get("gpuGateway");
        Map<?, ?> endpoints = (Map<?, ?>) gateway.get("endpoints");
        Map<?, ?> primary = (Map<?, ?>) endpoints.get("primaryChat");
        Map<?, ?> fast = (Map<?, ?>) endpoints.get("fastHelper");
        Map<?, ?> embedding = (Map<?, ?>) endpoints.get("embedding");

        assertEquals(Boolean.TRUE, gateway.get("enabled"));
        assertEquals("control_plane_to_gpu_executor", gateway.get("mode"));
        assertEquals("handoff_to_desktop_gpu", gateway.get("routePolicy"));
        assertEquals("desktop-rtx3090-rtx3060", gateway.get("targetExecutionNode"));
        assertEquals(Boolean.TRUE, gateway.get("allowedHostsConfigured"));
        assertEquals(Boolean.TRUE, gateway.get("hasRemoteAuth"));
        assertEquals(Boolean.TRUE, gateway.get("hasOwnerToken"));
        assertEquals(Boolean.TRUE, gateway.get("ownerTokenAttachable"));
        assertNull(gateway.get("disabledReason"));
        assertEquals("desktop-gpu.internal", primary.get("endpointHost"));
        assertEquals("desktop-gpu.internal:11435", primary.get("endpointHostPort"));
        assertEquals("rtx3090", primary.get("device"));
        assertEquals("ok", primary.get("status"));
        assertEquals("rtx3060", fast.get("device"));
        assertEquals("ok", fast.get("status"));
        assertEquals("embedding", embedding.get("workload"));
        assertEquals("ok", embedding.get("status"));
        assertFalse(health.getDetails().toString().contains(ownerToken));
    }

    @Test
    void reportsRemoteGpuGatewayMissingAuthAsDisabledReason() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://localhost:11434/v1")
                .withProperty("llm.chat-model", "gemma4:26b")
                .withProperty("llm.api-key", "sk-local")
                .withProperty("llm.owner-token", "")
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.fast-base-url", "http://desktop-gpu.internal:11436/v1")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://desktop-gpu.internal:11436/api/embed")
                .withProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435,desktop-gpu.internal:11436")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true");

        Health health = new LlmHealthIndicator(env).health();
        Map<?, ?> gateway = (Map<?, ?>) health.getDetails().get("gpuGateway");
        Map<?, ?> endpoints = (Map<?, ?>) gateway.get("endpoints");
        Map<?, ?> primary = (Map<?, ?>) endpoints.get("primaryChat");

        assertEquals(Boolean.FALSE, gateway.get("hasRemoteAuth"));
        assertEquals(Boolean.FALSE, gateway.get("ownerTokenAttachable"));
        assertEquals("remote_auth_missing", gateway.get("disabledReason"));
        assertEquals("remote_auth_missing", primary.get("status"));
    }

    @Test
    void exposesExternalRouteMissingKeyAsProviderDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://localhost:11434/v1")
                .withProperty("llm.chat-model", "gemma3:4b")
                .withProperty("llmrouter.models.external.enabled", "true")
                .withProperty("llmrouter.models.external.name", "openrouter/auto")
                .withProperty("llmrouter.models.external.base-url", "https://api.openrouter.ai/api/v1")
                .withProperty("OPENROUTER_API_KEY", "dummy");

        Health health = new LlmHealthIndicator(env).health();
        Map<?, ?> routes = (Map<?, ?>) health.getDetails().get("llmrouter.routes");
        Map<?, ?> external = (Map<?, ?>) routes.get("external");

        assertEquals("external", external.get("routeKey"));
        assertEquals(Boolean.TRUE, external.get("enabled"));
        assertEquals("openrouter", external.get("provider"));
        assertEquals("api.openrouter.ai", external.get("endpointHost"));
        assertEquals(Boolean.FALSE, external.get("hasKey"));
        assertEquals(Boolean.FALSE, external.get("hasOwnerToken"));
        assertEquals("missing OPENROUTER_API_KEY", external.get("disabledReason"));
    }

    @Test
    void exposesOpenCodeExternalRouteWithoutLeakingKeys() {
        String apiKey = "opencode-secret-value";
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://localhost:11434/v1")
                .withProperty("llm.chat-model", "gemma3:4b")
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llmrouter.models.external.enabled", "true")
                .withProperty("llmrouter.models.external.name", "deepseek-v4-flash-free")
                .withProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1")
                .withProperty("OPENCODE_API_KEY", apiKey);

        Health health = new LlmHealthIndicator(env).health();
        Map<?, ?> routes = (Map<?, ?>) health.getDetails().get("llmrouter.routes");
        Map<?, ?> external = (Map<?, ?>) routes.get("external");

        assertEquals("external", external.get("routeKey"));
        assertEquals(Boolean.TRUE, external.get("enabled"));
        assertEquals("opencode", external.get("provider"));
        assertEquals("opencode.ai", external.get("endpointHost"));
        assertEquals(Boolean.TRUE, external.get("hasKey"));
        assertEquals(Boolean.FALSE, external.get("hasOwnerToken"));
        assertNull(external.get("disabledReason"));
        assertFalse(health.getDetails().toString().contains(apiKey));
        assertFalse(health.getDetails().toString().contains(ownerToken));
    }

    @Test
    void exposesOpenCodeExternalRouteMissingKeyAsProviderDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://localhost:11434/v1")
                .withProperty("llm.chat-model", "gemma3:4b")
                .withProperty("llmrouter.models.external.enabled", "true")
                .withProperty("llmrouter.models.external.name", "deepseek-v4-flash-free")
                .withProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1")
                .withProperty("OPENCODE_API_KEY", "dummy");

        Health health = new LlmHealthIndicator(env).health();
        Map<?, ?> routes = (Map<?, ?>) health.getDetails().get("llmrouter.routes");
        Map<?, ?> external = (Map<?, ?>) routes.get("external");

        assertEquals("opencode", external.get("provider"));
        assertEquals("opencode.ai", external.get("endpointHost"));
        assertEquals(Boolean.FALSE, external.get("hasKey"));
        assertEquals(Boolean.FALSE, external.get("hasOwnerToken"));
        assertEquals("missing OPENCODE_API_KEY", external.get("disabledReason"));
    }

    @Test
    void routeGuardAndMaskBaseUrlFailuresLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/health/LlmHealthIndicator.java"));

        assertTrue(source.contains("traceSuppressed(\"llmHealthIndicator.routeGuard\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"llmHealthIndicator.maskBaseUrl\", e);"));
        assertTrue(source.contains("TraceStore.put(\"llm.health.indicator.suppressed.\" + safeStage, true);"));
    }

    @Test
    void suppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        TraceStore.clear();
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method method = LlmHealthIndicator.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "llmHealthIndicator.maskBaseUrl " + secret,
                new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("llm.health.indicator.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.health.indicator.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("llm.health.indicator.suppressed.errorType"));
        assertEquals("IllegalStateException",
                TraceStore.get("llm.health.indicator.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
        TraceStore.clear();
    }

    @Test
    void invalidBaseUrlLeavesStableReasonCodeWithoutLeakingUrlParserClass() {
        TraceStore.clear();
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://[broken")
                .withProperty("llm.chat-model", "gemma3:4b");

        new LlmHealthIndicator(env).health();

        assertEquals(Boolean.TRUE,
                TraceStore.get("llm.health.indicator.suppressed.llmHealthIndicator.maskBaseUrl"));
        assertEquals("invalid_url",
                TraceStore.get("llm.health.indicator.suppressed.llmHealthIndicator.maskBaseUrl.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("URISyntaxException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("http://[broken"));
        TraceStore.clear();
    }
}
