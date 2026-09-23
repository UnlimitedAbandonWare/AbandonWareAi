package com.abandonware.ai.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class SessionBootstrapController {

    @Value("${bootstrap.expose-admin-only:true}")
    private boolean adminOnly;

    @GetMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(HttpServletRequest req) {
        if (adminOnly) {
            String role = req.getHeader("X-Role");
            if (role == null || !role.contains("ROLE_ADMIN")) {
                return ResponseEntity.status(403).body(Map.of("error","admin only"));
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("status", "ok");
        out.put("remoteIpPresent", req.getRemoteAddr() != null);
        out.put("cookiePresent", req.getHeader("Cookie") != null);
        return ResponseEntity.ok(out);
    }
}