package com.example.lms.security;

import com.example.lms.config.ConfigValueGuards;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Optional operational guardrail for admin pages and diagnostics endpoints.
 *
 * <p>Supported presentation channels are intentionally narrow:</p>
 * <ul>
 *   <li>Header: {@code X-Admin-Token}</li>
 *   <li>Header: {@code X-Owner-Token}</li>
 *   <li>Cookie: {@code aw-admin-token} (derived, expiring HttpOnly session capability)</li>
 * </ul>
 */
@Component
public class AdminTokenGuardInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";
    public static final String OWNER_HEADER = "X-Owner-Token";
    public static final String COOKIE_NAME = "aw-admin-token";
    private static final String SESSION_VERSION = "v1";
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(15);
    private static final Duration MAX_SESSION_TTL = Duration.ofHours(1);
    private static final int SESSION_NONCE_BYTES = 16;
    private static final int SESSION_SIGNING_KEY_BYTES = 32;
    private static final String SESSION_ISSUED_ATTRIBUTE =
            AdminTokenGuardInterceptor.class.getName() + ".sessionIssued";
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();

    private final byte[] sessionSigningKey = randomBytes(SESSION_SIGNING_KEY_BYTES);
    private final ConcurrentMap<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Value("${domain.allowlist.admin-token:}")
    private String expectedToken;

    @Value("${llm.owner-token:${LLM_OWNER_TOKEN:}}")
    private String ownerToken;

    @Value("${domain.allowlist.admin-token.required:${DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED:false}}")
    private boolean tokenRequired;

    @SuppressWarnings("unused")
    @Value("${domain.allowlist.admin-token.allow-query:${DOMAIN_ALLOWLIST_ADMIN_TOKEN_ALLOW_QUERY:false}}")
    private boolean allowQueryToken;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Value("${domain.allowlist.admin-session.ttl-seconds:${DOMAIN_ALLOWLIST_ADMIN_SESSION_TTL_SECONDS:900}}")
    private long sessionTtlSeconds = DEFAULT_SESSION_TTL.getSeconds();

    @Value("${domain.allowlist.admin-session.max-active:${DOMAIN_ALLOWLIST_ADMIN_SESSION_MAX_ACTIVE:1024}}")
    private int maxActiveSessions = 1024;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        boolean strict = tokenRequired || hasProductionProfile(activeProfiles);
        List<String> expected = configuredTokens();
        if (expected.isEmpty()) {
            if (strict) {
                clearSessionCookieIfPresent(req, res, strict);
                deny(req, res);
                return false;
            }
            return true;
        }

        String matchedHeaderToken = matchingExpectedToken(expected, extractPresentedHeaderToken(req));
        if (matchedHeaderToken != null) {
            revokeSessionValue(extractSessionCookie(req));
            issueSessionCookieIfNeeded(req, res, strict);
            return true;
        }

        String sessionCookie = extractSessionCookie(req);
        if (isCookieCapableRequest(req) && isSessionCookieAuthorized(expected, sessionCookie, sessionTtl())) {
            return true;
        }

        clearSessionCookieIfPresent(req, res, strict);
        deny(req, res);
        return false;
    }

    private static String extractPresentedHeaderToken(HttpServletRequest req) {
        if (req == null) return "";

        String header = req.getHeader(HEADER);
        if (header != null && !header.isBlank()) return header.trim();

        header = req.getHeader(OWNER_HEADER);
        if (header != null && !header.isBlank()) return header.trim();

        return "";
    }

    private static String extractSessionCookie(HttpServletRequest req) {
        if (req == null) return "";

        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie != null && COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) return value.trim();
                }
            }
        }

        return "";
    }

    private static void deny(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (res == null) return;

        String uri = safePath(req);
        boolean api = uri.startsWith("/api/");

        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (api) {
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"admin token required\",\"hint\":\"set X-Admin-Token or X-Owner-Token header\"}");
            return;
        }

        res.setContentType(MediaType.TEXT_HTML_VALUE);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>");
        sb.append("<title>Admin token required</title>");
        sb.append("<style>");
        sb.append("body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:24px;}");
        sb.append("code{background:#f4f4f4;padding:2px 6px;border-radius:4px;}");
        sb.append(".box{max-width:880px;}");
        sb.append("</style>");
        sb.append("</head><body><div class=\"box\">");
        sb.append("<h2>Admin token required</h2>");
        sb.append("<p>This environment is configured with <code>domain.allowlist.admin-token</code>.</p>");
        sb.append("<p>To access admin/debug pages, present the token via one of the following:</p>");
        sb.append("<ul>");
        sb.append("<li><code>X-Admin-Token: &lt;token&gt;</code> header</li>");
        sb.append("<li><code>X-Owner-Token: &lt;token&gt;</code> header</li>");
        sb.append("</ul>");
        sb.append("<p>Successful header authentication creates an expiring HttpOnly session cookie.</p>");
        sb.append("</div></body></html>");
        res.getWriter().write(sb.toString());
    }

    private void issueSessionCookieIfNeeded(HttpServletRequest req,
                                             HttpServletResponse res,
                                             boolean forceSecure) {
        if (req == null || res == null) return;
        if (Boolean.TRUE.equals(req.getAttribute(SESSION_ISSUED_ATTRIBUTE))) return;

        Duration ttl = sessionTtl();
        IssuedSession issued = newSessionCookieValue(ttl);
        registerActiveSession(issued.value(), issued.expiresAt());

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, issued.value())
                .httpOnly(true)
                .secure(forceSecure || isHttpsRequest(req))
                .path("/")
                .sameSite("Lax")
                .maxAge(ttl)
                .build();

        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        req.setAttribute(SESSION_ISSUED_ATTRIBUTE, Boolean.TRUE);
    }

    private static void clearSessionCookieIfPresent(HttpServletRequest req,
                                                    HttpServletResponse res,
                                                    boolean forceSecure) {
        if (res == null || extractSessionCookie(req).isBlank()) return;

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(forceSecure || isHttpsRequest(req))
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void revokePresentedSession(HttpServletRequest req, HttpServletResponse res) {
        revokeSessionValue(extractSessionCookie(req));
        clearSessionCookieIfPresent(req, res, hasProductionProfile(activeProfiles));
    }

    private IssuedSession newSessionCookieValue(Duration ttl) {
        long expiresAt = Instant.now(clock).plus(ttl).getEpochSecond();
        byte[] nonce = new byte[SESSION_NONCE_BYTES];
        SESSION_RANDOM.nextBytes(nonce);
        String payload = SESSION_VERSION + "." + expiresAt + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionMac(payload));
        return new IssuedSession(payload + "." + signature, expiresAt);
    }

    private boolean isSessionCookieAuthorized(List<String> expected,
                                              String presented,
                                              Duration ttl) {
        if (expected == null || expected.isEmpty() || presented == null || presented.isBlank()) {
            return false;
        }

        String[] parts = presented.trim().split("\\.", -1);
        if (parts.length != 4 || !SESSION_VERSION.equals(parts[0])) return false;

        long now = Instant.now(clock).getEpochSecond();
        long expiresAt;
        byte[] nonce;
        byte[] signature;
        try {
            expiresAt = Long.parseLong(parts[1]);
            nonce = Base64.getUrlDecoder().decode(parts[2]);
            signature = Base64.getUrlDecoder().decode(parts[3]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String sessionDigest = sessionDigest(presented);
        if (expiresAt <= now || expiresAt > now + ttl.getSeconds() + 60) {
            activeSessions.remove(sessionDigest);
            return false;
        }
        if (nonce.length != SESSION_NONCE_BYTES || signature.length != 32) return false;

        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(sessionMac(payload), signature)) return false;

        ActiveSession active = activeSessions.get(sessionDigest);
        if (active == null || active.expiresAt() != expiresAt) return false;
        return expected != null && expected.stream().anyMatch(token -> token != null && !token.isBlank());
    }

    private byte[] sessionMac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sessionSigningKey, "HmacSHA256"));
            return mac.doFinal((COOKIE_NAME + "|" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("admin session signing unavailable", ex);
        }
    }

    private static boolean isHttpsRequest(HttpServletRequest request) {
        return request != null && request.isSecure();
    }

    private static boolean isSafeMethod(HttpServletRequest request) {
        if (request == null || request.getMethod() == null) return false;
        return switch (request.getMethod().toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }

    private static boolean isCookieCapableRequest(HttpServletRequest request) {
        return isSafeMethod(request) || isCsrfProtectedUiPath(safePath(request));
    }

    private static boolean isCsrfProtectedUiPath(String path) {
        String normalized = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("/admin") || normalized.startsWith("/admin/")
                || normalized.equals("/dashboard") || normalized.startsWith("/dashboard/")
                || normalized.equals("/model-settings") || normalized.startsWith("/model-settings/");
    }

    private synchronized void registerActiveSession(String value, long expiresAt) {
        long now = Instant.now(clock).getEpochSecond();
        activeSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        int capacity = Math.max(1, Math.min(maxActiveSessions, 10_000));
        while (activeSessions.size() >= capacity) {
            Map.Entry<String, ActiveSession> earliest = null;
            for (Map.Entry<String, ActiveSession> entry : activeSessions.entrySet()) {
                if (earliest == null || entry.getValue().expiresAt() < earliest.getValue().expiresAt()) {
                    earliest = entry;
                }
            }
            if (earliest == null || !activeSessions.remove(earliest.getKey(), earliest.getValue())) break;
        }
        activeSessions.put(sessionDigest(value), new ActiveSession(expiresAt));
    }

    private void revokeSessionValue(String value) {
        if (value != null && !value.isBlank()) activeSessions.remove(sessionDigest(value));
    }

    private static String sessionDigest(String value) {
        return digest(value == null ? "" : value);
    }

    private static String digest(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("admin session digest unavailable", ex);
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        SESSION_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String safePath(HttpServletRequest req) {
        if (req == null) return "";
        String uri = req.getRequestURI();
        if (uri == null) return "";
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isBlank() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (aa.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < aa.length; i++) {
            result |= aa[i] ^ bb[i];
        }
        return result == 0;
    }

    private List<String> configuredTokens() {
        List<String> tokens = new ArrayList<>();
        if (!ConfigValueGuards.isMissing(expectedToken)) {
            tokens.add(expectedToken.trim());
        }
        if (!ConfigValueGuards.isMissing(ownerToken)) {
            tokens.add(ownerToken.trim());
        }
        return tokens;
    }

    public boolean hasConfiguredToken() {
        return !configuredTokens().isEmpty();
    }

    public boolean isPresentedTokenAuthorized(HttpServletRequest req) {
        List<String> expected = configuredTokens();
        return matchingExpectedToken(expected, extractPresentedHeaderToken(req)) != null
                || (isCookieCapableRequest(req)
                && isSessionCookieAuthorized(expected, extractSessionCookie(req), sessionTtl()));
    }

    public boolean isPresentedHeaderTokenAuthorized(HttpServletRequest req) {
        List<String> expected = configuredTokens();
        return matchingExpectedToken(expected, extractPresentedHeaderToken(req)) != null;
    }

    public boolean isHeaderAuthorizedGraphRequest(HttpServletRequest req) {
        String path = safePath(req).toLowerCase(java.util.Locale.ROOT);
        return AdminTokenGuardFilter.isAdminGraphPath(path)
                && isPresentedHeaderTokenAuthorized(req);
    }

    private Duration sessionTtl() {
        long boundedSeconds = Math.max(60L, Math.min(sessionTtlSeconds, MAX_SESSION_TTL.getSeconds()));
        return Duration.ofSeconds(boundedSeconds);
    }

    private static String matchingExpectedToken(List<String> expected, String presented) {
        if (expected == null || expected.isEmpty()) return null;
        for (String token : expected) {
            if (constantTimeEquals(token, presented)) return token;
        }
        return null;
    }

    private static boolean hasProductionProfile(String profiles) {
        if (profiles == null) return false;
        for (String token : profiles.split(",")) {
            String profile = token.trim().toLowerCase();
            if (profile.equals("prod") || profile.equals("production") || profile.equals("live")
                    || profile.startsWith("prod-") || profile.startsWith("production-")
                    || profile.startsWith("live-")) return true;
        }
        return false;
    }

    private record IssuedSession(String value, long expiresAt) { }

    private record ActiveSession(long expiresAt) { }
}
