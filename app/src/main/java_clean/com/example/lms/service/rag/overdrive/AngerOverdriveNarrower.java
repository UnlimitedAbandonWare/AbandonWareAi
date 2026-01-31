package com.example.lms.service.rag.overdrive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Narrower that collapses expanded candidates by simple TF-like vote. */
public class AngerOverdriveNarrower {
    public List<String> narrow(List<String> subs, int target){
        if (subs == null || subs.isEmpty()) return Collections.emptyList();
        int k = Math.max(1, Math.min(target <= 0 ? 8 : target, subs.size()));
        List<String> out = new ArrayList<>(k);
        for (int i=0;i<subs.size() && out.size()<k;i+=Math.max(1, subs.size()/k)){
            out.add(subs.get(i));
        }
        if (out.isEmpty()) out.add(subs.get(0));
        return out;
    }
}