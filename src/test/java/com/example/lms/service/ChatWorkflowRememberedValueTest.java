package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatWorkflowRememberedValueTest {

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
