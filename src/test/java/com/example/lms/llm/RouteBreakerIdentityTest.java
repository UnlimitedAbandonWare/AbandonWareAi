package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RouteBreakerIdentityTest {
    @AfterEach void clearTrace() { TraceStore.clear(); }

    @SuppressWarnings("unchecked")
    private static Map<ChatModel, DynamicChatModelFactory.ConfiguredModelIdentity> identities() {
        return (Map<ChatModel, DynamicChatModelFactory.ConfiguredModelIdentity>)
                ReflectionTestUtils.getField(DynamicChatModelFactory.class, "CONFIGURED_MODEL_IDS");
    }

    @Test void routeTagUsesIdentityOfThatExactClient() {
        ChatModel selected = mock(ChatModel.class);
        identities().put(selected, new DynamicChatModelFactory.ConfiguredModelIdentity(
                "openai/gpt-oss-120b", "groq", "openai_chat_completions", null, null));
        try {
            assertEquals("groq:openai/gpt-oss-120b",
                    NamedChatModel.breakerTag(selected, "llmrouter.api3", "llmrouter.api3"));
            assertNull(TraceStore.get("chat.breaker.keyCollapsed"));
        } finally { identities().remove(selected); }
    }

    @Test void draftAndFinalHaveSeparateKeysForTheSameActualModel() {
        ChatModel selected = mock(ChatModel.class);
        identities().put(selected, new DynamicChatModelFactory.ConfiguredModelIdentity(
                "openai/gpt-oss-120b", "groq", null, null, null));
        try {
            String draft = NamedChatModel.breakerKey(selected, "llmrouter.api3", "llmrouter.api3", "draft");
            String finalKey = NamedChatModel.breakerKey(selected, "llmrouter.api3", "llmrouter.api3", "final");
            assertEquals("chat:draft:groq:openai_gpt-oss-120b", draft);
            assertEquals("chat:final:groq:openai_gpt-oss-120b", finalKey);
            assertNotEquals(draft, finalKey);
            assertEquals(draft, NamedChatModel.breakerKey(selected, "openai/gpt-oss-120b", "auto", "draft"));
        } finally { identities().remove(selected); }
    }

    @Test void anotherClientsIdentityDoesNotLeakIntoUnresolvedRoute() {
        ChatModel other = mock(ChatModel.class);
        ChatModel unresolved = mock(ChatModel.class);
        identities().put(other, new DynamicChatModelFactory.ConfiguredModelIdentity(
                "other-model", "other-provider", null, null, null));
        TraceStore.put("llm.factory.model", "other-model");
        try {
            assertEquals("llmrouter.api3",
                    NamedChatModel.breakerTag(unresolved, "llmrouter.api3", "llmrouter.api3"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.breaker.keyCollapsed"));
        } finally { identities().remove(other); }
    }

    @Test void concreteNamedDelegateRecoversRouteWithoutProviderGuess() {
        NamedChatModel selected = mock(NamedChatModel.class);
        org.mockito.Mockito.when(selected.resolvedModelName()).thenReturn("selected-model");
        assertEquals("selected-model",
                NamedChatModel.breakerTag(selected, "llmrouter.api3", "llmrouter.api3"));
    }
}
