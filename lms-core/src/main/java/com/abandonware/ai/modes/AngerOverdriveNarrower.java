package com.abandonware.ai.modes;

import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.modes.AngerOverdriveNarrower
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.modes.AngerOverdriveNarrower
role: config
*/
public class AngerOverdriveNarrower {
    public List<String> narrow(List<String> candidates, String anchor, int stages) {
        List<String> cur = new ArrayList<>(candidates);
        for (int s=0;s<stages;s++) {
            final String a = anchor;
            cur.sort(Comparator.comparingInt(x->x.contains(a)?0:1));
            if (cur.size() > 8) cur = cur.subList(0, Math.max(8, cur.size()/2));
        }
        return cur;
    }
}