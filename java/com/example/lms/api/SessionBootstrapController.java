package com.example.lms.api;

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
        map.put("ownerKeyPresent", request.getCookies() != null);
        map.put("remoteAddr", request.getRemoteAddr());
        return ResponseEntity.ok(map);
    }
}