
package com.example.lms.web;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Component
public class ClientOwnerKeyResolver {

    private final HttpServletRequest request;

    public ClientOwnerKeyResolver(HttpServletRequest request) {
        this.request = request;
    }

    public String ownerKey() {
        String ip = firstForwardedFor(request).orElse(request.getRemoteAddr());
        String ua = Optional.ofNullable(request.getHeader("User-Agent")).orElse("");
        String uaPrefix = ua.length() > 32 ? ua.substring(0, 32) : ua;
        String raw = ip + "|" + uaPrefix;
        return sha256(raw);
    }

    private Optional<String> firstForwardedFor(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return Optional.empty();
        String first = xff.split(",")[0].trim();
        if (first.isEmpty()) return Optional.empty();
        return Optional.of(first);
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
