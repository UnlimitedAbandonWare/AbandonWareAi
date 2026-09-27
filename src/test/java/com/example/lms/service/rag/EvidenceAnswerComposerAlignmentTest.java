package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 근거 정합성 게이트 회귀: 질문/컨텍스트 주제와 무관한 근거는 채택하지 않고
 * NO_RELEVANT 응답으로 돌려보낸다. 영상의 국립국어원·동서대·인권위 문서가
 * "그럼 이제 어떡해 해야하냐?"에 답변으로 쓰이던 3턴 실패를 차단한다.
 */
class EvidenceAnswerComposerAlignmentTest {

    private final EvidenceAnswerComposer composer = new EvidenceAnswerComposer();

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static EvidenceAwareGuard.EvidenceDoc doc(String title, String snippet, String url) {
        return new EvidenceAwareGuard.EvidenceDoc(url, title, snippet, url);
    }

    private static List<EvidenceAwareGuard.EvidenceDoc> unrelatedDocs() {
        return List.of(
                doc("국립국어원 기억력 훈련 안내",
                        "기억력을 높이기 위한 훈련 프로그램과 교육 과정을 안내합니다.",
                        "https://korean.go.kr/memory-training"),
                doc("동서대학교 신입생 모집 요강",
                        "2026학년도 신입생 모집 일정과 전형 방법을 공지합니다.",
                        "https://dongseo.ac.kr/admission"),
                doc("국가인권위원회 교육 자료",
                        "인권 교육 프로그램의 구성과 신청 방법을 설명합니다.",
                        "https://humanrights.go.kr/education"));
    }

    @Test
    void unrelatedEvidenceIsRejectedForSessionFollowUp() {
        String answer = composer.compose(
                "오, 기억 세션 저장기능 잘 작동하네. 그럼 이제 어떡해 해야하냐?",
                unrelatedDocs(),
                false,
                "아이리 칸나");

        assertTrue(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer),
                "무관 근거는 채택되면 안 된다: " + answer);
        assertTrue(Boolean.TRUE.equals(
                TraceStore.get("evidenceAnswerComposer.alignmentRejected")));
    }

    @Test
    void relevantEvidenceStillComposes() {
        String answer = composer.compose(
                "아이리 칸나가 뭐냐?",
                List.of(doc("아이리 칸나 소개",
                        "아이리 칸나는 버추얼 유튜버로 활동하는 캐릭터입니다.",
                        "https://example.com/iri-kanna")),
                false);

        assertFalse(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer),
                "정합 근거는 답변으로 구성돼야 한다: " + answer);
        assertTrue(answer.contains("아이리 칸나"), answer);
    }

    @Test
    void contextSubjectAlignsDocWithoutKeywordHits() {
        // 저엔티티 후속 질문: 질문 키워드는 없지만 carry 주제와 맞는 근거는 통과한다.
        String answer = composer.compose(
                "그럼 이제 어떡해 해야하냐?",
                List.of(doc("아이리 칸나 활동 정리",
                        "아이리 칸나는 버추얼 유튜버로 게임 방송을 진행합니다.",
                        "https://example.com/iri-kanna-2")),
                false,
                "아이리 칸나");

        assertFalse(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer), answer);
    }

    @Test
    void singleWeakKeywordHitDoesNotAlign() {
        // 약한 키워드(2자) 단독 히트는 우연 일치로 보고 채택하지 않는다.
        String answer = composer.compose(
                "기억 세션 기능 설명",
                List.of(doc("기억력 훈련 프로그램",
                        "기억력 향상 훈련 과정을 소개합니다.",
                        "https://example.com/memory")),
                false,
                "기억 세션");

        assertTrue(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer), answer);
    }

    @Test
    void twoWeakKeywordHitsAlign() {
        String answer = composer.compose(
                "기억 세션 기능 설명",
                List.of(doc("기억 세션 관리",
                        "기억과 세션 데이터를 함께 관리하는 기능을 설명합니다.",
                        "https://example.com/memory-session")),
                false,
                "기억 세션");

        assertFalse(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer), answer);
    }

    @Test
    void unjudgeableQueryKeepsLegacyBehavior() {
        // 판정 근거가 없으면(주제 없음) 기존처럼 근거를 사용한다.
        String answer = composer.compose(
                "!",
                List.of(doc("무관 문서",
                        "임의의 검색 결과 스니펫입니다.",
                        "https://example.com/x")),
                false);

        assertFalse(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer), answer);
    }

    @Test
    void genericQueryWithoutSubjectKeepsLegacyBehavior() {
        // 주제 신호 없는 범용 질문은 키워드 비중첩만으로 거부하지 않는다
        // (session-56 계약: generic doc vs generic question 도 근거 나열).
        String answer = composer.compose(
                "기능 설명해줘",
                List.of(doc("실제 문서 제목",
                        "실제 근거가 되는 스니펫 본문입니다.",
                        "https://example.com/generic")),
                false);

        assertFalse(EvidenceAnswerComposer.isNoRelevantEvidenceAnswer(answer), answer);
    }
}
