
package com.rc111.merge21.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/soak")
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakApiController {
    @GetMapping("/run")
    public Map<String,Object> run(@RequestParam(defaultValue="10") int k,
                                  @RequestParam(defaultValue="all") String topic) {
        Map<String,Object> out = new HashMap<>();
        out.put("k", k);
        out.put("topic", topic);
        out.put("accuracy", 0.0);
        out.put("avg_latency_ms", 0);
        out.put("notes", "scaffold only");
        return out;
    }
}
