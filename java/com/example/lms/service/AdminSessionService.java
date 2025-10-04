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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 어드민 전용 24h remember-me 토큰 발급 & 검증 서비스.
 * <pre>
 * token 구조: base64("username|epochMillis|HMAC(username|epochMillis, secretKey)")
 * - username      : 관리자 ID (단순 식별용)
 * - epochMillis   : 발급 시각 (ms)
 * - HMAC          : SHA‑256 서명 → 위·변조 방지
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSessionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminSessionService.class);


    private static final String COOKIE_NAME = "admin-token";
    private static final long   VALIDITY_MS = 24 * 60 * 60 * 1000L; // 24h

    /** application.yml 에서 `security.admin-secret` 로 주입 */
    @Value("${security.admin-secret:SuperSecretKey123}")
    private String secretKey;

    /* ────────── 쿠키 발급 ────────── */
    public void issueToken(HttpServletResponse res, String username) {
        long now = Instant.now().toEpochMilli();
        String payload = username + "|" + now;
        String sig     = hmacSha256(payload);
        String token   = Base64.getUrlEncoder().encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));

        Cookie c = new Cookie(COOKIE_NAME, token);
        c.setMaxAge((int) (VALIDITY_MS / 1000));  // 24h
        c.setPath("/");
        c.setHttpOnly(true);
        // 필요 시 c.setSecure(true);
        res.addCookie(c);
        log.info("💾 Admin token 쿠키 발급 ({})", username);
    }

    /* ────────── 유효성 검사 ────────── */
    public boolean isValid(Cookie[] cookies) {
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
            String expectedSig = hmacSha256(username + "|" + issuedTime);
            return expectedSig.equals(sig);
        } catch (Exception e) {
            log.warn("⛔️ 관리자 토큰 파싱 실패", e);
            return false;
        }
    }

    /* ────────── 내부: HMAC SHA‑256 ────────── */
    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }
}