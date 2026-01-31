package com.abandonware.ai.agent.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/soak")
@ConditionalOnProperty(name = "features.SoakTestAPI", havingValue = "true", matchIfMissing = true)
public class SoakController {
    @GetMapping("/run")
    public Map<String,Object> run(@RequestParam(name="k", defaultValue="10") int k,
                                  @RequestParam(name="topic", defaultValue="default") String topic) {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (int i=0;i<k;i++) {
            Map<String,Object> r = new HashMap<>();
            r.put("query", "q"+i);
            r.put("hit", Boolean.TRUE);
            r.put("grounding", 3);
            r.put("latencyMs", 100 + i*5);
            rows.add(r);
        }
        Map<String,Object> out = new HashMap<>();
        out.put("topic", topic);
        out.put("rows", rows);
        return out;
    }
}
