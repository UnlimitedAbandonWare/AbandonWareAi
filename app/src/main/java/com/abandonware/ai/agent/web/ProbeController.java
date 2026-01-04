
package com.abandonware.ai.agent.web;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/probe")
public class ProbeController {
    @GetMapping("/health")
    public Map<String,Object> health() {
        return Map.of("status","OK","ts", System.currentTimeMillis());
    }
}
