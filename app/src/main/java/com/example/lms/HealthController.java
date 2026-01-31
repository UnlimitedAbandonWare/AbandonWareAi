package com.example.lms;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
    @GetMapping({"/", "/health", "/actuator/alive"})
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "lms-app");
    }
}