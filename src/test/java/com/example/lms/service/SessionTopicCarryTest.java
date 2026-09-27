package com.example.lms.service;

import com.example.lms.service.subject.SubjectResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 세션 토픽 carry 결정론 테스트: 저엔티티 후속 발화에서 직전 사용자 발화의
 * 토픽을 추출하고, 현재 발화가 자체 엔티티를 가지면 carry하지 않는다.
 * SubjectResolver 는 KB/repo 미주입 상태로 순수 휴리스틱만 사용한다.
 */
class SessionTopicCarryTest {

    private final SubjectResolver resolver = new SubjectResolver(null);

    @Test
    void carryFindsPriorTopicForLowEntityFollowUp() {
        String history = String.join("\n",
                "User: 아이리 칸나가 뭐냐?",
                "Assistant: 아이리 칸나는 버추얼 유튜버입니다.",
                "User: 내가 방금 뭐라고 했냐?",
                "Assistant: 직전에 아이리 칸나가 뭐냐고 물으셨습니다.");

        String carried = ChatWorkflow.resolveCarriedSessionSubject(
                history, "오, 기억 세션 저장기능 잘 작동하네. 그럼 이제 어떡해 해야하냐?", null, resolver);

        // 회상 질문(마지막 User 라인)은 건너뛰고 그 앞 실제 토픽 발화에서 추출한다.
        assertEquals("아이리", carried);
    }

    @Test
    void carrySkippedWhenCurrentQueryHasQuotedEntity() {
        String history = "User: 아이리 칸나가 뭐냐?\nAssistant: 설명 생략";

        String carried = ChatWorkflow.resolveCarriedSessionSubject(
                history, "\"파이썬\" 설치법 알려줘", null, resolver);

        assertNull(carried, "인용구 자체 엔티티가 있으면 carry 미적용");
    }

    @Test
    void carrySkippedWhenHistoryMissing() {
        assertNull(ChatWorkflow.resolveCarriedSessionSubject(
                null, "그럼 이제 어떡해?", null, resolver));
        assertNull(ChatWorkflow.resolveCarriedSessionSubject(
                "", "그럼 이제 어떡해?", null, resolver));
        assertNull(ChatWorkflow.resolveCarriedSessionSubject(
                "   ", "그럼 이제 어떡해?", null, resolver));
    }

    @Test
    void carrySkippedWhenResolverMissing() {
        assertNull(ChatWorkflow.resolveCarriedSessionSubject(
                "User: 아이리 칸나가 뭐냐?", "그럼 이제 어떡해?", null, null));
    }

    @Test
    void carrySkipsCurrentEchoAndRecallLines() {
        String history = String.join("\n",
                "User: 아이리 칸나가 뭐냐?",
                "Assistant: 설명 생략",
                "User: 그럼 이제 어떡해 해야하냐?",
                "Assistant: 이전 맥락입니다",
                "User: 내가 방금 뭐라고 했냐?");

        String carried = ChatWorkflow.resolveCarriedSessionSubject(
                history, "그럼 이제 어떡해 해야하냐?", null, resolver);

        // 현재 발화 에코 라인과 회상 질문을 건너뛰어 첫 토픽 발화에서 추출한다.
        assertEquals("아이리", carried);
    }

    @Test
    void carryMissesWhenNoPriorUserTopic() {
        String history = "User: 내가 방금 뭐라고 했냐?\nAssistant: 확인 불가";

        String carried = ChatWorkflow.resolveCarriedSessionSubject(
                history, "그럼 이제 어떡해?", null, resolver);

        assertNull(carried);
    }

    @Test
    void priorUserPrefixLinesAreCarryCandidates() {
        // 컨텍스트 경로(interpretationHistory)의 "Prior user:" 라인도 후보다.
        String history = String.join("\n",
                "Prior user: 갤럭시 폴드7 가격이 얼마야?",
                "Prior assistant: 출시가 기준 안내",
                "User: 응 알겠어");

        String carried = ChatWorkflow.resolveCarriedSessionSubject(
                history, "그럼 이제 어떡해?", null, resolver);

        assertEquals("갤럭시", carried);
    }
}
