package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class ChatWorkflowFollowUpQueryPatternTest {

    @Test
    void freshSessionHypotheticalPromptDoesNotCompileCorruptedRegex() throws Exception {
        assertFalse(invokeFollowUp(
                "가상 시나리오: A는 10이 짝수라고 하고 B는 홀수라고 한다.",
                null));
    }

    @Test
    void independentQuestionDoesNotBecomeFollowUpOnlyBecausePriorAnswerExists() throws Exception {
        assertFalse(invokeFollowUp("프랑스의 수도는 어디야?", "이전 답변"));
        assertFalse(invokeFollowUp("Give me an example of a stable sorting algorithm.", "previous answer"));
        assertFalse(invokeFollowUp("Tell me more about quantum error correction.", "previous answer"));
        assertFalse(invokeFollowUp("안정 정렬의 예시를 들어줘.", "이전 답변"));
    }

    @Test
    void explicitFollowUpMarkerRequiresPriorAnswer() throws Exception {
        assertFalse(invokeFollowUp("좀 자세히 말해줘", null));
        assertFalse(invokeFollowUp("give me an example", null));
        assertTrue(invokeFollowUp("좀 자세히 말해줘", "이전 답변"));
        assertTrue(invokeFollowUp("give me an example", "previous answer"));
        assertTrue(invokeFollowUp("give me an example?", "previous answer"));
    }

    private static boolean invokeFollowUp(String query, String lastAnswer) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("isFollowUpQuery", String.class, String.class);
        method.setAccessible(true);
        Object result = assertDoesNotThrow(() -> method.invoke(null, query, lastAnswer));
        return Boolean.TRUE.equals(result);
    }
}
