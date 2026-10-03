package com.example.lms.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class InterviewDemoOwnedRoutesTest {
    @ParameterizedTest
    @CsvSource({"POST,/api/chat/stream", "POST,/api/chat/cancel", "POST,/api/chat/ack",
            "GET,/api/chat/state", "GET,/api/chat/sessions", "GET,/api/chat/sessions/7", "POST,/api/chat/sync"})
    void onlyNamedRoutesReachTheirExistingOwnerGuards(String method, String path) throws Exception {
        assertEquals(204, request(method, path, "127.0.0.1", "http://localhost:18180"));
    }
    @ParameterizedTest
    @CsvSource({"DELETE,/api/chat/sessions/7", "POST,/api/chat/sessions", "GET,/api/chat/sessions/7/traces/x/html",
            "POST,/api/chat/attachments", "POST,/api/chat/transcribe", "POST,/api/chat/models/install",
            "POST,/api/chat/models/recheck", "POST,/api/chat/cluster/cancel", "POST,/api/rag/query",
            "POST,/api/rag/probe", "GET,/api/chat/ui-heartbeat", "GET,/api/chat/sessions/7/extra"})
    void otherRoutesRemainNotFound(String method, String path) throws Exception {
        assertEquals(404, request(method, path, "127.0.0.1", null));
    }
    @ParameterizedTest
    @CsvSource({"POST,/api/chat/stream", "POST,/api/chat/cancel", "POST,/api/chat/ack",
            "GET,/api/chat/state", "GET,/api/chat/sessions", "GET,/api/chat/sessions/7"})
    void ownedRoutesDoNotRelaxPeerOrOrigin(String method, String path) throws Exception {
        assertEquals(403, request(method, path, "203.0.113.10", null));
        assertEquals(403, request(method, path, "127.0.0.1", "https://foreign.example"));
    }
    private int request(String method, String path, String peer, String origin) throws Exception {
        var req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(peer); req.setScheme("http"); req.setServerName("localhost"); req.setServerPort(18180);
        if (origin != null) req.addHeader("Origin", origin);
        var res = new MockHttpServletResponse();
        new ChatOpenSecurityConfig.InterviewDemoFilter().doFilter(req, res,
                (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        return res.getStatus();
    }
}
