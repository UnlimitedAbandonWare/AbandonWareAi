package com.example.lms.web.filter;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class GuardContextInitFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void hashesSessionHeaderBeforePuttingItIntoMdc() throws Exception {
        GuardContextInitFilter filter = new GuardContextInitFilter(debugEventsUnavailable());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "rid-raw-value");
        request.addHeader("X-Session-Id", "session-secret-123");
        AtomicReference<String> sessionDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                sessionDuringChain.set(MDC.get("sessionId")));

        assertEquals(SafeRedactor.hashValue("session-secret-123"), sessionDuringChain.get());
        assertNull(MDC.get("sessionId"));
    }

    @Test
    void trimsRequestIdHeaderBeforePuttingItIntoMdc() throws Exception {
        GuardContextInitFilter filter = new GuardContextInitFilter(debugEventsUnavailable());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", " rid-raw-value ");
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                requestIdDuringChain.set(MDC.get("x-request-id")));

        assertEquals("rid-raw-value", requestIdDuringChain.get());
        assertNull(MDC.get("x-request-id"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DebugEventStore> debugEventsUnavailable() {
        ObjectProvider<DebugEventStore> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
