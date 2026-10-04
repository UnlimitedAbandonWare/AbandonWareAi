package ai.abandonware.nova.orch.aop;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.NamedChatModel;
import com.example.lms.llm.gateway.FallbackAwareChatModel;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutedWrapperIdentityTest {
    @AfterEach void clear() { TraceStore.clear(); }

    @Test void lazyFallbackWrapperKeepsItsPrimaryConstructionIdentity() throws Throwable {
        try (var stub = new FallbackAwareChatModelApiFirstRoutingTest.Stub(200)) {
            var cfg = FallbackAwareChatModelApiFirstRoutingTest.route("openai", stub);
            ChatModel selected = FallbackAwareChatModelApiFirstRoutingTest.select(
                    FallbackAwareChatModelApiFirstRoutingTest.aspect(Map.of("a", cfg), true));
            assertInstanceOf(FallbackAwareChatModel.class, selected);
            assertIdentity(selected, cfg.getName(), "openai");
            assertEquals(0, stub.calls.get(), "construction must not generate");
        }
    }

    @Test void routeWithoutLazyFallbackKeepsItsConstructionIdentity() throws Throwable {
        try (var stub = new FallbackAwareChatModelApiFirstRoutingTest.Stub(200)) {
            var cfg = FallbackAwareChatModelApiFirstRoutingTest.route("openai", stub);
            var aspect = FallbackAwareChatModelApiFirstRoutingTest.aspect(Map.of("a", cfg), false);
            var call = mock(ProceedingJoinPoint.class);
            when(call.getArgs()).thenReturn(new Object[]{"llmrouter.a", 0.0, null, null, null, 32, 2, 0});
            ChatModel selected = (ChatModel) aspect.aroundLcWithTimeout(call);
            assertFalse(selected instanceof FallbackAwareChatModel);
            assertIdentity(selected, cfg.getName(), "openai");
            assertEquals(0, stub.calls.get(), "construction must not generate");
        }
    }

    @Test void separateReturnedClientsNeverUseTheLastTraceIdentity() throws Throwable {
        try (var first = new FallbackAwareChatModelApiFirstRoutingTest.Stub(200);
             var second = new FallbackAwareChatModelApiFirstRoutingTest.Stub(200)) {
            var a = FallbackAwareChatModelApiFirstRoutingTest.route("openai", first);
            var b = FallbackAwareChatModelApiFirstRoutingTest.route("mistral", second);
            a.setName("fixture-model-A");
            b.setName("fixture-model-B");
            ChatModel clientA = FallbackAwareChatModelApiFirstRoutingTest.select(
                    FallbackAwareChatModelApiFirstRoutingTest.aspect(Map.of("a", a), true));
            ChatModel clientB = FallbackAwareChatModelApiFirstRoutingTest.select(
                    FallbackAwareChatModelApiFirstRoutingTest.aspect(Map.of("a", b), true));
            TraceStore.put("observedModel", "unrelated-later-model");
            TraceStore.put("observedProvider", "unrelated-provider");
            assertIdentity(clientA, a.getName(), "openai");
            assertIdentity(clientB, b.getName(), "mistral");
            assertEquals(0, first.calls.get() + second.calls.get());
        }
    }

    @Test void substitutedConstructionIsNeverRelabelledFromTheOriginalConfig() throws Throwable {
        for (boolean captured : new boolean[]{true, false}) {
            try (var stub = new FallbackAwareChatModelApiFirstRoutingTest.Stub(200)) {
                var cfg = FallbackAwareChatModelApiFirstRoutingTest.route("openai", stub);
                cfg.setName("synthetic-responses-only");
                var aspect = FallbackAwareChatModelApiFirstRoutingTest.aspect(Map.of("a", cfg), true);
                var guard = (ai.abandonware.nova.config.NovaModelGuardProperties)
                        org.springframework.test.util.ReflectionTestUtils.getField(aspect, "modelGuardProps");
                guard.setEnabled(true);
                guard.setOpenAiBaseOnly(false);
                guard.setMode(ai.abandonware.nova.config.NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);
                guard.setResponsesOnlyPrefixes(java.util.List.of(cfg.getName()));
                guard.setSubstituteChatModel("synthetic-chat-substitute");
                if (!captured) org.springframework.test.util.ReflectionTestUtils.setField(
                        aspect, "modelRuntimeHealthTracker", null);
                ChatModel selected = FallbackAwareChatModelApiFirstRoutingTest.select(aspect);
                assertEquals(com.example.lms.trace.SafeRedactor.hashValue("synthetic-chat-substitute"),
                        TraceStore.get("llm.modelGuard.substituteChatModelHash"));
                assertNull(DynamicChatModelFactory.configuredModelIdentity(selected),
                        "missing or mismatched construction evidence must remain unresolved");
                assertEquals(0, stub.calls.get());
                TraceStore.clear();
            }
        }
    }

    private static void assertIdentity(ChatModel model, String name, String provider) {
        assertEquals(name, NamedChatModel.resolve(model));
        var identity = DynamicChatModelFactory.configuredModelIdentity(model);
        assertNotNull(identity);
        assertEquals(provider, identity.provider());
        assertNotNull(identity.endpointHash());
        assertEquals("chat:draft:" + provider + ":" + name,
                NamedChatModel.breakerKey(model, "llmrouter.api3", "llmrouter.api3", "draft"));
        assertEquals("chat:final:" + provider + ":" + name,
                NamedChatModel.breakerKey(model, "llmrouter.api3", "llmrouter.api3", "final"));
    }
}
