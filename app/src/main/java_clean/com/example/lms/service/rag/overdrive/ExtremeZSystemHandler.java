package com.example.lms.service.rag.overdrive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extreme-Z expansion: explode a query into many sub-queries.
 * NOTE: uses properly escaped Java regex for whitespace.
 */
public final class ExtremeZSystemHandler {
    public List<String> explode(String query, int max){
        if (query == null) return Collections.emptyList();
        String[] toks = query.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        for (String t : toks){
            if (t.length() < 2) continue;
            // two variants per token to encourage diversity
            out.add(query + " " + t);
            out.add(t + " definition");
            if (max > 0 && out.size() >= max) break;
        }
        if (out.isEmpty()) out.add(query);
        return out;
    }
}