package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션/대화 자체에 대한 후속 질문은 AUTO 일반 질문 추론의 웹 검색 발동을
 * 억제해야 한다 — "그럼 이제 어떡해 해야하냐?" 같은 발화가 무관한 웹 근거를
 * 끌어오는 3턴 회귀의 첫 관문이다.
 */
class SearchDecisionServiceSessionContextTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private SearchDecision decide(String query) {
        return new SearchDecisionService().decide(query, SearchMode.AUTO, null, 5);
    }

    @Test
    void sessionMetaFollowUpIsSuppressedFromGeneralQuestionSearch() {
        // 영상 3턴 실패 발화 그대로: "어떻게"가 들어가도 세션 맥락 질문이면 검색하지 않는다.
        SearchDecision decision = decide("오, 기억 세션 저장기능 잘 작동하네. 그럼 이제 어떻게 해야하냐?");

        assertFalse(decision.shouldSearch(), decision.reason());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("search.decision.sessionContextSuppressed")));
    }

    @Test
    void recentHistoryRecallQuestionIsSuppressed() {
        SearchDecision decision = decide("내가 방금 뭐라고 했냐?");

        assertFalse(decision.shouldSearch(), decision.reason());
    }

    @Test
    void priorTopicFollowUpIsSuppressed() {
        SearchDecision decision = decide("아까 말한 거랑 이어서 그럼 다음은 어떻게 해야 해?");

        assertFalse(decision.shouldSearch(), decision.reason());
    }

    @Test
    void freshEntityQuestionStillSearches() {
        // 맥락 지시어가 없는 새 주제 질문은 그대로 검색한다.
        SearchDecision decision = new SearchDecisionService().decide(
                "아이리 칸나가 뭐냐?", SearchMode.AUTO, null, 5);

        assertTrue(decision.shouldSearch(), decision.reason());
    }

    @Test
    void explicitWebLookupStillSearches() {
        SearchDecision decision = decide("웹에서 최신 Ollama 변경사항 찾아줘");

        assertTrue(decision.shouldSearch(), decision.reason());
    }

    @Test
    void memoryTopicQuestionWithoutSessionMarkerStillSearches() {
        // "기억" 단어가 있어도 후속/회상 표지가 없는 독립 질문은 검색 대상이다.
        SearchDecision decision = decide("기억력 좋아지는 방법이 뭐야?");

        assertTrue(decision.shouldSearch(), decision.reason());
    }
}
