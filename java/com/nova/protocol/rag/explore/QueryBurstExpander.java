package com.nova.protocol.rag.explore;

import java.util.ArrayList;
import java.util.List;



public class QueryBurstExpander {
    public List<String> expand(String query, int n) {
        List<String> out = new ArrayList<>();
        out.add(query);
        // naive expansions (stub)
        out.add(query + " 최신");
        out.add(query + " 배경");
        out.add(query + " 핵심");
        while (out.size() < n) out.add(query + " +" + out.size());
        return out;
    }
}