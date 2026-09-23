package com.example.lms.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdHeaderFilterTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void missingRequestIdIsCreatedOnRequestResponseAndMdc() throws Exception {
        RequestIdHeaderFilter filter = new RequestIdHeaderFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get("x-request-id")));

        String rid = response.getHeader("X-Request-Id");
        assertNotNull(rid);
        assertEquals(rid, request.getAttribute("X-Request-Id"));
        assertEquals(rid, mdcDuringChain.get());
        assertNull(MDC.get("x-request-id"));
    }

    @Test
    void requestIdHeaderIsTrimmedBeforeResponseAndMdc() throws Exception {
        RequestIdHeaderFilter filter = new RequestIdHeaderFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", " rid-1 ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get("x-request-id")));

        assertEquals("rid-1", response.getHeader("X-Request-Id"));
        assertEquals("rid-1", request.getAttribute("X-Request-Id"));
        assertEquals("rid-1", mdcDuringChain.get());
        assertNull(MDC.get("x-request-id"));
    }
}
