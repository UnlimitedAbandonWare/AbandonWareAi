package com.abandonware.ai.agent.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StepTest {

    @Test
    void setArgsDefensivelyCopiesInputMap() {
        Step step = new Step();
        Map<String, Object> args = new HashMap<>();
        args.put("query", "original");

        step.setArgs(args);
        args.put("query", "mutated");

        assertEquals("original", step.getArgs().get("query"));
    }

    @Test
    void retrySettingsNormalizeInvalidValues() {
        Step.Retry retry = new Step.Retry();

        retry.setMaxAttempts(-4);
        retry.setInitialMs(-10);
        retry.setMode(" exp ");

        assertEquals(1, retry.getMaxAttempts());
        assertEquals(0L, retry.getInitialMs());
        assertEquals("EXP", retry.getMode());

        retry.setMode(" ");
        assertEquals("FIXED", retry.getMode());
        assertFalse(retry.getMode().isBlank());
    }
}
