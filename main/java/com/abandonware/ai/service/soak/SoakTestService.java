package com.abandonware.ai.service.soak;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SoakTestService {
    public Map<String, Object> run(int k, String topic) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("topic", topic);
        m.put("hitRate", 0.0);
        m.put("citationRate", 0.0);
        m.put("p95LatencyMs", 0);
        return m;
    }
}