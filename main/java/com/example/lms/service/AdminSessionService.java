// ─────────────────────────────────────────────────────────────────────────────
// Admin 24h Session 유지 로직 (쿠키 기반)
// ─────────────────────────────────────────────────────────────────────────────
// ① AdminSessionService          : 쿠키 생성·검증 + HMAC 서명, 24h 만료
// ② AdminAuthInterceptor         : 모든 요청 가로채어 쿠키 유효성 검사 → request.setAttribute("isAdmin", true)
// ③ WebConfig                    : Interceptor 등록 (Spring MVC)
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 경로: src/main/java/com/example/lms/service/AdminSessionService.java
// ─────────────────────────────────────────────────────────────────────────────
package com.example.lms.service;

import com.example.lms.config.ConfigValueGuards;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 어드민 전용 24h remember-me 토큰 발급 & 검증 서비스.
 * <pre>
 * token 구조: base64("username|epochMillis|HMAC(username|epochMillis, secretKey)")
 * - username      : 관리자 ID (단순 식별용)
 * - epochMillis   : 발급 시각 (ms)
 * - HMAC          : SHA-256 서명 → 위·변조 방지
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class AdminSessionService {
    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    private static final String COOKIE_NAME = "admin-token";
    private static final long   VALIDITY_MS = 24 * 60 * 60 * 1000L; // 24h

    /** application.yml 에서 `security.admin-secret` 로 주입 */
    @Value("${security.admin-secret:${SECURITY_ADMIN_SECRET:}}")
    private String secretKey;

    /* ────────── 쿠키 발급 ────────── */
    public void issueToken(HttpServletResponse res, String username) {
        String signingSecret = effectiveSecret();
        if (signingSecret == null) {
            log.warn("[AWX][admin-session] token-cookie-skipped reason=missing_admin_secret usernamePresent={}",
                    username != null && !username.isBlank());
            return;
        }
        long now = Instant.now().toEpochMilli();
        String payload = username + "|" + now;
        String sig     = hmacSha256(payload, signingSecret);
        String token   = Base64.getUrlEncoder().encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));

        ResponseCookie c = ResponseCookie.from(COOKIE_NAME, token)
                .maxAge(VALIDITY_MS / 1000)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, c.toString());
        log.info("[AWX][admin-session] token-cookie-issued usernamePresent={}",
                username != null && !username.isBlank());
    }

    /* ────────── 유효성 검사 ────────── */
    public boolean isValid(Cookie[] cookies) {
        String signingSecret = effectiveSecret();
        if (signingSecret == null) return false;
        if (cookies == null) return false;
        Optional<Cookie> opt =  java.util.Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .findFirst();
        if (opt.isEmpty()) return false;

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(opt.get().getValue()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 3) return false;
            String username   = parts[0];
            long   issuedTime = Long.parseLong(parts[1]);
            String sig        = parts[2];

            // 만료 체크
            if ((Instant.now().toEpochMilli() - issuedTime) > VALIDITY_MS) return false;

            // 서명 검증
            String expectedSig = hmacSha256(username + "|" + issuedTime, signingSecret);
            return MessageDigest.isEqual(
                    expectedSig.getBytes(StandardCharsets.UTF_8),
                    sig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[AWX][admin-session] token-parse-failed errorType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /* ────────── 내부: HMAC SHA-256 ────────── */
    private String hmacSha256(String data, String signingSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    private String effectiveSecret() {
        return ConfigValueGuards.isMissing(secretKey) ? null : secretKey.trim();
    }
}
