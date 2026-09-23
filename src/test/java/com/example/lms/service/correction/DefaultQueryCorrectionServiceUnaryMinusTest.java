package com.example.lms.service.correction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultQueryCorrectionServiceUnaryMinusTest {

    private final DefaultQueryCorrectionService service = new DefaultQueryCorrectionService();

    @Test
    void preservesLeadingUnaryMinusBeforeNumber() {
        assertEquals("-42 °C", service.correct("−42 °C"));
        assertEquals("-42 °C", service.correct("-42 °C"));
    }

    @Test
    void stillRemovesNonUnaryLeadingHyphen() {
        assertEquals("temperature", service.correct("-temperature"));
    }
}
