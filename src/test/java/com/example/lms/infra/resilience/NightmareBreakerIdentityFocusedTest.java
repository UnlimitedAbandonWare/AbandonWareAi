package com.example.lms.infra.resilience;

import com.example.lms.llm.NamedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chat:draft 브레이커 키의 모델별 분리 회귀 방지 —
 * 서로 다른 모델이 chat:draft:unknown 하나를 공유하면 한 모델의 장애가
 * 다른 모델 호출까지 차단한다.
 */
class NightmareBreakerIdentityFocusedTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static ChatModel anonymousNamedModel(String name) {
        return new NamedChatModel() {
            @Override
            public String resolvedModelName() {
                return name;
            }

            @Override
            public ChatResponse doChat(ChatRequest request) {
                return null;
            }
        };
    }

    @Test
    void chatDraftKeySeparatesModelsAndKeepsUnknownSentinelForBlank() {
        String a = NightmareKeys.chatDraftKey("qwen3:8b");
        String b = NightmareKeys.chatDraftKey("gemma3:27b");
        assertNotEquals(a, b);
        assertEquals("chat:draft:qwen3:8b", a);
        assertEquals("chat:draft:unknown", NightmareKeys.chatDraftKey(null));
        assertEquals("chat:draft:unknown", NightmareKeys.chatDraftKey("   "));
        // 키에 들어갈 수 없는 문자는 정규화된다.
        assertEquals("chat:draft:model_x", NightmareKeys.chatDraftKey("model x"));
    }

    @Test
    void namedWrapperIdentityFlowsIntoDistinctDraftKeys() {
        ChatModel qwen = anonymousNamedModel("qwen3:8b");
        ChatModel gemma = anonymousNamedModel("gemma3:27b");
        String qwenKey = NightmareKeys.chatDraftKey(
                NamedChatModel.breakerTag(qwen, NamedChatModel.resolve(qwen), "auto"));
        String gemmaKey = NightmareKeys.chatDraftKey(
                NamedChatModel.breakerTag(gemma, NamedChatModel.resolve(gemma), "auto"));
        assertEquals("chat:draft:qwen3:8b", qwenKey);
        assertEquals("chat:draft:gemma3:27b", gemmaKey);
        assertNotEquals(qwenKey, gemmaKey);
    }

    @Test
    void collapsedIdentityRescuesToRequestedModelInsteadOfSharedUnknown() {
        ChatModel anonymous = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return null;
            }
        };
        // 익명 클래스 → resolve 불가 → resolved 붕괴 → 요청 모델로 복구.
        String tag = NamedChatModel.breakerTag(anonymous, "unknown", "qwen3:8b");
        assertEquals("chat:draft:qwen3:8b", NightmareKeys.chatDraftKey(tag));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.breaker.keyCollapsed"));
        assertTrue(String.valueOf(
                TraceStore.get("chat.breaker.keyCollapsed.resolvedHash")).startsWith("hash:"));
    }
}
