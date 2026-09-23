package com.example.lms.service.verbosity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerbosityDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "대한민국의 수도는 어디인가요? 도시 이름만 답해주세요.",
            "대한민국의 수도를 도시 이름 한 단어만 답해줘.",
            "정답을 한 단어로만 대답해주세요.",
            "Answer in one word only.",
            "Reply with a single word."
    })
    void explicitNameOrSingleWordAnswerDoesNotRequireExpansion(String query) throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile profile = detector.detect(query);

        assertEquals("brief", profile.hint());
        assertEquals(0, profile.minWordCount());
        assertEquals(160, profile.targetTokenBudgetOut());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "이름만 답하지 말고 그 이유도 설명해줘.",
            "한 단어로만 대답하지 마세요. 이유를 설명해주세요.",
            "도시 이름만 답해주세요. 역사도 함께 설명해주세요.",
            "'도시 이름만 답해주세요'라는 지시의 문제점을 설명해줘.",
            "Do not answer in one word only.",
            "Reply with a single word per item and explain each item."
    })
    void negatedQuotedOrExpandedNameInstructionsKeepStandardProfile(String query) throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minStd", 250);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile profile = detector.detect(query);

        assertEquals("standard", profile.hint());
        assertEquals(250, profile.minWordCount());
        assertEquals(1000, profile.targetTokenBudgetOut());
    }

    @Test
    void oneSentenceInstructionUsesCompactBriefProfileWithoutExpansionFloor() throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile korean = detector.detect("1+1은? 한 문장으로만 답해줘.");
        VerbosityProfile english = detector.detect("Answer in one sentence only.");

        assertEquals("brief", korean.hint());
        assertEquals(0, korean.minWordCount());
        assertEquals(160, korean.targetTokenBudgetOut());
        assertEquals("brief", english.hint());
        assertEquals(0, english.minWordCount());
        assertEquals(160, english.targetTokenBudgetOut());
    }

    @Test
    void twoSentenceInstructionUsesCompactBriefProfileWithoutExpansionFloor() throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile korean = detector.detect("서로의 반례를 검증하고 두 문장으로 판정해줘.");
        VerbosityProfile english = detector.detect("Judge the counterexamples in two sentences.");

        assertEquals("brief", korean.hint());
        assertEquals(0, korean.minWordCount());
        assertEquals(160, korean.targetTokenBudgetOut());
        assertEquals("brief", english.hint());
        assertEquals(0, english.minWordCount());
        assertEquals(160, english.targetTokenBudgetOut());
    }

    @Test
    void numericOnlyAndOneLineInstructionsUseCompactProfileWithoutExpansionFloor() throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile koreanNumber = detector.detect("156만, 숫자만 답해줘.");
        VerbosityProfile englishNumber = detector.detect("Answer only the number.");
        VerbosityProfile englishLine = detector.detect("Summarize this in one line.");

        for (VerbosityProfile profile : java.util.List.of(koreanNumber, englishNumber, englishLine)) {
            assertEquals("brief", profile.hint());
            assertEquals(0, profile.minWordCount());
            assertEquals(160, profile.targetTokenBudgetOut());
        }
    }

    @Test
    void explicitResultOnlyCommandUsesCompactProfileWithoutExpansionFloor() throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile profile = detector.detect("12+8을 계산하고 결과만 말해줘.");

        assertEquals("brief", profile.hint());
        assertEquals(0, profile.minWordCount());
        assertEquals(160, profile.targetTokenBudgetOut());
    }

    @Test
    void resultScopeAndNegatedResultOnlyCommandStayStandard() throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        VerbosityProfile resultScope = detector.detect("검색 결과만 보여줘.");
        VerbosityProfile negatedCommand = detector.detect("결과만 말하지 말고 풀이도 보여줘.");
        VerbosityProfile trailingConflict = detector.detect("결과만 말해줘. 풀이도 함께 설명해줘.");

        for (VerbosityProfile profile : java.util.List.of(resultScope, negatedCommand, trailingConflict)) {
            assertEquals("standard", profile.hint());
            assertEquals(250, profile.minWordCount());
            assertEquals(1000, profile.targetTokenBudgetOut());
        }
    }

    @Test
    void negatedOrPerItemCompactPhrasesStayStandard() throws Exception {
        VerbosityDetector detector = new VerbosityDetector();
        setInt(detector, "minBrief", 120);
        setInt(detector, "minStd", 250);
        setInt(detector, "tokBrief", 800);
        setInt(detector, "tokStd", 1000);

        for (String query : java.util.List.of(
                "Answer not only the number; explain.",
                "Do not give only the number; explain.",
                "Only the number is insufficient; explain.",
                "One sentence is insufficient; explain thoroughly.",
                "One sentence isn't enough; explain thoroughly.",
                "Use one line per item, with detailed reasoning.",
                "Do not answer in one sentence; explain thoroughly.")) {
            VerbosityProfile profile = detector.detect(query);
            assertEquals("standard", profile.hint(), query);
            assertEquals(250, profile.minWordCount(), query);
            assertEquals(1000, profile.targetTokenBudgetOut(), query);
        }
    }

    private static void setInt(VerbosityDetector detector, String fieldName, int value) throws Exception {
        var field = VerbosityDetector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(detector, value);
    }
}
