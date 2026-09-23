package com.example.lms.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VectorAdminTokenFilterTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void queryTokenIsRejected() throws Exception {
        VectorAdminTokenFilter filter = new VectorAdminTokenFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/vector/flush");
        request.addParameter("token", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void headerTokenIsAccepted() throws Exception {
        VectorAdminTokenFilter filter = new VectorAdminTokenFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/vector/flush");
        request.addHeader(VectorAdminTokenFilter.HEADER_TOKEN, "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void brainStateAdminPathUsesVectorAdminTokenBoundary() throws Exception {
        VectorAdminTokenFilter filter = new VectorAdminTokenFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/vector/brain/ingest");
        request.addHeader(VectorAdminTokenFilter.HEADER_TOKEN, "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
