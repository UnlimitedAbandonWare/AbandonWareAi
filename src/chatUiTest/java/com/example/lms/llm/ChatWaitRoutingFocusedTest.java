package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.*;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatWaitRoutingFocusedTest {
    @Test void configuredRoleModelUsesConfiguredDevice() {
        var env = new MockEnvironment().withProperty("llm.coder.model", "installed-model:custom");
        var factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:19101/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://127.0.0.1:19102/v1");
        assertEquals("http://127.0.0.1:19102/v1", ReflectionTestUtils.invokeMethod(
                factory, "selectLocalBaseUrl", "installed-model:custom"));
    }

    @Test void exactCoderSelectionUsesItsConfiguredEndpointAndRespectsOffRoute() {
        String model = "installed-model:custom";
        String coderUrl = "http://127.0.0.1:19102/v1";
        var env = new MockEnvironment().withProperty("llm.coder.model", model);
        var factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:19101/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", coderUrl);
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        RequestedModelSelection.begin(model);
        try {
            assertNotNull(factory.lcWithTimeout(model, null, null, 64, 5));
            assertEquals(SafeRedactor.hashValue(coderUrl), TraceStore.get("llm.factory.baseUrlHash"));

            var route = new LlmRouterProperties.ModelConfig();
            route.setName(model);
            route.setBaseUrl(coderUrl);
            route.setStage("coder");
            route.setDeviceRole("coder");
            route.setEnabled(false);
            var routes = new LlmRouterProperties();
            routes.getModels().put("coder", route);
            ReflectionTestUtils.setField(factory, "llmRouterProperties", routes);
            var denied = assertThrows(LlmGatewayException.class,
                    () -> factory.lcWithTimeout(model, null, null, 64, 5));
            assertEquals("route_disabled", denied.reasonCode());
        } finally { TraceStore.clear(); }
    }

    @Test void fallbackAvailabilityDoesNotThirdThePrimaryTimeout() {
        TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(240)));
        try {
            Duration timeout = ReflectionTestUtils.invokeMethod(DynamicChatModelFactory.class,
                    "localPrimaryTimeout", 180, true);
            assertEquals(Duration.ofSeconds(180), timeout);
        } finally { TimeBudgetContext.clear(); }
    }

    @Test void callerTimeoutKeepsBackendBusyUntilActualWorkerExit() throws Exception {
        var props = new LlmGatewayProperties();
        props.getLocalDeviceFailover().setEnabled(true);
        var gateway = new HybridLlmGatewayProbeService(props, new ModelRuntimeHealthTracker(),
                null, new LlmRouteScorer());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel slow = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet(); entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                finally { exited.countDown(); }
                return ChatResponse.builder().aiMessage(AiMessage.from("completed")).build();
            }
        };
        var first = gateway.guardLocalModel(slow, "http://127.0.0.1:19103/v1", "first");
        var otherModel = gateway.guardLocalModel(slow, "http://127.0.0.1:19103", "second");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> timeout = caller.submit(() -> {
                Exception failure = assertThrows(Exception.class, () -> TimedChatModelCaller.chat(first,
                        List.of(UserMessage.from("synthetic")), Duration.ofMillis(200), "test", "first"));
                assertTrue(TimedChatModelCaller.isHardTimeout(failure));
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            timeout.get(2, TimeUnit.SECONDS);
            var busy = assertThrows(LlmGatewayException.class,
                    () -> otherModel.chat(List.of(UserMessage.from("synthetic"))));
            assertEquals("local_backend_busy", busy.reasonCode());
            assertEquals(1, calls.get());
            AtomicInteger cloudCalls = new AtomicInteger();
            ChatModel cloud = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    cloudCalls.incrementAndGet();
                    return ChatResponse.builder().aiMessage(AiMessage.from("cloud completed"))
                            .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                    .modelName("configured-cloud-model").build()).build();
                }
            };
            var routed = new FallbackAwareChatModel(otherModel, () -> cloud, null, null, "local", "api3");
            var answer = TimedChatModelCaller.chat(routed, List.of(UserMessage.from("synthetic")),
                    Duration.ofSeconds(1), "test", "requested-local-model");
            assertEquals("cloud completed", answer.text());
            assertEquals(1, cloudCalls.get());
            assertEquals(1, calls.get(), "busy GPU must not dispatch a second generation");
            assertEquals("configured-cloud-model", TraceStore.get("llm.call.responseModel"));
        } finally {
            release.countDown(); exited.await(2, TimeUnit.SECONDS);
            caller.shutdownNow(); TraceStore.clear();
        }
    }

    @Test void cancellingSelectedFallbackPreservesCancellation() {
        var cancellation = new CancellationException("synthetic cancellation");
        var calls = new AtomicInteger();
        ChatModel local = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                throw new LlmGatewayException("synthetic timeout", LlmFailureClass.TIMEOUT_SOFT, "test");
            }
        };
        ChatModel cloud = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet(); throw cancellation;
            }
        };
        var routed = new FallbackAwareChatModel(local, (failure, used) ->
                new FallbackAwareChatModel.ResolvedFallback(cloud, "api3", null),
                null, null, "local", null, null, null, 1);
        try {
            assertSame(cancellation, assertThrows(CancellationException.class,
                    () -> routed.chat(List.of(UserMessage.from("synthetic")))));
            assertEquals(1, calls.get());
            assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(cancellation));
        } finally { TraceStore.clear(); }
    }

    @Test void expiredGroqAccountProofIsBlockedBeforeRouteSelection(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var evidence = temp.resolve("plan.json");
        java.nio.file.Files.writeString(evidence,
                "{\"plan\":\"free\",\"verifiedAtMs\":1,\"expiresAtMs\":2}");
        var env = new MockEnvironment().withProperty("GROQ_API_KEY", "synthetic-fixture-credential")
                .withProperty("groq.free-tier.evidence", evidence.toString())
                .withProperty("groq.free-tier.ledger", temp.resolve("unused-ledger.jsonl").toString());
        var gateway = new HybridLlmGatewayProbeService(new LlmGatewayProperties(),
                new ModelRuntimeHealthTracker(), null, new LlmRouteScorer(), env);
        var guard = new com.example.lms.agent.GroqFreeTierGuard(env);
        ReflectionTestUtils.setField(gateway, "groqFreeTierGuard", guard);
        var route = new ai.abandonware.nova.config.LlmRouterProperties.ModelConfig();
        route.setEnabled(true); route.setProvider("groq"); route.setName("openai/gpt-oss-120b");
        route.setBaseUrl("https://api.groq.com/openai/v1");
        var result = gateway.evaluate("api3", route, "chat");
        assertFalse(result.eligible());
        assertEquals(LlmFailureClass.AUTH_MISSING, result.primaryFailure());
        assertEquals("groq_free_account_evidence_needed", result.safeMeta().get("disabledReason"));
        assertFalse(result.asBreadcrumb().toString().contains("synthetic-fixture-credential"));
        assertFalse(java.nio.file.Files.exists(temp.resolve("unused-ledger.jsonl")),
                "eligibility must not reserve quota");
        ReflectionTestUtils.setField(gateway, "groqFreeTierGuard", null);
        assertEquals("groq_free_guard_unavailable", gateway.evaluate("api3", route, "chat")
                .safeMeta().get("disabledReason"));
    }

    @Test void missingResponseIdentityDoesNotReusePreviousAttempt() throws Exception {
        TraceStore.putInternal("llm.call.responseModel", "previous-model");
        ChatModel noIdentity = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("answer")).build();
            }
        };
        try {
            TimedChatModelCaller.chat(noIdentity, List.of(UserMessage.from("synthetic")),
                    Duration.ofSeconds(1), "test", "requested");
            assertNull(TraceStore.get("llm.call.responseModel"));
        } finally { TraceStore.clear(); }
    }
}
