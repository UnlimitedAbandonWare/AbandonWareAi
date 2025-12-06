package com.example.lms.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class BootstrapController {
    @GetMapping("/bootstrap")
    public Map<String,Object> bootstrap(){
        return Map.of(
            "status", "ok",
            "module", "app",
            "ts", System.currentTimeMillis()
        );
    }
}