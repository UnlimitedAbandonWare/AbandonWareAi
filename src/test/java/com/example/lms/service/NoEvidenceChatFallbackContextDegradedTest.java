package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.rag.EvidenceAnswerComposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정합성 게이트 전량 탈락(무관 근거) 시 evidence fallback 분기가
 * 무관 문서 나열 대신 부적합 사실+세션 맥락을 유지한 degraded 응답을
 * 반환해야 한다 — 영상 3턴 실패의 마지막 관문.
 */
class NoEvidenceChatFallbackContextDegradedTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static String rejectedMarker() {
        // 실제 컴포저가 내보내는 부적합 응답을 만들어 마커 검출을 검증한다.
        EvidenceAnswerComposer composer = new EvidenceAnswerComposer();
        return composer.compose(
                "그럼 이제 어떡해 해야하냐?",
                List.of(new EvidenceAwareGuard.EvidenceDoc(
                        "https://humanrights.go.kr/edu",
                        "국가인권위원회 교육 자료",
                        "인권 교육 프로그램 구성과 신청 방법을 설명합니다.",
                        "https://humanrights.go.kr/edu")),
                false,
                "아이리 칸나");
    }

    @Test
    void rejectedEvidenceDegradesWithSessionContext() {
        String marker = rejectedMarker();
        assertTrue(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(marker), marker);

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "그럼 이제 어떡해 해야하냐?",
                "model-x",
                false,
                List.of("무관한 웹 문서"),
                List.of(),
                List.of(),
                marker,
                List.of(),
                "User: 아이리 칸나가 뭐냐?\nAssistant: 아이리 칸나는 버추얼 유튜버입니다.");

        assertTrue(result.content().contains("답변을 구성하기 어렵습니다"), result.content());
        assertTrue(result.content().contains("이전 대화 맥락"), result.content());
        assertTrue(result.content().contains("아이리 칸나는 버추얼 유튜버입니다"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("chat.llmFallback.evidenceRejected")));
        assertTrue(Boolean.TRUE.equals(TraceStore.get("chat.llmFallback.sessionContextApplied")));
    }

    @Test
    void rejectedEvidenceWithoutSessionStillTransparent() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "그럼 이제 어떡해 해야하냐?",
                "model-x",
                false,
                List.of("무관한 웹 문서"),
                List.of(),
                List.of(),
                rejectedMarker(),
                List.of(),
                null);

        assertTrue(result.content().contains("답변을 구성하기 어렵습니다"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("chat.llmFallback.evidenceRejected")));
    }

    @Test
    void alignedEvidenceFallbackAppendsSessionContext() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "그럼 이제 어떡해 해야하냐?",
                "model-x",
                false,
                List.of("근거 문서"),
                List.of(),
                List.of(),
                "검색 근거 기반 답변 본문",
                List.of(),
                "User: 아이리 칸나가 뭐냐?\nAssistant: 아이리 칸나는 버추얼 유튜버입니다.");

        assertTrue(result.content().contains("검색 근거 기반 답변 본문"), result.content());
        assertTrue(result.content().contains("이전 대화 맥락"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("chat.llmFallback.sessionContextApplied")));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("chat.llmFallback.evidenceRejected")));
    }
}
