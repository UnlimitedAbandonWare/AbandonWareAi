package com.abandonware.ai.agent.orchestrator.nodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;



/** Minimal critic that checks presence of at least one evidence source. */
public final class CriticNode {
    public Map<String,Object> run(Map<String,Object> ctx){
        Map<String,Object> out = new HashMap<>();
        Object rag = ctx != null ? ctx.get("rag.retrieve") : null;
        Object web = ctx != null ? ctx.get("web.search") : null;
        boolean hasEvidence = (rag != null) || (web != null);
        out.put("critic", hasEvidence ? "coverage_ok" : "need_more_evidence");
        return out;
    }
}