package com.example.lms.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;




@Component
public class ClientOwnerKeyResolver {

    private final HttpServletRequest request;

    public ClientOwnerKeyResolver(HttpServletRequest request) {
        this.request = request;
    }

    /** Compute or retrieve stable ownerKey for current request. */
    public String ownerKey() {
        // 1) Header override
        String hdr = trimToNull(request.getHeader("X-Owner-Key"));
        if (hdr != null) return hdr;

        // 2) ownerKey cookie
        String cookieVal = readCookie("ownerKey");
        if (cookieVal != null) return cookieVal;

        // 3) gid cookie (compatibility path)
        String gid = readCookie("gid");
        if (gid != null) return "gid:" + gid;

        // 4) Fallback: IP + UA hash (do not store raw PII)
        String ip = firstForwardedIpOrRemoteAddr();
        String ua = Optional.ofNullable(request.getHeader("User-Agent")).orElse("");
        if (ua.length() > 120) ua = ua.substring(0, 120);
        String raw = (ip == null ? "" : ip) + "|" + ua;
        String digest = sha256(raw);
        if (digest != null) return "ipua:" + digest;

        // 5) Random
        return UUID.randomUUID().toString();
    }

    private String readCookie(String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                String v = trimToNull(c.getValue());
                if (v != null) return v;
            }
        }
        return null;
    }

    private String firstForwardedIpOrRemoteAddr() {
        String xff = trimToNull(request.getHeader("X-Forwarded-For"));
        if (xff != null) {
            int idx = xff.indexOf(',');
            return (idx > 0 ? xff.substring(0, idx) : xff).trim();
        }
        return trimToNull(request.getRemoteAddr());
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}