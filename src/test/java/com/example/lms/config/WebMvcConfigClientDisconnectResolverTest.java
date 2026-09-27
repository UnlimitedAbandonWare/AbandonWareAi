package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebMvcConfigClientDisconnectResolverTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void clientDisconnectResolverHandlesAsyncRequestNotUsableBeforeDefaultResolver() {
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();
        new WebMvcConfig(null).extendHandlerExceptionResolvers(resolvers);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String raw = "ServletOutputStream failed token=dummy-client-disconnect-token";

        ModelAndView handled = resolvers.get(0).resolveException(
                request,
                response,
                null,
                new AsyncRequestNotUsableException(raw));

        assertNotNull(handled);
        assertEquals(204, response.getStatus());
        assertEquals(Boolean.TRUE, TraceStore.get("webmvc.clientDisconnect"));
        assertEquals("async_request_not_usable", TraceStore.get("webmvc.clientDisconnect.reason"));
        assertEquals(SafeRedactor.hashValue("/api/chat/stream"),
                TraceStore.get("webmvc.clientDisconnect.pathHash"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    @Test
    void clientDisconnectResolverLeavesOtherExceptionsForDefaultResolvers() {
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();
        new WebMvcConfig(null).extendHandlerExceptionResolvers(resolvers);

        ModelAndView handled = resolvers.get(0).resolveException(
                new MockHttpServletRequest("GET", "/api/chat/stream"),
                new MockHttpServletResponse(),
                null,
                new IllegalStateException("ordinary failure"));

        assertNull(handled);
    }
}
