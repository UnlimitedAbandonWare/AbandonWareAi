package com.example.lms.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerKeyBootstrapFilterTest {
    private static final String OWNER_UUID = "22222222-2222-4222-8222-222222222222";

    @Test
    void newOwnerCookieEmitsSingleSetCookieHeader() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        String header = headers.get(0);
        assertTrue(header.contains(OwnerKeyBootstrapFilter.OWNER_KEY + "="));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
    }

    @Test
    void existingOwnerCookieRefreshEmitsSingleSetCookieHeader() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, OWNER_UUID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        assertTrue(headers.get(0).contains(OwnerKeyBootstrapFilter.OWNER_KEY + "=" + OWNER_UUID));
    }

    @Test
    void cookieLessRequestUsesTheSameOwnerEmittedByTheFilter() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/sessions");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("User-Agent", "owner-continuity-test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> resolvedInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> resolvedInsideChain.set(
                new ClientOwnerKeyResolver((HttpServletRequest) req).ownerKey());

        filter.doFilter(request, response, chain);

        String emittedOwner = cookieValue(response.getHeader("Set-Cookie"));
        assertEquals(emittedOwner, resolvedInsideChain.get());
    }

    @Test
    void rawForwardedHttpsDoesNotMarkOwnerCookieSecure() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        assertFalse(headers.get(0).contains("; Secure"));
    }

    @Test
    void containerSecurityStateMarksOwnerCookieSecure() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertTrue(response.getHeader("Set-Cookie").contains("; Secure"));
    }

    @Test
    void unsafeOwnerCookieIsRotated() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, "existing-owner"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        String header = headers.get(0);
        assertTrue(header.contains(OwnerKeyBootstrapFilter.OWNER_KEY + "="));
        assertTrue(header.matches(".*" + OwnerKeyBootstrapFilter.OWNER_KEY
                + "=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*"));
        assertTrue(!header.contains("existing-owner"));
    }

    private static String cookieValue(String setCookie) {
        int equals = setCookie.indexOf('=');
        int separator = setCookie.indexOf(';', equals + 1);
        return setCookie.substring(equals + 1, separator < 0 ? setCookie.length() : separator);
    }
}
