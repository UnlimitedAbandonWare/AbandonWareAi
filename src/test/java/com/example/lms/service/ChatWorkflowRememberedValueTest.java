package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatWorkflowRememberedValueTest {

    @Test
    void compoundMisconceptionCorrectionDoesNotEchoThePreviousUserTurn() {
        String query = "내가 방금 “검색 결과만 있으면 답변의 사실검증도 통과한 셈”이라고 이해했어. "
                + "그 오해를 정정하고, 검색 성공과 주장 검증을 따로 볼 이유를 면접 답변처럼 두 문장으로 말해줘. "
                + "추가 검색은 하지 마.";
        String history = "User: 공식 문서에서 최소 Java 버전을 확인해줘.\nAssistant: 합성 문서 확인 응답";

        assertNull(ChatWorkflow.composeRecentHistoryFallback(query, history));
        assertEquals("직전 사용자 메시지: 공식 문서에서 최소 Java 버전을 확인해줘.\n\n출처: 세션 최근 기록",
                ChatWorkflow.composeRecentHistoryFallback("내가 방금 뭐라고 했어?", history));
    }

    @Test
    void scopedCompoundAssignmentDoesNotCaptureStoragePolicyAsValue() {
        assertNull(ChatWorkflow.composeCurrentTurnMemoryFallback(
                "이 대화에서만 시험 프로젝트 이름 해솔-42, 색상 청록, "
                        + "비교 기준 공식 자료 우선·확인 가능한 갱신일을 기억해줘. "
                        + "계정의 장기 기억에 저장할 필요는 없어."));
    }

    @Test
    void recognizedAssignmentWithStorageOptOutKeepsTheCompleteModelPath() {
        assertNull(ChatWorkflow.composeCurrentTurnMemoryFallback(
                "색상은 청록이야. 이 대화에서 기억해줘. 계정에 저장할 필요는 없어."));
    }

    @Test
    void storageOptOutAloneIsNotAMemoryAssignment() {
        assertNull(ChatWorkflow.composeCurrentTurnMemoryFallback(
                "계정의 장기 기억에 저장할 필요는 없어."));
    }

    @Test
    void recallCombinedWithCorrectionDoesNotShortCircuitTheUpdate() {
        assertNull(ChatWorkflow.composeRecentHistoryFallback(
                "방금 정한 프로젝트 이름·색상·비교 기준을 다시 말해줘. "
                        + "그리고 이 대화의 색상은 남색으로 정정해줘.",
                "User: 이 대화에서만 시험 프로젝트 이름 해솔-42, 색상 청록, "
                        + "비교 기준 공식 자료 우선·확인 가능한 갱신일을 기억해줘. "
                        + "계정의 장기 기억에 저장할 필요는 없어.\nAssistant: 합성 확인 응답"));
    }

    @Test
    void currentTurnMemoryFallbackIgnoresDirectiveNounBeforeCompoundLabel() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "SEED-C6C96043: \uD14C\uC2A4\uD2B8\uB85C \uAE30\uC5B5\uD574\uC918. "
                        + "\uC624\uB298\uC758 \uD655\uC778 \uB2E8\uC5B4\uB294 \uBE14\uB8E8\uB9DD\uACE0\uC57C.");

        assertEquals("\uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uBE14\uB8E8\uB9DD\uACE0\n"
                + "\uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uC744 \uAE30\uC900\uC73C\uB85C \uB2F5\uD558\uACA0\uC2B5\uB2C8\uB2E4.", answer);
    }

    @Test
    void currentTurnMemoryFallbackKeepsOrdinarySeedLabel() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "seed is barley. Remember this value for the next question.");

        assertEquals("Noted for this session: barley\n"
                + "Ask next and I will answer from recent session history.", answer);
    }
}
