package com.abandonware.ai.agent.orchestrator.nodes;

import java.util.HashMap;
import java.util.Map;



/** Minimal synthesiser that builds a simple answer string from intermediate results. */
public final class SynthNode {
    @SuppressWarnings("unchecked")
    public Map<String,Object> run(Map<String,Object> ctx){
        Map<String,Object> out = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        Object question = ctx != null ? ctx.getOrDefault("question", "") : "";
        sb.append("요약 답변").append(": ");
        if (ctx != null && ctx.get("rag.retrieve") != null) {
            sb.append("[RAG 근거 포함] ");
        }
        if (ctx != null && ctx.get("web.search") != null) {
            sb.append("[웹 검색 보강] ");
        }
        if (question != null) {
            sb.append(question.toString());
        }
        out.put("answer", sb.toString());
        return out;
    }
}