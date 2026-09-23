package com.example.lms.api;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.web.OwnerKeyBootstrapFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;




/**
 * Idempotent bootstrap endpoint that returns basic session diagnostics.
 */
@RestController
public class SessionBootstrapController {

    @GetMapping("/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrap(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "ok");
        map.put("ownerKeyPresent", hasOwnerKeyCookie(request));
        String remoteAddr = request.getRemoteAddr();
        map.put("remoteAddrHash", SafeRedactor.hashValue(remoteAddr));
        map.put("remoteAddrLength", remoteAddr == null ? 0 : remoteAddr.length());
        return ResponseEntity.ok(map);
    }

    private static boolean hasOwnerKeyCookie(HttpServletRequest request) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (cookie != null
                    && OwnerKeyBootstrapFilter.OWNER_KEY.equals(cookie.getName())
                    && OwnerKeyBootstrapFilter.usableOwnerKey(cookie.getValue()) != null) {
                return true;
            }
        }
        return false;
    }
}
