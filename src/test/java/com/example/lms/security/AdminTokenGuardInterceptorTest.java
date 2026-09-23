package com.example.lms.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTokenGuardInterceptorTest {

    @Test
    void prodWithoutConfiguredTokenDenies() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("", "", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void wrongTokenDenies() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "local");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void adminTokenHeaderAccepts() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void headerAuthenticationIssuesReusableNonRawSessionCookie() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        Cookie sessionCookie = responseCookie(setCookie);
        assertNotEquals("admin-secret", sessionCookie.getValue());
        assertFalse(setCookie.contains("admin-secret"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Max-Age=900"));
        assertTrue(setCookie.contains("; Secure"));

        MockHttpServletRequest followUp = request("/api/settings/model");
        followUp.setCookies(sessionCookie);

        assertTrue(interceptor.preHandle(followUp, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void longLivedSessionRecordContainsNoTokenDerivedField() {
        Class<?> activeSession = java.util.Arrays.stream(AdminTokenGuardInterceptor.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ActiveSession"))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("expiresAt"),
                java.util.Arrays.stream(activeSession.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList());
    }

    @Test
    void spoofedForwardedHttpsDoesNotMarkLocalAdminCookieSecure() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "local");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        assertFalse(headers.get(0).contains("; Secure"));
    }

    @Test
    void repeatedGuardInvocationIssuesOnlyOneSessionCookie() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals(1, response.getHeaders("Set-Cookie").size());
    }

    @Test
    void productionAliasesIssueSecureSessionCookie() throws Exception {
        for (String profile : new String[]{"production", "live-blue", "prod-eu"}) {
            AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, profile);
            MockHttpServletRequest request = request("/api/settings/model");
            request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertTrue(response.getHeader("Set-Cookie").contains("; Secure"), profile);
        }
    }

    @Test
    void ownerTokenHeaderAccepts() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("", "owner-secret", false, false, "prod");
        MockHttpServletRequest request = request("/model-settings");
        request.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void legacyRawTokenCookieIsRejectedAndCleared() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest request = request("/model-settings");
        request.setCookies(new Cookie(AdminTokenGuardInterceptor.COOKIE_NAME, "admin-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        String clearCookie = response.getHeader("Set-Cookie");
        assertNotNull(clearCookie);
        assertTrue(clearCookie.contains(AdminTokenGuardInterceptor.COOKIE_NAME + "="));
        assertTrue(clearCookie.contains("Max-Age=0"));
    }

    @Test
    void configuredTokenRotationInvalidatesIssuedSessionCookie() throws Exception {
        AdminTokenGuardInterceptor issuer = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest issueRequest = request("/model-settings");
        issueRequest.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse issueResponse = new MockHttpServletResponse();
        assertTrue(issuer.preHandle(issueRequest, issueResponse, new Object()));

        AdminTokenGuardInterceptor rotated = interceptor("rotated-admin-secret", "", false, false, "prod");
        MockHttpServletRequest replay = request("/model-settings");
        replay.setCookies(responseCookie(issueResponse.getHeader("Set-Cookie")));
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();

        assertFalse(rotated.preHandle(replay, replayResponse, new Object()));
        assertEquals(403, replayResponse.getStatus());
        assertTrue(replayResponse.getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void processRestartInvalidatesCapabilityEvenWhenConfiguredTokenIsUnchanged() throws Exception {
        AdminTokenGuardInterceptor issuer = interceptor("admin-secret", "", false, false, "prod");
        Cookie issued = issueCookie(issuer, "admin-secret");
        AdminTokenGuardInterceptor restarted = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest replay = request("/model-settings");
        replay.setCookies(issued);

        assertFalse(restarted.preHandle(replay, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void successfulHeaderReauthenticationRotatesAndRevokesExistingCapability() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        Cookie original = issueCookie(interceptor, "admin-secret");
        MockHttpServletRequest reauth = request("/model-settings");
        reauth.setCookies(original);
        reauth.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse reauthResponse = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(reauth, reauthResponse, new Object()));
        Cookie rotated = responseCookie(reauthResponse.getHeader("Set-Cookie"));
        assertNotEquals(original.getValue(), rotated.getValue());

        MockHttpServletRequest oldReplay = request("/model-settings");
        oldReplay.setCookies(original);
        assertFalse(interceptor.preHandle(oldReplay, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest newReplay = request("/model-settings");
        newReplay.setCookies(rotated);
        assertTrue(interceptor.preHandle(newReplay, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void logoutRevokesCopiedCapabilityAndClearsBrowserCookie() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        Cookie issued = issueCookie(interceptor, "admin-secret");
        MockHttpServletRequest logout = request("/logout");
        logout.setCookies(issued);
        MockHttpServletResponse logoutResponse = new MockHttpServletResponse();

        interceptor.revokePresentedSession(logout, logoutResponse);

        assertTrue(logoutResponse.getHeader("Set-Cookie").contains("Max-Age=0"));
        MockHttpServletRequest replay = request("/model-settings");
        replay.setCookies(issued);
        assertFalse(interceptor.preHandle(replay, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void cookieCapabilityIsLimitedToSafeMethodsWhileHeaderWorksForWrites() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        Cookie issued = issueCookie(interceptor, "admin-secret");
        MockHttpServletRequest cookiePost = request("POST", "/api/admin/graph/probe");
        cookiePost.setCookies(issued);

        assertFalse(interceptor.preHandle(cookiePost, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest headerPost = request("POST", "/api/admin/graph/probe");
        headerPost.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        assertTrue(interceptor.preHandle(headerPost, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void cookieCapabilityMayUseCsrfProtectedUiWriteButNotCsrfExemptApiWrite() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        Cookie issued = issueCookie(interceptor, "admin-secret");

        MockHttpServletRequest uiPost = request("POST", "/model-settings/save");
        uiPost.setCookies(issued);
        assertTrue(interceptor.preHandle(uiPost, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest apiPost = request("POST", "/api/admin/graph/probe");
        apiPost.setCookies(issued);
        assertFalse(interceptor.preHandle(apiPost, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void graphCsrfExemptionMatchesOnlyGraphRequestsWithValidHeader() {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest valid = request("POST", "/api/admin/graph/probe");
        valid.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        assertTrue(interceptor.isHeaderAuthorizedGraphRequest(valid));

        MockHttpServletRequest missing = request("POST", "/api/admin/graph/probe");
        assertFalse(interceptor.isHeaderAuthorizedGraphRequest(missing));

        MockHttpServletRequest other = request("POST", "/api/admin/fine-tuning/start");
        other.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        assertFalse(interceptor.isHeaderAuthorizedGraphRequest(other));
    }

    @Test
    void independentHeaderAuthenticationsUseUniqueNoncesAndTamperingIsRejected() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        Cookie first = issueCookie(interceptor, "admin-secret");
        Cookie second = issueCookie(interceptor, "admin-secret");
        assertNotEquals(first.getValue(), second.getValue());

        String value = second.getValue();
        char replacement = value.charAt(value.length() - 1) == 'A' ? 'B' : 'A';
        Cookie tampered = new Cookie(AdminTokenGuardInterceptor.COOKIE_NAME,
                value.substring(0, value.length() - 1) + replacement);
        MockHttpServletRequest replay = request("/model-settings");
        replay.setCookies(tampered);
        assertFalse(interceptor.preHandle(replay, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void sessionTtlIsClampedToOneMinuteAndOneHour() throws Exception {
        AdminTokenGuardInterceptor shortTtl = interceptor("admin-secret", "", false, false, "prod");
        ReflectionTestUtils.setField(shortTtl, "sessionTtlSeconds", 1L);
        MockHttpServletResponse shortResponse = issueResponse(shortTtl, "admin-secret");
        assertTrue(shortResponse.getHeader("Set-Cookie").contains("Max-Age=60"));

        AdminTokenGuardInterceptor longTtl = interceptor("admin-secret", "", false, false, "prod");
        ReflectionTestUtils.setField(longTtl, "sessionTtlSeconds", 99_999L);
        MockHttpServletResponse longResponse = issueResponse(longTtl, "admin-secret");
        assertTrue(longResponse.getHeader("Set-Cookie").contains("Max-Age=3600"));
    }

    @Test
    void activeSessionCapacityEvictsAnOlderCapability() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        ReflectionTestUtils.setField(interceptor, "maxActiveSessions", 1);
        Cookie first = issueCookie(interceptor, "admin-secret");
        Cookie second = issueCookie(interceptor, "admin-secret");

        MockHttpServletRequest firstReplay = request("/model-settings");
        firstReplay.setCookies(first);
        assertFalse(interceptor.preHandle(firstReplay, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest secondReplay = request("/model-settings");
        secondReplay.setCookies(second);
        assertTrue(interceptor.preHandle(secondReplay, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void expiredCapabilityIsRejectedAndRemoved() throws Exception {
        Instant issuedAt = Instant.parse("2026-08-12T00:00:00Z");
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        ReflectionTestUtils.setField(interceptor, "sessionTtlSeconds", 60L);
        ReflectionTestUtils.setField(interceptor, "clock", Clock.fixed(issuedAt, ZoneOffset.UTC));
        Cookie issued = issueCookie(interceptor, "admin-secret");

        ReflectionTestUtils.setField(interceptor, "clock",
                Clock.fixed(issuedAt.plusSeconds(61), ZoneOffset.UTC));
        MockHttpServletRequest replay = request("/model-settings");
        replay.setCookies(issued);

        assertFalse(interceptor.preHandle(replay, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void prodQueryTokenDoesNotAuthenticate() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, true, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.setParameter("adminToken", "admin-secret");
        request.setParameter("token", "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void localQueryTokenDoesNotAuthenticateEvenWhenEnabled() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, true, "local");
        MockHttpServletRequest request = request("/api/settings/model");
        request.setParameter("adminToken", "admin-secret");
        request.setParameter("token", "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void ownerKeyHeaderDoesNotAuthenticate() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("", "owner-secret", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader("X-Owner-Key", "owner-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void denialHintDoesNotAdvertiseQueryToken() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, true, "local");
        MockHttpServletRequest request = request("/admin/debug");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertFalse(response.getContentAsString().contains("adminToken"));
        assertFalse(response.getContentAsString().contains("?token"));
    }

    private static AdminTokenGuardInterceptor interceptor(String adminToken,
                                                         String ownerToken,
                                                         boolean tokenRequired,
                                                         boolean allowQueryToken,
                                                         String activeProfiles) {
        AdminTokenGuardInterceptor interceptor = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", adminToken);
        ReflectionTestUtils.setField(interceptor, "ownerToken", ownerToken);
        ReflectionTestUtils.setField(interceptor, "tokenRequired", tokenRequired);
        ReflectionTestUtils.setField(interceptor, "allowQueryToken", allowQueryToken);
        ReflectionTestUtils.setField(interceptor, "activeProfiles", activeProfiles);
        return interceptor;
    }

    private static MockHttpServletRequest request(String uri) {
        return request("GET", uri);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private static Cookie issueCookie(AdminTokenGuardInterceptor interceptor, String token) throws Exception {
        return responseCookie(issueResponse(interceptor, token).getHeader("Set-Cookie"));
    }

    private static MockHttpServletResponse issueResponse(AdminTokenGuardInterceptor interceptor,
                                                         String token) throws Exception {
        MockHttpServletRequest request = request("/model-settings");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        return response;
    }

    private static Cookie responseCookie(String setCookie) {
        assertNotNull(setCookie);
        String prefix = AdminTokenGuardInterceptor.COOKIE_NAME + "=";
        assertTrue(setCookie.startsWith(prefix));
        int valueEnd = setCookie.indexOf(';');
        String value = valueEnd < 0
                ? setCookie.substring(prefix.length())
                : setCookie.substring(prefix.length(), valueEnd);
        return new Cookie(AdminTokenGuardInterceptor.COOKIE_NAME, value);
    }
}
