package com.abandonware.ai.agent;

import com.abandonware.ai.agent.consent.ConsentCardRenderer;
import com.abandonware.ai.agent.consent.ConsentExceptionHandler;
import com.abandonware.ai.agent.consent.ConsentRequiredException;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.context.ContextBridge;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;



import static org.junit.jupiter.api.Assertions.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.ConsentExceptionHandlerTest
 * Role: config
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.agent.consent.ConsentCardRenderer, com.abandonware.ai.agent.consent.ConsentExceptionHandler, com.abandonware.ai.agent.consent.ConsentRequiredException, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.ConsentExceptionHandlerTest
role: config
flags: [sse]
*/
public class ConsentExceptionHandlerTest {

    @Test
    void returnsBasicCardJsonAnd403() {
        ConsentExceptionHandler handler = new ConsentExceptionHandler(new ConsentCardRenderer(), new ContextBridge());
        ConsentRequiredException ex = new ConsentRequiredException(java.util.List.of(ToolScope.KAKAO_PUSH));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Session-Id", "sess-1");
        req.addHeader("X-Kakao-Room-Id", "room-1");

        ResponseEntity<String> resp = handler.handle(ex, req);
        assertEquals(403, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("basicCard"));
        assertTrue(resp.getBody().contains("sess-1"));
        assertTrue(resp.getBody().contains("room-1"));
    }
}