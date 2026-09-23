package com.example.lms.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatWorkflowLiteralRoutingRegressionTest {

    @ParameterizedTest(name = "[{index}] {0} is a generic output label")
    @ValueSource(strings = {"TOKEN", "CODE", "VALUE", "WORD"})
    void genericBareOutputLabelDoesNotBecomeLiteralAnswer(String label) {
        String query = label + "\uB9CC \uC815\uD655\uD788 \uCD9C\uB825\uD574";

        assertAll(
                () -> assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query), label),
                () -> assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query), label));
    }

    @Test
    void alphabeticLiteralValueRemainsDirect() {
        String query = "READY\uB9CC \uC815\uD655\uD788 \uB2F5\uD574.";

        assertEquals("READY", ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void explicitlyNamedGenericWordRemainsDirect() {
        String query = "TOKEN\uB77C\uACE0 \uB2F5\uD574\uC918.";

        assertEquals("TOKEN", ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void namedMachineTokenRemainsDirect() {
        String query = "\uC124\uBA85\uC744 \uCD94\uAC00\uD558\uC9C0 \uB9D0\uACE0 ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals("GPU3060-PROBE", ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }
}
