package com.example.lms.web;

import com.example.lms.search.TraceStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientOwnerKeyResolverTest {
    @Test void rateLimitIpHashIgnoresSpoofedForwardingAndUsesConfiguredTrustedProxy() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For","198.51.100.77");
        var resolver=new ClientOwnerKeyResolver();
        assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex("203.0.113.10"),resolver.clientIpHash(request));
        request.setRemoteAddr("127.0.0.1");
        var trusted=new ClientOwnerKeyResolver(request,new TrustedProxyPolicy("127.0.0.1/32"));
        assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex("198.51.100.77"),trusted.clientIpHash(request));
    }

    @Test
    void noServletRequestFallsBackToSystemOwnerKey() {
        ClientOwnerKeyResolver resolver = new ClientOwnerKeyResolver();

        assertEquals("system:no-request", resolver.ownerKey());
    }

    @Test
    void springContextWithoutServletRequestFallsBackToSystemOwnerKey() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ClientOwnerKeyResolver.class);
            context.refresh();

            assertEquals("system:no-request", context.getBean(ClientOwnerKeyResolver.class).ownerKey());
        }
    }

    @Test
    void ownerKeyCookieStillWinsWhenRequestIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(
                OwnerKeyBootstrapFilter.OWNER_KEY,
                "22222222-2222-4222-8222-222222222222"));
        ClientOwnerKeyResolver resolver = new ClientOwnerKeyResolver(request);

        assertEquals("22222222-2222-4222-8222-222222222222", resolver.ownerKey());
    }

    @Test
    void untrustedPeerCannotChooseOwnerThroughForwardedFor() {
        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.setRemoteAddr("203.0.113.10");
        spoofed.addHeader("User-Agent", "forwarded-owner-test");
        spoofed.addHeader("X-Forwarded-For", "198.51.100.77");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("203.0.113.10");
        direct.addHeader("User-Agent", "forwarded-owner-test");

        assertEquals(
                new ClientOwnerKeyResolver(direct).ownerKey(),
                new ClientOwnerKeyResolver(spoofed).ownerKey());
    }

    @Test
    void trustedPeerUsesOnlyTheFirstValidatedForwardedAddress() {
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("User-Agent", "trusted-forwarded-owner-test");
        proxied.addHeader("X-Forwarded-For", "198.51.100.77, 127.0.0.1");

        MockHttpServletRequest directClient = new MockHttpServletRequest();
        directClient.setRemoteAddr("198.51.100.77");
        directClient.addHeader("User-Agent", "trusted-forwarded-owner-test");

        assertEquals(
                new ClientOwnerKeyResolver(directClient).ownerKey(),
                new ClientOwnerKeyResolver(
                        proxied,
                        new TrustedProxyPolicy("127.0.0.1")).ownerKey());
    }

    @Test
    void trustedPeerRejectsMalformedFirstForwardedAddress() {
        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setRemoteAddr("127.0.0.1");
        malformed.addHeader("User-Agent", "trusted-forwarded-owner-test");
        malformed.addHeader("X-Forwarded-For", "not-an-ip, 198.51.100.77");

        MockHttpServletRequest directProxy = new MockHttpServletRequest();
        directProxy.setRemoteAddr("127.0.0.1");
        directProxy.addHeader("User-Agent", "trusted-forwarded-owner-test");

        assertEquals(
                new ClientOwnerKeyResolver(directProxy).ownerKey(),
                new ClientOwnerKeyResolver(
                        malformed,
                        new TrustedProxyPolicy("127.0.0.0/8")).ownerKey());
    }

    @Test
    void malformedIpv6ForwardedAddressFailsClosedWithRedactedReason() {
        TraceStore.clear();
        try {
            MockHttpServletRequest malformed = new MockHttpServletRequest();
            malformed.setRemoteAddr("127.0.0.1");
            malformed.addHeader("User-Agent", "trusted-forwarded-owner-test");
            malformed.addHeader("X-Forwarded-For", "1:2:3:4:5:6:7:8:9");

            MockHttpServletRequest directProxy = new MockHttpServletRequest();
            directProxy.setRemoteAddr("127.0.0.1");
            directProxy.addHeader("User-Agent", "trusted-forwarded-owner-test");

            assertEquals(
                    new ClientOwnerKeyResolver(directProxy).ownerKey(),
                    new ClientOwnerKeyResolver(
                            malformed,
                            new TrustedProxyPolicy("127.0.0.0/8")).ownerKey());
            assertEquals(
                    "invalid_ipv6",
                    TraceStore.getString("web.trustedProxy.validationFallbackReason"));
            assertEquals(
                    1L,
                    TraceStore.getLong("web.trustedProxy.validationFallbackCount"));
            assertFalse(TraceStore.getAll().toString().contains("1:2:3:4:5:6:7:8:9"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void invalidTrustedProxyConfigurationFailsWithFixedReason() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new TrustedProxyPolicy("not-a-proxy-address"));

        assertEquals("trusted_proxy_configuration_invalid", failure.getMessage());
    }

    @Test
    void fallbackOwnerKeyDoesNotAliasSupplementaryBoundaryToQuestionMark() {
        String prefix = "x".repeat(119);

        String supplementary = ownerKeyForUserAgent(prefix + "\uD83D\uDE00" + "tail");
        String replacement = ownerKeyForUserAgent(prefix + "?" + "tail");

        assertAll(
                () -> assertTrue(supplementary.startsWith("ipua:")),
                () -> assertNotEquals(replacement, supplementary));
    }

    @Test
    void fallbackOwnerKeyPreservesBmpAndCompletePairControls() {
        String bmpPrefix = "y".repeat(120);
        String pairPrefix = "q".repeat(118) + "\uD83D\uDE00";

        assertAll(
                () -> assertEquals(ownerKeyForUserAgent(bmpPrefix + "first"),
                        ownerKeyForUserAgent(bmpPrefix + "second")),
                () -> assertEquals(ownerKeyForUserAgent(pairPrefix + "first"),
                        ownerKeyForUserAgent(pairPrefix + "second")));
    }

    private static String ownerKeyForUserAgent(String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("User-Agent", userAgent);
        return new ClientOwnerKeyResolver(request).ownerKey();
    }
}
