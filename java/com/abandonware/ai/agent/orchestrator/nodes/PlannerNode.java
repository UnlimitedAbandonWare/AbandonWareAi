package com.abandonware.ai.agent.orchestrator.nodes;

import java.util.HashMap;
import java.util.Map;



/** Minimal planner that suggests a retrieval-first plan with optional web search. */
public final class PlannerNode {
    public Map<String,Object> run(Map<String,Object> input){
        String text = input != null && input.get("text") != null ? input.get("text").toString() : "";
        Map<String,Object> plan = new HashMap<>();
        plan.put("plan", "RAG 먼저 → 필요시 web.search → 결과 합성 → (동의시) kakao.push");
        plan.put("query", text);
        return plan;
    }
}