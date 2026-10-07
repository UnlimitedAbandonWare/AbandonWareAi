package com.example.lms.api;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHarmonyScopedSentenceInstructionTest {
    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test
    void globalSentenceLimitKeepsTheDottedVersionAndItsQualification() {
        var meta = new LinkedHashMap<String, Object>();
        String answer = "검색 성공과 주장 검증은 별개입니다. Spring Boot 3.3.4의 실제 동작은 확인하지 않았습니다. 추가 설명입니다.";
        assertEquals("검색 성공과 주장 검증은 별개입니다. Spring Boot 3.3.4의 실제 동작은 확인하지 않았습니다.",
                ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(meta,
                        "검색과 검증의 차이를 두 문장으로 설명해줘.", answer));
        assertEquals(2, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
    }

    @Test
    void globalSentenceLimitKeepsADecimalValueWithinItsSentence() {
        String answer = "합성 예시의 확률은 0.25입니다. 다음 설명입니다.";
        assertEquals("합성 예시의 확률은 0.25입니다.",
                ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(new LinkedHashMap<>(),
                        "확률 예시를 한 문장으로 설명해줘.", answer));
    }

    @Test
    void globalSentenceLimitCountsAnUnterminatedSentenceAfterADecimalValue() {
        String answer = "합성 예시의 확률은 0.25입니다. 다음 설명입니다";
        assertEquals("합성 예시의 확률은 0.25입니다.",
                ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(new LinkedHashMap<>(),
                        "확률 예시를 한 문장으로 설명해줘.", answer));
    }

    @Test
    void quotedPerItemPhraseDoesNotCancelAnExplicitGlobalSentenceLimit() {
        assertEquals("첫 번째 설명입니다.", ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                new LinkedHashMap<>(), "문구 ‘한 문장씩’이 무슨 뜻인지 답변 전체를 한 문장으로 설명해줘.",
                "첫 번째 설명입니다. 두 번째 설명입니다."));
    }

    @Test
    void negatedParagraphInstructionDoesNotCancelAnExplicitGlobalLineLimit() {
        assertEquals("첫 번째 설명입니다.", ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                new LinkedHashMap<>(), "한 문단으로 요약하지 마. 마지막에 쓸 답변 전체를 한 줄로 설명해줘.",
                "첫 번째 설명입니다. 두 번째 설명입니다."));
    }

    @Test
    void perItemSentenceInstructionPreservesBothMisconceptionCorrections() {
        String query = "앞에서 내가 정정받은 오해 두 가지를 다시 말해줘. 새 검색 없이 이 대화에서 실제로 말한 오해만 짚고, 각각 올바른 설명을 한 문장씩 붙여줘.";
        String answer = "1. 검색 성공과 주장 검증은 별개입니다.\n2. 새 탭만으로 쿠키 격리가 보장되지는 않습니다.";
        assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                new LinkedHashMap<>(), query, answer));
    }

    @Test
    void finalLineInstructionDoesNotCollapseTheRequestedSummaryParagraph() {
        String query = "지금까지를 면접용 한 문단으로 요약해줘. 최신 조건과 확인되지 않은 일정을 구분해줘. 마지막에 계속 지킬 답변 기준을 한 줄로 적어줘.";
        String answer = "가상 프로젝트는 세이지이며 준비 시간은 20분입니다. 실제 면접 일정은 미확인입니다.\n기준: 사실과 제안을 구분합니다.";
        assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                new LinkedHashMap<>(), query, answer));
    }
}
