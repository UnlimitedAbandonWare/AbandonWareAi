package com.example.lms.service;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 56 회귀: 무근거 분기에서 (1) 호출자가 조립한 폴백 본문이 버려지지 않고
 * (2) 세션 대화 스냅샷이 응답에 반영돼야 한다.
 */
class NoEvidenceChatFallbackSessionContextTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void noEvidenceBranchKeepsCallerComposedFallback() {
        // 호출자가 만든 맥락 인지 폴백 본문이 무근거 분기에서 폐기되면 안 된다.
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "와, 그걸 어떡해..",
                "model-x",
                false,
                List.of(),
                List.of(),
                List.of(),
                "직전 맥락 기반 답변 본문",
                List.of(),
                "User: 첫 질문\nAssistant: 첫 답변");

        assertTrue(result.content().contains("직전 맥락 기반 답변 본문"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void noEvidenceBranchAppendsSessionContextLine() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "와, 그걸 어떡해..",
                "model-x",
                false,
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(),
                "User: 첫 질문\nAssistant: 첫 답변");

        assertTrue(result.content().contains("로컬 안전 응답"), result.content());
        assertTrue(result.content().contains("첫 답변"), result.content());
        assertTrue(Boolean.TRUE.equals(
                TraceStore.get("chat.llmFallback.sessionContextApplied")));
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void noEvidenceWithoutSessionContextKeepsGenericBody() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "오늘 대화 가능한 상태인지 알려줘",
                "model-x",
                false,
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(),
                null);

        assertTrue(result.content().contains("로컬 안전 응답"), result.content());
        assertFalse(Boolean.TRUE.equals(
                TraceStore.get("chat.llmFallback.sessionContextApplied")));
    }

    @Test
    void sessionContextDoesNotOverrideHistoryAwareCallerAnswer() {
        // recent-history 폴백 본문이 이미 맥락 답변이면 그대로 유지한다.
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "내가 방금 뭐라고 했냐?",
                "model-x",
                false,
                List.of(),
                List.of(),
                List.of(),
                "직전에 \"세션 56 컨텍스트 실험\"이라고 말씀하셨습니다.",
                List.of(),
                "User: 세션 56 컨텍스트 실험\nAssistant: 첫 답변");

        assertTrue(result.content().contains("세션 56 컨텍스트 실험"), result.content());
    }
}
