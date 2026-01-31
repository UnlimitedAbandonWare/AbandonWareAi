package com.abandonware.ai.agent.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/probe/search")
@ConditionalOnProperty(name = "probe.search.enabled", havingValue = "true", matchIfMissing = false)
public class SearchProbeController {
    @PostMapping
    public Map<String,Object> probe(@RequestBody Map<String,Object> body) {
        Map<String,Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("echo", body);
        result.put("note", "This is a diagnostic stub; wire to real chain.");
        return result;
    }
}
