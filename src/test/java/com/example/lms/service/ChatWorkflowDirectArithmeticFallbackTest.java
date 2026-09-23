package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatWorkflowDirectArithmeticFallbackTest {

    @Test
    void explicitNumberOnlyMultiplicationBypassesUnavailableModel() {
        assertEquals("9", ChatWorkflow.composeDirectLiteralAnswerFallback(
                "3\u00d73\uc740 \uc5bc\ub9c8\uc57c? \uc22b\uc790\ub9cc \ub2f5\ud574\uc918."));
    }

    @Test
    void koreanMultiplicationWordUsesDirectNumberOnlyAnswerPath() {
        assertEquals("391", ChatWorkflow.composeDirectLiteralAnswerFallback(
                "17 \uacf1\ud558\uae30 23\uc744 \uacc4\uc0b0\ud574. \ucd5c\uc885 \ub2f5\uc740 \uc22b\uc790\ub9cc \ud55c \uc904\ub85c \ub2f5\ud574."));
    }

    @Test
    void ordinaryArithmeticQuestionStillUsesTheNormalAnswerPath() {
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback("3\u00d73\uc740 \uc5bc\ub9c8\uc57c?"));
    }

    @Test
    void oversizedOperandIsNotTruncatedToTheNineDigitPrefix() {
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "2\u00d73000000000\uc740 \uc5bc\ub9c8\uc57c? \uc22b\uc790\ub9cc \ub2f5\ud574\uc918."));
    }

    @Test
    void trailingLatinSuffixIsNotTreatedAsACompleteOperand() {
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3x4abc, only the number"));
    }

    @Test
    void decimalOperandIsNotTruncatedToAnIntegerPrefix() {
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3x4.5, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3.5x4, only the number"));
    }

    @Test
    void chainedArithmeticIsNotTruncatedToTheFirstMultiplication() {
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3x4+5, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3+5x4, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3x4 + 5, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3 + 5x4, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate (3x4)+5, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3+(5x4), only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate ((3x4))+5, only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3+((5x4)), only the number"));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "17 \\uacf1\\ud558\\uae30 23 \\uacf1\\ud558\\uae30 2\\ub97c \\uacc4\\uc0b0\\ud574. \\uc22b\\uc790\\ub9cc \\ub2f5\\ud574."));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "17\\uacf1\\ud558\\uae3023\\uacf1\\ud558\\uae302\\ub97c \\uacc4\\uc0b0\\ud574. \\uc22b\\uc790\\ub9cc \\ub2f5\\ud574."));
    }

    @Test
    void explicitlyNegatedNumberOnlyInstructionUsesTheNormalPath() {
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3x4, do not answer with only the number"));
    }

    @Test
    void negatingWordsDoesNotNegateTheFollowingNumberOnlyInstruction() {
        assertEquals("12", ChatWorkflow.composeDirectLiteralAnswerFallback(
                "calculate 3x4, not words, only the number"));
    }
}
