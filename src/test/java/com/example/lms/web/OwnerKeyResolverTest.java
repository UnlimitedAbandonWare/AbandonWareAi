package com.example.lms.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerKeyResolverTest {
    private static final String OWNER_UUID = "22222222-2222-4222-8222-222222222222";

    @Test
    void canonicalOwnerKeyCookieWinsWhenPublicHeaderIsPresent() {
        OwnerKeyResolver resolver = new OwnerKeyResolver();
        MockHttpServletRequest request = request();
        request.addHeader("X-Owner-Key", "spoofed-owner");
        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, OWNER_UUID));

        assertEquals(OWNER_UUID, resolver.resolveOwnerKey(request));
    }

    @Test
    void publicOwnerKeyHeaderIsIgnoredByDefault() {
        OwnerKeyResolver resolver = new OwnerKeyResolver();
        MockHttpServletRequest request = request();
        request.addHeader("X-Owner-Key", "spoofed-owner");

        String ownerKey = resolver.resolveOwnerKey(request);

        assertNotEquals("spoofed-owner", ownerKey);
        assertTrue(ownerKey.startsWith("guest:"));
    }

    @Test
    void publicOwnerKeyHeaderRequiresExplicitOverrideFlag() {
        OwnerKeyResolver resolver = new OwnerKeyResolver();
        ReflectionTestUtils.setField(resolver, "headerOverrideEnabled", true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Owner-Key", "trusted-owner");

        assertEquals("trusted-owner", resolver.resolveOwnerKey(request));
    }

    @Test
    void unsafeOwnerKeyCookieFallsBackToGuestKey() {
        OwnerKeyResolver resolver = new OwnerKeyResolver();
        MockHttpServletRequest request = request();
        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, "cookie-owner"));

        String ownerKey = resolver.resolveOwnerKey(request);

        assertNotEquals("cookie-owner", ownerKey);
        assertTrue(ownerKey.startsWith("guest:"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "unit-test-browser");
        return request;
    }
}
