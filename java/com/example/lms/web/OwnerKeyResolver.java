package com.example.lms.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Resolve a stable ownerKey for a client from headers/cookies,
 * falling back to a hashed guest identifier.
 *
 * This is intentionally stateless so it can safely be used
 * from reactive / SSE entrypoints where HttpServletRequest
 * is not thread-bound anymore.
 */
@Component
public class OwnerKeyResolver {

    private static final String COOKIE_NAME = "owner_key";
    private static final String HEADER_NAME = "X-Owner-Key";

    public String resolveOwnerKey(HttpServletRequest req) {
        // 1) explicit header
        String fromHeader = req.getHeader(HEADER_NAME);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }

        // 2) cookie
        if (req.getCookies() != null) {
            for (Cookie cookie : req.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 3) fallback guest id
        return resolveGuestOwnerKey(req);
    }

    private String resolveGuestOwnerKey(HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        String userAgent = req.getHeader("User-Agent");
        if (userAgent == null) userAgent = "";
        if (userAgent.length() > 32) {
            userAgent = userAgent.substring(0, 32);
        }
        String raw = ip + ":" + userAgent;
        return "guest:" + sha256Hex(raw).substring(0, 16);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback: still deterministic but weaker
            return Integer.toHexString(input.hashCode());
        }
    }
}
