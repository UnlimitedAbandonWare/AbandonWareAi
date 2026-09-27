package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.dto.SearchMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchDecisionServiceArithmeticTest {
    private final SearchDecisionService decisions = new SearchDecisionService();

    @Test void selfContainedArithmeticDoesNotTurnPunctuationIntoSearchIntent() {
        for (String query : List.of("2 더하기 2의 답을 숫자 한 개로만 알려줘", "2 더하기 2는 얼마야?",
                "2 + 2?", "9 빼기 4?", "7 x 8?", "12 나누기 3?", "1.5 + 2.5?")) {
            assertFalse(decisions.decide(query, SearchMode.AUTO, null, 3).shouldSearch(), query);
        }
    }

    @Test void externalContextAndExplicitWebIntentRemainSearchable() {
        for (String query : List.of("웹에서 2 더하기 2의 답을 찾아줘", "2 + 2 관련 최신 뉴스",
                "2 + 2 vs 4", "2 더하기 2의 공식 출처?", "2 달러 더하기 2 원은 얼마야?",
                "2 더하기 2 그리고 3 더하기 3은?")) {
            assertTrue(decisions.decide(query, SearchMode.AUTO, null, 3).shouldSearch(), query);
        }
    }

    @Test void explicitModesKeepTheirExistingPriority() {
        for (SearchMode mode : List.of(SearchMode.FORCE_LIGHT, SearchMode.FORCE_DEEP)) {
            assertTrue(decisions.decide("2 + 2?", mode, null, 3).shouldSearch());
        }
        assertFalse(decisions.decide("웹에서 최신 정보를 찾아줘", SearchMode.OFF, null, 3).shouldSearch());
    }
}
