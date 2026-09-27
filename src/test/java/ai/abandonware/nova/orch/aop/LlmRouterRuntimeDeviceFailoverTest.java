package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRouterRuntimeDeviceFailoverTest {

    @Test void fallbackKeepsTheOriginalContextCapacityFloor() throws Throwable {
        try(OpenAiStub local=stub(500,"{\"error\":\"GPU is lost\"}");
            OpenAiStub a=stub(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"too small\"}}]}");
            OpenAiStub b=stub(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"adequate [source-7]\"}}]}")){
            var primary=localRoute("local",local.baseUrl(),"rtx3090");primary.setFallbackKey("cloud");primary.setMinContextTokens(128000);
            var first=localRoute("cloud-a",a.baseUrl(),null);first.setProvider("openai");first.setFallbackKey("cloud-b");first.setMinContextTokens(8000);
            var second=localRoute("cloud-b",b.baseUrl(),null);second.setProvider("openai");second.setMinContextTokens(128000);
            var gateway=eligibleGateway(true);
            doAnswer(invocation->{String key=invocation.getArgument(0),stage=invocation.getArgument(2);LlmRouterProperties.ModelConfig cfg=invocation.getArgument(1);
                return RoutingEligibility.eligible(key,cfg.getProvider(),cfg.getName(),stage,100,false,Map.of("contextTokens",cfg.getMinContextTokens()));
            }).when(gateway).evaluate(anyString(),any(),anyString());
            var router=aspect(routes(Map.of("primary",primary,"cloud",first,"cloud-b",second)),gateway);
            var model=assertInstanceOf(ChatModel.class,router.aroundLcWithTimeout(new FakePjp(null,"llmrouter.primary",null,null,null,null,32,2,0)));
            assertEquals("adequate [source-7]",model.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());assertEquals(0,a.calls());assertEquals(1,b.calls());
        }
    }

    @Test void openLocalCannotBypassCapacityChecksThroughTheLegacyFallbackKey() throws Throwable {
        try (OpenAiStub local=stub(500,"{\"error\":\"must not be called\"}");
             OpenAiStub cloud=stub(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"too small\"}}]}")) {
            var primary=localRoute("local",local.baseUrl(),"rtx3090");
            primary.setFallbackKey("cloud"); primary.setMinContextTokens(128000);
            var fallback=localRoute("cloud",cloud.baseUrl(),null); fallback.setProvider("openai");
            var gateway=eligibleGateway(true);
            doAnswer(invocation->{String key=invocation.getArgument(0),stage=invocation.getArgument(2);
                LlmRouterProperties.ModelConfig cfg=invocation.getArgument(1);
                return key.equals("primary") ? RoutingEligibility.blocked(key,"local",cfg.getName(),stage,0,false,
                        List.of(LlmFailureClass.GPU_DEVICE_LOST),Map.of())
                        : RoutingEligibility.eligible(key,"openai",cfg.getName(),stage,100,false,Map.of("contextTokens",8000));
            }).when(gateway).evaluate(anyString(),any(),anyString());
            var router=aspect(routes(Map.of("primary",primary,"cloud",fallback)),gateway);
            assertThrows(RuntimeException.class,()-> {
                var routed=(ChatModel)router.aroundLcWithTimeout(new FakePjp(null,"llmrouter.primary",null,null,null,null,32,2,0));
                routed.chat(List.of(UserMessage.from("synthetic source-7")));
            });
            assertEquals(0,local.calls()); assertEquals(0,cloud.calls());
        }
    }

    @Test void stalledFirstCloudLeavesTimeForSecondCloudInsideOriginalDeadline() throws Throwable {
        try(OpenAiStub local=stub(500,"{\"error\":\"GPU is lost\"}");
            OpenAiStub a=delayedStub();OpenAiStub b=stub(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"B [source-7]\"}}]}")){
            var primary=localRoute("local",local.baseUrl(),"rtx3090");primary.setFallbackKey("cloud");
            var first=localRoute("cloud-a",a.baseUrl(),null);first.setProvider("openai");first.setFallbackKey("cloud-b");
            var second=localRoute("cloud-b",b.baseUrl(),null);second.setProvider("openai");
            var router=aspect(routes(Map.of("primary",primary,"cloud",first,"cloud-b",second)),eligibleGateway(true));
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(1800));
            long began=System.nanoTime();
            try{var model=assertInstanceOf(ChatModel.class,router.aroundLcWithTimeout(new FakePjp(null,"llmrouter.primary",null,null,null,null,32,5,0)));
                assertEquals("B [source-7]",model.chat(List.of(UserMessage.from("synthetic source-7"))).aiMessage().text());
                org.junit.jupiter.api.Assertions.assertTrue((System.nanoTime()-began)/1_000_000<1800);
                assertEquals(1,local.calls());assertEquals(1,a.calls());assertEquals(1,b.calls());
            }finally{com.abandonware.ai.addons.budget.TimeBudgetContext.clear();}
        }
    }

    private static OpenAiStub delayedStub() throws IOException {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try{Thread.sleep(2200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();return new OpenAiStub(server,calls);
    }

    @Test void allAutoLocalEndpointsOpenStillTriesTheSecondCloud() throws Throwable {
        verifyAllOpenCloudChain(false);
    }

    @Test void allAutoLocalEndpointsOpenSkipsAnIneligibleFirstCloud() throws Throwable {
        verifyAllOpenCloudChain(true);
    }

    private void verifyAllOpenCloudChain(boolean firstCloudIneligible) throws Throwable {
        try(OpenAiStub local=stub(500,"{\"error\":\"must not be called\"}");
            OpenAiStub a=stub(503,"{\"error\":\"temporarily unavailable\"}");
            OpenAiStub b=stub(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"B [source-7]\"}}]}")){
            var primary=localRoute("local",local.baseUrl(),"rtx3090");primary.setFallbackKey("cloud");
            var first=localRoute("cloud-a",a.baseUrl(),null);first.setProvider("openai");first.setFallbackKey("cloud-b");first.setFallbackOnly(true);
            var second=localRoute("cloud-b",b.baseUrl(),null);second.setProvider("openai");second.setFallbackOnly(true);
            var gateway=eligibleGateway(true);
            doAnswer(invocation->{
                String key=invocation.getArgument(0),stage=invocation.getArgument(2);LlmRouterProperties.ModelConfig cfg=invocation.getArgument(1);
                if (key.equals("cloud") && firstCloudIneligible) return RoutingEligibility.blocked(key,"openai",cfg.getName(),stage,0,false,List.of(LlmFailureClass.AUTH_MISSING),Map.of());
                return key.equals("primary")?RoutingEligibility.blocked(key,"local",cfg.getName(),stage,0,false,List.of(LlmFailureClass.GPU_DEVICE_LOST),Map.of()):
                    RoutingEligibility.eligible(key,"openai",cfg.getName(),stage,100,false,Map.of());
            }).when(gateway).evaluate(anyString(),any(),anyString());
            var router=aspect(routes(Map.of("primary",primary,"cloud",first,"cloud-b",second)),gateway);
            var model=assertInstanceOf(ChatModel.class,router.aroundLcWithTimeout(new FakePjp(null,"llmrouter.auto",null,null,null,null,32,2,0)));
            assertEquals("B [source-7]",model.chat(List.of(UserMessage.from("synthetic source-7"))).aiMessage().text());
            assertEquals(0,local.calls());assertEquals(firstCloudIneligible ? 0 : 1,a.calls());assertEquals(1,b.calls());
            assertEquals(1L,TraceStore.get("llm.gateway.preselectionFallbackCount"));
        }
    }

    @Test
    void firstCloudFailureContinuesToTheNextRegisteredCloudExactlyOnce() throws Throwable {
        try (OpenAiStub primary = stub(500, "{\"error\":\"GPU is lost\"}");
             OpenAiStub cloudA = stub(503, "{\"error\":\"temporarily unavailable\"}");
             OpenAiStub cloudB = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"API_B [source-7]\"}}]}")) {
            var local = localRoute("qwen3:30b", primary.baseUrl(), "rtx3090");
            local.setFallbackKey("cloud");
            var first = localRoute("cloud-a", cloudA.baseUrl(), null);
            first.setProvider("openai");
            first.setFallbackKey("cloud-b");
            var second = localRoute("cloud-b", cloudB.baseUrl(), null);
            second.setProvider("openai");
            var router = aspect(routes(Map.of("primary", local, "cloud", first, "cloud-b", second)),
                    eligibleGateway(true));
            ChatModel routed = assertInstanceOf(ChatModel.class, router.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));
            assertEquals("API_B [source-7]", routed.chat(List.of(UserMessage.from("synthetic source-7"))).aiMessage().text());
            assertEquals(1, primary.calls());
            assertEquals(1, cloudA.calls());
            assertEquals(1, cloudB.calls());
        }
    }

    @Test
    void unavailableWithoutFallbackDoesNotReportAProviderTransition() throws Throwable {
        try (OpenAiStub primary = stub(500, "{\"error\":\"must not be called\"}")) {
            var primaryRoute = localRoute("qwen3:30b", primary.baseUrl(), "rtx3090");
            LlmRouterProperties properties = routes(primaryRoute, primaryRoute);
            properties.getModels().remove("backup");
            LlmRouterAspect aspect = aspect(properties, eligibleGateway());
            var manager = new com.example.lms.config.LocalLlmProcessManager() {
                @Override public boolean isAvailable(String endpoint) { return false; }
            };
            org.springframework.test.util.ReflectionTestUtils.setField(aspect, "localLlmProcessManager", manager);
            assertThrows(RuntimeException.class, () -> aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));
            assertEquals(0, primary.calls());
            assertEquals(false, manager.diagnostics().get("fallbackUsed"));
        }
    }

    @Test
    void managerCooldownSkipsPrimaryAndUsesExistingDeviceFallbackOnce() throws Throwable {
        try (OpenAiStub primary = stub(500, "{\"error\":\"must not be called\"}");
             OpenAiStub backup = stub(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"BACKUP_OK\"}}]}")) {
            var primaryRoute = localRoute("qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            var backupRoute = localRoute("qwen3:8b", backup.baseUrl(), "rtx3060");
            LlmRouterAspect aspect = aspect(routes(primaryRoute, backupRoute), eligibleGateway());
            var manager = new com.example.lms.config.LocalLlmProcessManager() {
                @Override public boolean isAvailable(String endpoint) { return !primary.baseUrl().equals(endpoint); }
                @Override public boolean managesEndpoint(String endpoint) { return primary.baseUrl().equals(endpoint); }
            };
            org.springframework.test.util.ReflectionTestUtils.setField(aspect, "localLlmProcessManager", manager);
            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp(null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));
            assertEquals("BACKUP_OK", routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(0, primary.calls());
            assertEquals(1, backup.calls());
            assertEquals("LOCAL_PROVIDER_UNAVAILABLE", TraceStore.get("localLlm.router.fallback"));
        }
    }

    @AfterEach
    void clearState() {
        TraceStore.clear();
        ai.abandonware.nova.orch.router.LlmRouterContext.clear();
    }

    @Test
    void runtimeGpuLossUsesExplicitDifferentLocalDeviceFallbackExactlyOnce() throws Throwable {
        try (OpenAiStub primary = stub(500,
                "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}");
             OpenAiStub backup = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"BACKUP_OK\"}}]}")) {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline("gpu-request", "gpu-session");
            tracker.recordRequestPhase(timelineId, "dispatch", null, null, null);
            tracker.recordRequestPhase(timelineId, "pending", null, null, null);
            TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            TraceStore.put(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            LlmRouterProperties.ModelConfig backupRoute = localRoute(
                    "qwen3:8b", backup.baseUrl(), "rtx3060");
            LlmRouterProperties routes = routes(primaryRoute, backupRoute);
            LlmRouterAspect aspect = aspect(routes, eligibleGateway(), tracker);

            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("BACKUP_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, primary.calls());
            assertEquals(1, backup.calls());
            assertEquals("device_fallback", TraceStore.get("llm.localEndpoint.selectionDecision"));
            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(2, attempts.size());
            assertEquals(List.of("primary", "fallback"),
                    attempts.stream().map(row -> row.get("role")).toList());
            assertEquals(List.of("gpu_device_lost", "none"),
                    attempts.stream().map(row -> row.get("failureClass")).toList());
            assertEquals(List.of(1, 2),
                    attempts.stream().map(row -> row.get("sequence")).toList());
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(primary.baseUrl()));
            assertFalse(trace.contains(backup.baseUrl()));
        }
    }

    @Test
    void runtimeVramExhaustionUsesExplicitDifferentLocalDeviceFallbackExactlyOnce() throws Throwable {
        try (OpenAiStub primary = stub(500,
                "{\"error\":{\"code\":\"vram_oom\",\"message\":\"CUDA out of memory\"}}");
             OpenAiStub backup = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"BACKUP_OK\"}}]}")) {
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            LlmRouterProperties.ModelConfig backupRoute = localRoute(
                    "qwen3:8b", backup.baseUrl(), "rtx3060");
            LlmRouterAspect aspect = aspect(routes(primaryRoute, backupRoute), eligibleGateway());
            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("BACKUP_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, primary.calls());
            assertEquals(1, backup.calls());
            assertEquals("device_fallback", TraceStore.get("llm.localEndpoint.selectionDecision"));
        }
    }

    @Test
    void http200GpuErrorEnvelopeUsesNativeAdapterAndDeviceFallbackExactlyOnce() throws Throwable {
        try (OpenAiStub primary = nativeStub(200,
                "{\"error\":{\"code\":\"gpu_lost\",\"message\":\"GPU is lost\"}}");
             OpenAiStub backup = nativeStub(200,
                     "{\"message\":{\"role\":\"assistant\",\"content\":\"BACKUP_OK\"},"
                             + "\"done\":true,\"done_reason\":\"stop\"}")) {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline("native-request", "native-session");
            tracker.recordRequestPhase(timelineId, "dispatch", null, null, null);
            tracker.recordRequestPhase(timelineId, "pending", null, null, null);
            TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            TraceStore.put(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            LlmRouterProperties.ModelConfig backupRoute = localRoute(
                    "qwen3:8b", backup.baseUrl(), "rtx3060");
            LlmRouterAspect aspect = aspect(
                    routes(primaryRoute, backupRoute), eligibleGateway(), tracker, true);
            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, 0.7d, 0.9d, null, 32, 2, 0)));

            assertEquals("BACKUP_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, primary.calls());
            assertEquals(1, backup.calls());
            assertEquals("device_fallback", TraceStore.get("llm.localEndpoint.selectionDecision"));
            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.route"));
            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(List.of("primary", "fallback"),
                    attempts.stream().map(row -> row.get("role")).toList());
            assertEquals(List.of("gpu_device_lost", "none"),
                    attempts.stream().map(row -> row.get("failureClass")).toList());
        }
    }

    @Test
    void runtimeDeviceFallbackRejectsSameEndpointAlias() throws Throwable {
        try (OpenAiStub endpoint = stub(500,
                "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}")) {
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", endpoint.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            LlmRouterProperties.ModelConfig alias = localRoute(
                    "qwen3:8b", endpoint.baseUrl() + "/alias", "rtx3060");
            LlmRouterAspect aspect = aspect(routes(primaryRoute, alias), eligibleGateway());
            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertThrows(RuntimeException.class,
                    () -> routed.chat(List.of(UserMessage.from("bounded request"))));

            assertEquals(1, endpoint.calls());
        }
    }

    @Test
    void runtimeDeviceFallbackRejectsRemoteCandidateForLocalRoute() throws Throwable {
        try (OpenAiStub primary = stub(500,
                "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}")) {
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            LlmRouterProperties.ModelConfig remote = localRoute(
                    "qwen3:8b", "https://example.invalid/v1", "remote");
            remote.setProvider("openai");
            LlmRouterAspect aspect = aspect(routes(primaryRoute, remote), eligibleGateway());
            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertThrows(RuntimeException.class,
                    () -> routed.chat(List.of(UserMessage.from("bounded request"))));

            assertEquals(1, primary.calls());
        }
    }

    @Test
    void nonGpuRuntimeFailureKeepsConfiguredCloudFallbackPrecedence() throws Throwable {
        try (OpenAiStub primary = stub(503, "{\"error\":\"overloaded\"}");
             OpenAiStub deviceBackup = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"DEVICE\"}}]}");
             OpenAiStub cloudBackup = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"CLOUD\"}}]}")) {
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("device");
            primaryRoute.setFallbackKey("cloud");
            LlmRouterProperties.ModelConfig deviceRoute = localRoute(
                    "qwen3:8b", deviceBackup.baseUrl(), "rtx3060");
            LlmRouterProperties.ModelConfig cloudRoute = localRoute(
                    "cloud-model", cloudBackup.baseUrl(), "cloud-host");
            cloudRoute.setProvider("openai");
            LlmRouterAspect aspect = aspect(
                    routes(Map.of(
                            "primary", primaryRoute,
                            "device", deviceRoute,
                            "cloud", cloudRoute)),
                    eligibleGateway(true));

            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals("CLOUD",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, primary.calls());
            assertEquals(0, deviceBackup.calls());
            assertEquals(1, cloudBackup.calls());
        }
    }

    @Test
    void runtimeDeviceFallbackEligibilityIsCheckedOnlyAfterPrimaryFailure() throws Throwable {
        AtomicInteger backupEligibilityChecks = new AtomicInteger();
        try (OpenAiStub primary = stub(500,
                "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}");
             OpenAiStub backup = stub(200,
                     "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"BACKUP_OK\"}}]}")) {
            LlmRouterProperties.ModelConfig primaryRoute = localRoute(
                    "qwen3:30b", primary.baseUrl(), "rtx3090");
            primaryRoute.setDeviceFallbackKey("backup");
            LlmRouterProperties.ModelConfig backupRoute = localRoute(
                    "qwen3:8b", backup.baseUrl(), "rtx3060");
            HybridLlmGatewayProbeService gateway = eligibleGateway(false);
            doAnswer(invocation -> {
                String key = invocation.getArgument(0);
                LlmRouterProperties.ModelConfig route = invocation.getArgument(1);
                String stage = invocation.getArgument(2);
                if ("backup".equals(key)) {
                    backupEligibilityChecks.incrementAndGet();
                }
                return RoutingEligibility.eligible(
                        key, route.getProvider(), route.getName(), stage, 100,
                        route.isFallbackOnly(), Map.of());
            }).when(gateway).evaluate(anyString(), any(), anyString());
            LlmRouterAspect aspect = aspect(routes(primaryRoute, backupRoute), gateway);

            ChatModel routed = assertInstanceOf(ChatModel.class,
                    aspect.aroundLcWithTimeout(new FakePjp(
                            null, "llmrouter.primary", null, null, null, null, 32, 2, 0)));

            assertEquals(0, backupEligibilityChecks.get());
            assertEquals("BACKUP_OK",
                    routed.chat(List.of(UserMessage.from("bounded request"))).aiMessage().text());
            assertEquals(1, backupEligibilityChecks.get());
        }
    }

    private static LlmRouterProperties routes(
            LlmRouterProperties.ModelConfig primary,
            LlmRouterProperties.ModelConfig backup) {
        return routes(Map.of("primary", primary, "backup", backup));
    }

    private static LlmRouterProperties routes(
            Map<String, LlmRouterProperties.ModelConfig> modelRoutes) {
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

    private static HybridLlmGatewayProbeService eligibleGateway() {
        return eligibleGateway(false);
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
        return gateway;
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(
            LlmRouterProperties properties,
            HybridLlmGatewayProbeService gateway) {
        return aspect(properties, gateway, new ModelRuntimeHealthTracker());
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(
            LlmRouterProperties properties,
            HybridLlmGatewayProbeService gateway,
            ModelRuntimeHealthTracker tracker) {
        return aspect(properties, gateway, tracker, false);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(
            LlmRouterProperties properties,
            HybridLlmGatewayProbeService gateway,
            ModelRuntimeHealthTracker tracker,
            boolean nativeThinkFalseEnabled) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.api-key", "ollama")
                .withProperty("llm.owner-token-header", "X-Owner-Token")
                .withProperty("llm.ollama-native.think-false.enabled",
                        Boolean.toString(nativeThinkFalseEnabled));
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        when(keyResolverProvider.getIfAvailable()).thenReturn(null);
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
                tracker,
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

    private static OpenAiStub nativeStub(int status, String body) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
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
