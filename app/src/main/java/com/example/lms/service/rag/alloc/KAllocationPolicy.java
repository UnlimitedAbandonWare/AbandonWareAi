package com.example.lms.service.rag.alloc;

import java.util.Map;
import java.util.LinkedHashMap;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.alloc.KAllocationPolicy
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.alloc.KAllocationPolicy
role: config
flags: [kg]
*/
public class KAllocationPolicy {
    public Map<String,Integer> allocate(int total){
        Map<String,Integer> m = new LinkedHashMap<>();
        if (total <= 0) { m.put("web", 0); m.put("vector", 0); m.put("kg", 0); return m; }
        int web = Math.max(1, (int)Math.round(total * 0.7));
        int vec = Math.max(1, (int)Math.round(total * 0.3));
        int kg = Math.max(1, (int)Math.round(total * 0.2));
        // normalize to total
        int sum = web + vec + kg;
        if (sum != total) {
            int diff = total - sum;
            web += diff; // push remainder to web
        }
        m.put("web", web); m.put("vector", vec); m.put("kg", kg);
        return m;
    }
}