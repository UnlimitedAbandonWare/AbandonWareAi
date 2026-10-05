package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LocalModelAdmission;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.RoutingEligibility;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 제작 검증: 선택된 로컬 (model,endpoint) 슬롯이 다른 in-flight 요청으로 포화일 때
 * 라우터가 silent fail 없이 기존 폴백 경로(reason=local_contended)로 우회하는지 확인한다.
 */
class LlmRouterLocalContentionTest {

    @Test
    void saturatedLocalPrimaryBypassesToEligibleCloudWithLocalContendedReason() throws Throwable {
        try (OpenAiStub local = stub(500, "{\"error\":\"must not be called\"}");
             OpenAiStub cloud = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"CLOUD_OK\"}}]}")) {
            var primary = localRoute("qwen3:30b", local.baseUrl(), "rtx3090");
            primary.setFallbackKey("cloud");
            var cloudRoute = localRoute("cloud-model", cloud.baseUrl(), null);
            cloudRoute.setProvider("openai");
            var gateway = eligibleGateway(true);
            when(gateway.localFailoverEnabled()).thenReturn(true);
            var aspect = aspect(routes(Map.of("primary", primary, "cloud", cloudRoute)), gateway);
            admission(aspect).tryAcquire(LocalModelAdmission.slotKey(local.baseUrl(), "qwen3:30b"), 1);

            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("CLOUD_OK",
                    routed.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(0, local.calls());
            assertEquals(1, cloud.calls());
            assertEquals("local_contended", TraceStore.get("llm.gateway.preselectionReason"));
            assertEquals(Boolean.TRUE, TraceStore.get("llm.localAdmission.contended"));
            assertEquals(1L, TraceStore.get("llm.gateway.preselectionFallbackCount"));
        }
    }

    @Test
    void invokeTimeSlotContentionFallsBackThroughLazyResolver() throws Throwable {
        try (OpenAiStub local = stub(500, "{\"error\":\"must not be called\"}");
             OpenAiStub cloud = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"CLOUD_OK\"}}]}")) {
            var primary = localRoute("qwen3:30b", local.baseUrl(), "rtx3090");
            primary.setFallbackKey("cloud");
            var cloudRoute = localRoute("cloud-model", cloud.baseUrl(), null);
            cloudRoute.setProvider("openai");
            var gateway = eligibleGateway(true);
            when(gateway.localFailoverEnabled()).thenReturn(true);
            var aspect = aspect(routes(Map.of("primary", primary, "cloud", cloudRoute)), gateway,
                    Map.of("llmrouter.api-first.enabled", "true", "llmrouter.api-first.route-order", "cloud"));

            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            // route 시점 이후 invoke 직전에 다른 in-flight 요청이 슬롯을 점유한 경합을 재현한다.
            admission(aspect).tryAcquire(LocalModelAdmission.slotKey(local.baseUrl(), "qwen3:30b"), 1);

            assertEquals("CLOUD_OK",
                    routed.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(0, local.calls());
            assertEquals(1, cloud.calls());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.localAdmission.contended"));
        }
    }

    @Test
    void contendedLocalRepicksAnotherUncontendedLocalArm() throws Throwable {
        try (OpenAiStub primary = stub(500, "{\"error\":\"must not be called\"}");
             OpenAiStub backup = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"BACKUP_OK\"}}]}")) {
            var primaryRoute = localRoute("qwen3:30b", primary.baseUrl(), "rtx3090");
            var backupRoute = localRoute("qwen3:8b", backup.baseUrl(), "rtx3060");
            var aspect = aspect(
                    routes(Map.of("primary", primaryRoute, "backup", backupRoute)),
                    eligibleGateway(false));
            admission(aspect).tryAcquire(LocalModelAdmission.slotKey(primary.baseUrl(), "qwen3:30b"), 1);

            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("BACKUP_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(0, primary.calls());
            assertEquals(1, backup.calls());
            assertEquals("local_contended", TraceStore.get("llm.gateway.preselectionReason"));
        }
    }

    @Test
    void contendedLocalWithoutEligibleFallbackFailsWithLocalContendedReason() throws Throwable {
        try (OpenAiStub local = stub(500, "{\"error\":\"must not be called\"}")) {
            var primary = localRoute("qwen3:30b", local.baseUrl(), "rtx3090");
            var aspect = aspect(routes(Map.of("primary", primary)), eligibleGateway(false));
            admission(aspect).tryAcquire(LocalModelAdmission.slotKey(local.baseUrl(), "qwen3:30b"), 1);

            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    aspect.aroundLcWithTimeout(
                            new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertTrue(String.valueOf(failure.getMessage()).contains("local_contended"));
            assertEquals(0, local.calls());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.localAdmission.contended"));
        }
    }

    @Test
    void unsaturatedLocalSlotServesNormally() throws Throwable {
        try (OpenAiStub local = stub(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"LOCAL_OK\"}}]}")) {
            var primary = localRoute("qwen3:8b", local.baseUrl(), "rtx3090");
            var aspect = aspect(routes(Map.of("primary", primary)), eligibleGateway(false));

            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("LOCAL_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, local.calls());
        }
    }

    @Test
    void disabledAdmissionIgnoresSaturatedSlot() throws Throwable {
        try (OpenAiStub local = stub(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"LOCAL_OK\"}}]}")) {
            var primary = localRoute("qwen3:8b", local.baseUrl(), "rtx3090");
            var aspect = aspect(routes(Map.of("primary", primary)), eligibleGateway(false),
                    Map.of("llm.local-admission.enabled", "false"));
            admission(aspect).tryAcquire(LocalModelAdmission.slotKey(local.baseUrl(), "qwen3:8b"), 1);

            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("LOCAL_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, local.calls());
        }
    }

    @AfterEach
    void clearState() {
        TraceStore.clear();
        ai.abandonware.nova.orch.router.LlmRouterContext.clear();
    }

    private static LocalModelAdmission admission(LlmRouterAspect aspect) {
        return (LocalModelAdmission) ReflectionTestUtils.getField(aspect, "localModelAdmission");
    }

    private static LlmRouterProperties routes(Map<String, LlmRouterProperties.ModelConfig> modelRoutes) {
        LlmRouterProperties properties = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        models.putAll(modelRoutes);
        properties.setModels(models);
        return properties;
    }

    private static LlmRouterProperties.ModelConfig localRoute(
            String model,
            String baseUrl,
            String deviceRole) {
        LlmRouterProperties.ModelConfig route = new LlmRouterProperties.ModelConfig();
        route.setEnabled(true);
        route.setProvider("local");
        route.setStage("chat");
        route.setName(model);
        route.setBaseUrl(baseUrl);
        route.setDeviceRole(deviceRole);
        route.setWeight(1.0d);
        return route;
    }

    private static HybridLlmGatewayProbeService eligibleGateway(boolean cloudFallbackEnabled) {
        HybridLlmGatewayProbeService gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.isEnforce()).thenReturn(true);
        when(gateway.cloudFallbackEnabled()).thenReturn(cloudFallbackEnabled);
        when(gateway.cloudRouteKey()).thenReturn(cloudFallbackEnabled ? "cloud" : null);
        when(gateway.evaluate(anyString(), any(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            LlmRouterProperties.ModelConfig route = invocation.getArgument(1);
            String stage = invocation.getArgument(2);
            return RoutingEligibility.eligible(
                    key,
                    route.getProvider(),
                    route.getName(),
                    stage,
                    100,
                    route.isFallbackOnly(),
                    Map.of());
        });
        // 실제 구현은 non-null 모델을 그대로(래핑해) 돌려준다 — mock 기본값 null이
        // decorateRoutedAttempt 경로의 primary를 null로 만들어 FallbackAwareChatModel이 NPE를 던진다.
        when(gateway.guardLocalModel(any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.guardLocalModel(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return gateway;
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(
            LlmRouterProperties properties,
            HybridLlmGatewayProbeService gateway) {
        return aspect(properties, gateway, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(
            LlmRouterProperties properties,
            HybridLlmGatewayProbeService gateway,
            Map<String, String> extraProperties) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.api-key", "ollama")
                .withProperty("llm.owner-token-header", "X-Owner-Token")
                .withProperty("llm.ollama-native.think-false.enabled", "false");
        extraProperties.forEach(environment::withProperty);
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        KeyResolver resolver = mock(KeyResolver.class);
        when(resolver.resolveLocalLlmCredential()).thenReturn(new com.example.lms.guard.ProviderCredentialResolver.Resolution(
                "local_llm", "ollama", true, true, "synthetic", 1, false, ""));
        when(resolver.resolveOpenAiCredential()).thenReturn(new com.example.lms.guard.ProviderCredentialResolver.Resolution(
                "openai", java.util.UUID.randomUUID().toString(), true, true, "synthetic", 1, false, ""));
        when(keyResolverProvider.getIfAvailable()).thenReturn(resolver);
        NovaModelGuardProperties guard = new NovaModelGuardProperties();
        guard.setEnabled(false);
        return new LlmRouterAspect(
                environment,
                properties,
                new LlmRouterBandit(properties),
                guard,
                keyResolverProvider,
                gateway,
                null,
                new LlmGatewayFailureClassifier(),
                new ModelRuntimeHealthTracker(),
                null);
    }

    private static OpenAiStub stub(int status, String body) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return new OpenAiStub(server, calls);
    }

    private record OpenAiStub(HttpServer server, AtomicInteger callCount) implements AutoCloseable {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int calls() {
            return callCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override public Object proceed() { return result; }
        @Override public Object proceed(Object[] arguments) { return result; }
        @Override public void set$AroundClosure(AroundClosure arc) { }
        @Override public Object getThis() { return this; }
        @Override public Object getTarget() { return this; }
        @Override public Object[] getArgs() { return args; }
        @Override public Signature getSignature() { return null; }
        @Override public SourceLocation getSourceLocation() { return null; }
        @Override public String getKind() { return "method-execution"; }
        @Override public JoinPoint.StaticPart getStaticPart() { return null; }
        @Override public String toShortString() { return "FakePjp"; }
        @Override public String toLongString() { return "FakePjp"; }
    }
}
