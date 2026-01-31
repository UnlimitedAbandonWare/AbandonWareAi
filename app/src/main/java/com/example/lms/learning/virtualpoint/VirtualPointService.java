package com.example.lms.learning.virtualpoint;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.learning.virtualpoint.VirtualPointService
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.learning.virtualpoint.VirtualPointService
role: service
*/
public class VirtualPointService {
    private static final int MAX = 256;
    private final LinkedHashMap<String, VirtualPoint> lru = new LinkedHashMap<>(16,0.75f,true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VirtualPoint> eldest) {
            return size() > MAX;
        }
    };

    public void put(String key, VirtualPoint vp) {
        if (key != null && vp != null) lru.put(key, vp);
    }

    public Optional<VirtualPoint> get(String key) {
        return Optional.ofNullable(lru.get(key));
    }
}