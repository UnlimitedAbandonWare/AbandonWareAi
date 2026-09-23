package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.SseEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmGatewayBreadcrumbPublisherTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void publisherDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/llm/gateway/LlmGatewayBreadcrumbPublisher.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "LLM gateway breadcrumb publisher needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesRedactedTraceAndSsePayload() {
        SseEventPublisher sse = mock(SseEventPublisher.class);
        ObjectProvider<SseEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sse);
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(provider);

        publisher.publishEligibility(new RoutingEligibility(
                "api3",
                "groq",
                "qwen/qwen3-32b",
                "chat",
                80,
                true,
                false,
                List.of(),
                Map.of("apiKey", "secret-value", "endpointHost", "api.groq.com")));

        verify(sse).emit(eq("llm.gateway.route"), any());
        assertFalse(TraceStore.getAll().toString().contains("secret-value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failoverPublishesApiRoutingContractFields() {
        SseEventPublisher sse = mock(SseEventPublisher.class);
        ObjectProvider<SseEventPublisher> sseProvider = mock(ObjectProvider.class);
        when(sseProvider.getIfAvailable()).thenReturn(sse);

        LlmRouterProperties props = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig local = new LlmRouterProperties.ModelConfig();
        local.setProvider("local");
        local.setName("qwen3:8b");
        local.setBaseUrl("http://127.0.0.1:11435/v1");
        LlmRouterProperties.ModelConfig cloud = new LlmRouterProperties.ModelConfig();
        cloud.setProvider("groq");
        cloud.setName("qwen/qwen3-32b");
        cloud.setBaseUrl("https://api.groq.com/openai/v1");
        props.setModels(Map.of("local-a", local, "api3", cloud));
        ObjectProvider<LlmRouterProperties> propsProvider = mock(ObjectProvider.class);
        when(propsProvider.getIfAvailable()).thenReturn(props);

        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("com.example.lms.routing.ApiRoutingDebug");
        java.util.logging.Level priorLevel = jul.getLevel();
        boolean priorParent = jul.getUseParentHandlers();
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        jul.addHandler(capture);
        jul.setLevel(java.util.logging.Level.ALL);
        jul.setUseParentHandlers(false);
        try {
            LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(sseProvider, propsProvider);
            publisher.publishFailure("local-a", LlmFailureClass.TIMEOUT_SOFT, new RuntimeException("connect timeout"));
            publisher.publishFallback("local-a", "api3", LlmFailureClass.TIMEOUT_SOFT, "local_provider_unavailable");
        } finally {
            jul.removeHandler(capture);
            jul.setLevel(priorLevel);
            jul.setUseParentHandlers(priorParent);
        }

        assertEquals("local", TraceStore.get("llm.gateway.fallback.provider"));
        assertEquals("timeout_soft", TraceStore.get("llm.gateway.fallback.errorClass"));
        assertEquals("groq", TraceStore.get("llm.gateway.fallback.fallbackProvider"));
        assertEquals("local", TraceStore.get("llm.gateway.failure.provider"));
        assertEquals("timeout_soft", TraceStore.get("llm.gateway.failure.errorClass"));

        assertTrue(records.stream().anyMatch(r -> {
            Object[] p = r.getParameters();
            return p != null && p.length == 8
                    && "local".equals(String.valueOf(p[1]))
                    && "timeout_soft".equals(String.valueOf(p[6]))
                    && "groq".equals(String.valueOf(p[7]));
        }), "ApiRoutingDebug failure line must carry provider/errorClass/fallbackTo");
        assertFalse(records.toString().contains("qwen3-32b-secret"),
                "no credential material may reach the routing debug record");
    }
}
