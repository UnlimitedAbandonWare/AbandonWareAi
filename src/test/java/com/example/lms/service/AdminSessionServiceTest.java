package com.example.lms.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSessionServiceTest {

    @Test
    void configuredSecretIssuesValidAdminCookie() {
        AdminSessionService service = serviceWithSecret("configured-admin-session-secret-32b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issueToken(response, "admin");

        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        Cookie cookie = adminCookieFromHeader(setCookie);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertTrue(service.isValid(new Cookie[] {cookie}));
    }

    @Test
    void missingSecretDoesNotIssueAdminCookie() {
        AdminSessionService service = serviceWithSecret("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issueToken(response, "admin");

        assertNull(response.getCookie("admin-token"));
    }

    @Test
    void placeholderSecretDoesNotIssueAdminCookie() {
        AdminSessionService service = serviceWithSecret("changeme");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issueToken(response, "admin");

        assertNull(response.getCookie("admin-token"));
    }

    @Test
    void missingSecretDoesNotValidateExistingCookie() {
        AdminSessionService issuer = serviceWithSecret("configured-admin-session-secret-32b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        issuer.issueToken(response, "admin");

        AdminSessionService verifier = serviceWithSecret("");

        assertFalse(verifier.isValid(new Cookie[] {adminCookieFromHeader(response.getHeader("Set-Cookie"))}));
    }

    private static AdminSessionService serviceWithSecret(String secret) {
        AdminSessionService service = new AdminSessionService();
        ReflectionTestUtils.setField(service, "secretKey", secret);
        return service;
    }

    private static Cookie adminCookieFromHeader(String setCookie) {
        assertNotNull(setCookie);
        int valueStart = "admin-token=".length();
        int valueEnd = setCookie.indexOf(';');
        Cookie cookie = new Cookie("admin-token", setCookie.substring(valueStart, valueEnd));
        cookie.setHttpOnly(setCookie.contains("HttpOnly"));
        cookie.setSecure(setCookie.contains("Secure"));
        return cookie;
    }
}
