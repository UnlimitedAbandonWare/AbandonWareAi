package com.abandonware.ai.modes;

import java.util.*;

/** AngerOverdrive: iterative narrowing around an anchor term. */
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