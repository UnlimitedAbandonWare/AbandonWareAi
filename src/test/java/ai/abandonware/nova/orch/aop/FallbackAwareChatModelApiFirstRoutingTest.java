package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.router.*;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.RequestedModelSelection;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FallbackAwareChatModelApiFirstRoutingTest {
    @AfterEach void clear() { TraceStore.clear(); }
    static class Stub implements AutoCloseable {
        final HttpServer server; final AtomicInteger calls = new AtomicInteger();
        Stub(int status) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions", e -> {
                calls.incrementAndGet(); e.getRequestBody().readAllBytes();
                byte[] body = (status == 200
                    ? "{\"model\":\"registered-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}"
                    : "{\"error\":{\"code\":\"synthetic_provider_error\"}}").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type","application/json");
                e.sendResponseHeaders(status,body.length); e.getResponseBody().write(body); e.close();
            }); server.start();
        }
        String endpoint() { return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1"; }
        public void close() { server.stop(0); }
    }
    static LlmRouterProperties.ModelConfig route(String provider, Stub stub) {
        var c = new LlmRouterProperties.ModelConfig(); c.setProvider(provider);
        c.setName(provider.equals("local") ? "qwen3.5:9b" : "registered-model");
        c.setBaseUrl(stub.endpoint()); c.setStage("chat"); c.setFallbackOnly(!provider.equals("local")); return c;
    }
    @SuppressWarnings("unchecked")
    static LlmRouterAspect aspect(Map<String,LlmRouterProperties.ModelConfig> routes, boolean flag) {
        var env = new MockEnvironment().withProperty("llmrouter.api-first.enabled",Boolean.toString(flag))
            .withProperty("llmrouter.api-first.route-order","a,b,local")
            .withProperty("llm.ollama-native.think-false.enabled","false")
            .withProperty("llm.api-key","ollama")
            .withProperty("llm.mistral.api-key",UUID.randomUUID().toString());
        var keys = mock(KeyResolver.class);
        when(keys.resolveLocalLlmCredential()).thenReturn(new com.example.lms.guard.ProviderCredentialResolver.Resolution(
            "local_llm","ollama",true,true,"synthetic",1,false,""));
        when(keys.resolveOpenAiCredential()).thenReturn(new com.example.lms.guard.ProviderCredentialResolver.Resolution(
            "openai",UUID.randomUUID().toString(),true,true,"synthetic",1,false,""));
        var kp = mock(ObjectProvider.class); when(kp.getIfAvailable()).thenReturn(keys);
        var gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.evaluate(anyString(),any(),anyString())).thenAnswer(i -> {
            LlmRouterProperties.ModelConfig c = i.getArgument(1);
            return RoutingEligibility.eligible(i.getArgument(0),c.getProvider(),c.getName(),"chat",100,c.isFallbackOnly(),Map.of());
        });
        when(gateway.guardLocalModel(any(),anyString(),anyString())).thenAnswer(i->i.getArgument(0));
        when(gateway.guardLocalModel(any(),anyString(),anyString(),any())).thenAnswer(i->i.getArgument(0));
        when(gateway.contextPreparationReady(anyString(),anyString())).thenReturn(true);
        var props = new LlmRouterProperties(); props.setModels(routes);
        var guard = new NovaModelGuardProperties(); guard.setEnabled(false);
        return new LlmRouterAspect(env,props,new LlmRouterBandit(props),guard,kp,gateway,null,
            new LlmGatewayFailureClassifier(),new ModelRuntimeHealthTracker(),null);
    }
    static ChatModel select(LlmRouterAspect a) throws Throwable {
        var pjp=mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{"llmrouter.auto",0.0,null,null,null,32,2,0});
        return (ChatModel)a.aroundLcWithTimeout(pjp);
    }
    static String call(ChatModel m) { return m.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text(); }
    @Test void observedContextOverloadKeepsAutomaticRouting() throws Throwable {
        try (var a = new Stub(200)) {
            var router = aspect(Map.of("a", route("openai", a)), true);
            var pjp = mock(ProceedingJoinPoint.class);
            when(pjp.getArgs()).thenReturn(new Object[]{"llmrouter.auto", 0.0, null, null, null, 32, 2, 0, null});
            ChatModel selected = (ChatModel) router.aroundLcWithTimeout(pjp);
            assertNotNull(selected);
            assertEquals("OK", call(selected));
            assertEquals(1, a.calls.get());
            verify(pjp, never()).proceed();
        }
    }
    @Test void defaultRoleSelectsConfiguredCloudPrimary() throws Throwable {
        try(var a=new Stub(200);var b=new Stub(200);var local=new Stub(200)) {
            assertEquals("OK",call(select(aspect(Map.of("a",route("openai",a),"b",route("mistral",b),"local",route("local",local)),true))));
            assertEquals(1,a.calls.get()); assertEquals(0,b.calls.get()); assertEquals(0,local.calls.get());
        }
    }
    @Test void cloudTimeoutSelectsSecondCloudProvider() {
        var attempts=new AtomicInteger();
        ChatModel primary=new ChatModel(){public ChatResponse doChat(ChatRequest r){throw new LlmGatewayException("timeout",LlmFailureClass.TIMEOUT_SOFT,"provider_timeout");}};
        var fallback=chain(primary,attempts,2); apiFirst(fallback);
        assertEquals("OK",call(fallback)); assertEquals(1,attempts.get());
    }
    @Test void secondCloudFailureUsesLocalOnlyWhenPermitAvailable() throws Throwable {
        try(var a=new Stub(503);var b=new Stub(503);var local=new Stub(200)) {
            assertEquals("OK",call(select(aspect(Map.of("a",route("openai",a),"b",route("mistral",b),"local",route("local",local)),true))));
            assertEquals(1,a.calls.get()); assertEquals(1,b.calls.get()); assertEquals(1,local.calls.get());
        }
    }
    @Test void localContentionDoesNotWaitOrQueue() throws Throwable {
        try(var a=new Stub(503);var b=new Stub(503);var local=new Stub(200)) {
            var cfg=route("local",local);
            var aspect=aspect(Map.of("a",route("openai",a),"b",route("mistral",b),"local",cfg),true);
            var admission=(LocalModelAdmission)ReflectionTestUtils.getField(aspect,"localModelAdmission");
            String slot=LocalModelAdmission.slotKey(local.endpoint(),cfg.getName()); assertTrue(admission.tryAcquire(slot,1));
            try { assertThrows(RuntimeException.class,()->call(select(aspect)));
                assertEquals(0,local.calls.get()); assertEquals("local_contended",TraceStore.get("llm.gateway.fallbackReason"));
            } finally { admission.release(slot); }
            assertTrue(admission.tryAcquire(slot,1)); admission.release(slot);
        }
    }
    @Test void authFailureDoesNotRetrySameRoute() throws Throwable {
        try(var a=new Stub(401);var b=new Stub(200)) {
            assertEquals("OK",call(select(aspect(Map.of("a",route("openai",a),"b",route("mistral",b)),true))));
            assertEquals(1,a.calls.get()); assertEquals(1,b.calls.get());
            assertEquals("provider_auth_invalid",TraceStore.get("llm.gateway.fallbackReason"));
        }
    }
    @Test void partialOutputPreventsReplay() { terminal(new LlmGatewayException("partial",LlmFailureClass.STREAM_ERROR,"stream_error_after_partial")); }
    @Test void badRequestDoesNotReplayOnAnotherProvider() throws Throwable {
        try(var a=new Stub(400);var b=new Stub(200)) {
            var model=select(aspect(Map.of("a",route("openai",a),"b",route("mistral",b)),true));
            assertThrows(RuntimeException.class,()->call(model));
            assertEquals(1,a.calls.get()); assertEquals(0,b.calls.get());
        }
    }
    @Test void cancellationNeverTriggersFallback() { terminal(new CancellationException()); }
    static void terminal(RuntimeException ex) {
        var attempts=new AtomicInteger();
        var fallback=chain(new ChatModel(){public ChatResponse doChat(ChatRequest r){throw ex;}},attempts,2); apiFirst(fallback);
        assertThrows(RuntimeException.class,()->call(fallback)); assertEquals(0,attempts.get());
    }
    static void apiFirst(FallbackAwareChatModel model) { ReflectionTestUtils.invokeMethod(model,"withApiFirstPolicy"); }
    static FallbackAwareChatModel chain(ChatModel primary,AtomicInteger attempts,int cap) {
        return new FallbackAwareChatModel(primary,(failure,used)->new FallbackAwareChatModel.ResolvedFallback(
            new ChatModel(){public ChatResponse doChat(ChatRequest r){attempts.incrementAndGet();return ChatResponse.builder().aiMessage(AiMessage.from("OK")).build();}},
            "b",null),new LlmGatewayFailureClassifier(),null,"a",null,null,null,cap);
    }
    @Test void maxExtraFallbackCallsIsRespected() {
        var calls=new AtomicInteger();
        ChatModel fail=new ChatModel(){public ChatResponse doChat(ChatRequest r){calls.incrementAndGet();throw new LlmGatewayException("unavailable",LlmFailureClass.HEALTH_DOWN,"upstream_unavailable");}};
        var model=new FallbackAwareChatModel(fail,(failure,used)->new FallbackAwareChatModel.ResolvedFallback(fail,"route"+used.size(),null),
            new LlmGatewayFailureClassifier(),null,"a",null,null,null,1); apiFirst(model);
        assertThrows(RuntimeException.class,()->call(model)); assertEquals(2,calls.get());
    }
    @Test void routingDisabledPreservesLegacyBehavior() throws Throwable {
        try(var a=new Stub(200);var local=new Stub(200)) {
            var cfg=route("local",local); cfg.setName("qwen3.5:9b");
            assertEquals("OK",call(select(aspect(Map.of("a",route("openai",a),"local",cfg),false))));
            assertEquals(0,a.calls.get()); assertEquals(1,local.calls.get());
        }
    }
    @Test void oauthRouteNeverAutoSelected() throws Throwable {
        try(var a=new Stub(200);var oauth=new Stub(200)) {
            var router = aspect(Map.of("a",route("openai",a),"personal",route(com.example.lms.llm.ChatGptOAuthRegistration.PROVIDER,oauth)),true);
            ((MockEnvironment)ReflectionTestUtils.getField(router,"env"))
                    .withProperty("llmrouter.api-first.route-order","personal,a");
            assertEquals("OK",call(select(router)));
            assertEquals(0,oauth.calls.get()); assertEquals(1,a.calls.get());
        }
    }
}
