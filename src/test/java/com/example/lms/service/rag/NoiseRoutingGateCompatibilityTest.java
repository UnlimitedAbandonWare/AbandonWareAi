package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NoiseRoutingGateCompatibilityTest {

    @Test
    void legacyStringDecisionDoesNotThrowOnPunctuationOnlyInput() {
        NoiseRoutingGate.Decision decision = assertDoesNotThrow(() -> NoiseRoutingGate.decideEscape("?!"));

        assertEquals(true, decision.escape());
        assertEquals("too_short", decision.reason());
    }

    @Test
    void legacyStringDecisionCountsHangulAsSignal() {
        NoiseRoutingGate.Decision decision = assertDoesNotThrow(
                () -> NoiseRoutingGate.decideEscape("\uC548\uB155\uD558\uC138\uC694"));

        assertFalse(decision.escape());
        assertEquals("ok", decision.reason());
    }
}
