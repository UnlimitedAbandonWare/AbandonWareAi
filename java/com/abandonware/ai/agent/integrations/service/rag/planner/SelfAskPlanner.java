package com.abandonware.ai.agent.integrations.service.rag.planner;

import java.util.*;
public final class SelfAskPlanner {
    public List<String> maybeBranch(String query) {
        if (query == null) return Collections.emptyList();
        String q = query.trim();
        if (q.length() < 48) return Collections.emptyList();
        List<String> subs = new ArrayList<>();
        subs.add(q);
        subs.add(q + " site:gov");
        subs.add(q + " site:edu");
        return subs;
    }
}