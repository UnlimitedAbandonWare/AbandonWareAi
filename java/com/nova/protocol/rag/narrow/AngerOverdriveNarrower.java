package com.nova.protocol.rag.narrow;

import java.util.ArrayList;
import java.util.List;



public class AngerOverdriveNarrower {
    /** 단계 축소: N -> 8 -> 4 -> 2 */
    public List<String> narrow(List<String> in) {
        if (in == null) return List.of();
        List<String> cur = new ArrayList<>(in);
        while (cur.size() > 8) cur = cur.subList(0, Math.max(8, cur.size()/2));
        while (cur.size() > 4) cur = cur.subList(0, 4);
        while (cur.size() > 2) cur = cur.subList(0, 2);
        return cur;
    }
}