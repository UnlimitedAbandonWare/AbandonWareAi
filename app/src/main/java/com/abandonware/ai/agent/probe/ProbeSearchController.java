package com.abandonware.ai.agent.probe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix="probe.search", name="enabled", havingValue="true")
public class ProbeSearchController {
    @GetMapping(value="/api/probe/search", produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> probe(@RequestParam(name="q", required=false, defaultValue="") String q) {
        Map<String,Object> m = new HashMap<>();
        m.put("status", "ok");
        m.put("q", q);
        m.put("note", "This is a lightweight probe endpoint.");
        return m;
    }
}
