package com.nova.protocol.rag.explore;

import java.util.ArrayList;
import java.util.List;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.rag.explore.QueryBurstExpander
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.rag.explore.QueryBurstExpander
role: config
*/
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