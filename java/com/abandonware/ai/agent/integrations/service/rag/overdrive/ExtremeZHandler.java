package com.abandonware.ai.agent.integrations.service.rag.overdrive;

import java.util.*;
public final class ExtremeZHandler {
    public List<String> explode(String query) {
        if (query == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String[] tokens = query.split("\\s+");
        for (String t : tokens) {
            if (t.length() > 2) out.add(query + " " + t);
        }
        return out;
    }
}