package com.abandonware.ai.agent.job;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobRequestTest {

    @Test
    void constructorNormalizesBlankFlowAndCopiesPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "demo");

        JobRequest request = new JobRequest(" ", payload, " rid ", " sid ");
        payload.put("kind", "mutated");

        assertEquals("default", request.flow());
        assertEquals("demo", request.payload().get("kind"));
        assertEquals("rid", request.requestId());
        assertEquals("sid", request.sessionId());
        assertThrows(UnsupportedOperationException.class, () -> request.payload().put("other", true));
    }
}
