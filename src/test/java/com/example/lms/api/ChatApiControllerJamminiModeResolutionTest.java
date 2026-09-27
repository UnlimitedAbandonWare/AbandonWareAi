package com.example.lms.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * X-Brave-Mode header bridge contract (plans/brave.v1.yaml when:
 * request.header.X-Brave-Mode == "on"). The header previously had no effect on
 * the live chat path because its only writers targeted dead request attributes
 * and an unconsumed NovaRequestContext flag.
 */
class ChatApiControllerJamminiModeResolutionTest {

    @Test
    void braveModeOnSelectsBraveLaneWhenJamminiAbsent() {
        assertEquals("brave", ChatApiController.resolveJamminiMode(null, "on"));
        assertEquals("brave", ChatApiController.resolveJamminiMode(null, "ON"));
        assertEquals("brave", ChatApiController.resolveJamminiMode("  ", "on"));
    }

    @Test
    void explicitJamminiModeWinsOverBraveMode() {
        assertEquals("brave", ChatApiController.resolveJamminiMode("brave", "on"));
        assertEquals("s1", ChatApiController.resolveJamminiMode("s1", "on"));
        assertEquals("zero_break", ChatApiController.resolveJamminiMode("zero_break", "on"));
    }

    @Test
    void nonOnBraveModeStaysInert() {
        assertNull(ChatApiController.resolveJamminiMode(null, null));
        assertNull(ChatApiController.resolveJamminiMode(null, "off"));
        assertNull(ChatApiController.resolveJamminiMode(null, "1"));
        assertNull(ChatApiController.resolveJamminiMode(null, "true"));
        assertEquals("", ChatApiController.resolveJamminiMode("", "off"));
    }
}
