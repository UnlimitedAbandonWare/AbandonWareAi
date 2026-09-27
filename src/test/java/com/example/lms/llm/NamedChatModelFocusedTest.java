package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NamedChatModel 계약 focused 테스트 — 익명 래퍼가 simpleName=""로 붕괴해
 * chat:draft:unknown 브레이커 하나를 공유하던 결함의 회귀 방지.
 */
class NamedChatModelFocusedTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static ChatModel anonymousChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return null;
            }
        };
    }

    private static NamedChatModel namedWrapper(String name, ChatModel delegate) {
        return new NamedChatModel() {
            @Override
            public String resolvedModelName() {
                String resolved = NamedChatModel.resolve(delegate);
                return resolved != null ? resolved : name;
            }

            @Override
            public ChatResponse doChat(ChatRequest request) {
                return null;
            }
        };
    }

    @Test
    void declaredNameWinsOverAnonymousSimpleNameCollapse() {
        ChatModel wrapper = namedWrapper("qwen3:8b", null);
        // 익명 래퍼의 getSimpleName()은 "" — 선언된 모델명이 우선해야 한다.
        assertEquals("", wrapper.getClass().getSimpleName());
        assertEquals("qwen3:8b", NamedChatModel.resolve(wrapper));
    }

    @Test
    void resolveDelegatesThroughWrapperChain() {
        ChatModel leaf = namedWrapper("gemma3:27b", null);
        ChatModel outer = namedWrapper(null, leaf);
        assertEquals("gemma3:27b", NamedChatModel.resolve(outer));
    }

    @Test
    void plainAnonymousModelResolvesToNullSoCallerFallsThrough() {
        assertNull(NamedChatModel.resolve(anonymousChatModel()));
        assertNull(NamedChatModel.resolve(null));
    }

    @Test
    void breakerTagKeepsConcreteResolvedNameUntouched() {
        ChatModel model = namedWrapper("qwen3:8b", null);
        assertEquals("qwen3:8b",
                NamedChatModel.breakerTag(model, "qwen3:8b", "other-model"));
        assertNull(TraceStore.get("chat.breaker.keyCollapsed"));
    }

    @Test
    void breakerTagRescuesCollapsedIdentityWithRequestedModelId() {
        ChatModel anonymous = anonymousChatModel();
        // resolved="" (익명 simpleName 붕괴) → 요청 모델 ID로만 복구한다.
        assertEquals("qwen3:8b",
                NamedChatModel.breakerTag(anonymous, "", "qwen3:8b"));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.breaker.keyCollapsed"));
    }

    @Test
    void breakerTagNeverAdoptsSentinelRequestedValues() {
        ChatModel anonymous = anonymousChatModel();
        for (String sentinel : new String[] {"auto", "default", "*", "", "  "}) {
            TraceStore.clear();
            // 센티널은 복구 대상이 아니다 — 붕괴 상태를 그대로 보고한다.
            assertEquals("unknown",
                    NamedChatModel.breakerTag(anonymous, "unknown", sentinel));
        }
    }

    /** named(비익명) 클래스 — simpleName이 실제로 존재하는 래퍼 시뮬레이션. */
    private static final class NamedStubModel implements NamedChatModel {
        @Override
        public String resolvedModelName() {
            return "stub-model";
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return null;
        }
    }

    @Test
    void breakerTagTreatsBareClassNameAsCollapsedIdentity() {
        ChatModel model = new NamedStubModel();
        // resolved가 클래스 단순명이면 모델 ID가 아니라 붕괴 신호다.
        String tag = NamedChatModel.breakerTag(model,
                model.getClass().getSimpleName(),
                "gemma3:27b");
        assertEquals("gemma3:27b", tag);
    }

    @Test
    void usableModelTagRejectsBlankSentinelWhitespaceAndOverflow() {
        assertFalse(NamedChatModel.isUsableModelTag(null));
        assertFalse(NamedChatModel.isUsableModelTag(""));
        assertFalse(NamedChatModel.isUsableModelTag("auto"));
        assertFalse(NamedChatModel.isUsableModelTag("AUTO"));
        assertFalse(NamedChatModel.isUsableModelTag("has space"));
        assertFalse(NamedChatModel.isUsableModelTag("x".repeat(201)));
        assertTrue(NamedChatModel.isUsableModelTag("qwen3:8b"));
        assertTrue(NamedChatModel.isUsableModelTag("llmrouter.local"));
    }
}
