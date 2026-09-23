package com.abandonware.ai.agent.orchestrator.nodes;

import com.abandonware.ai.agent.orchestrator.subagent.SubagentResult;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Synthesize successful subagent outputs once, while retaining categorical status only. */
    public Map<String, Object> run(Map<String, Object> ctx, List<SubagentResult> results) {
        List<SubagentResult> ordered = results == null
                ? List.of()
                : results.stream().sorted(Comparator.comparingInt(SubagentResult::ordinal)).toList();
        List<SubagentResult> successful = ordered.stream().filter(SubagentResult::succeeded).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("synthesis.status", synthesisStatus(successful.size(), ordered.size()));
        out.put("synthesis.inputCount", ordered.size());
        out.put("synthesis.successCount", successful.size());
        if (successful.isEmpty()) {
            out.put("answer", "서브에이전트 실행이 완료되지 않았습니다. 사용 가능한 provider를 확인해 주세요.");
            return out;
        }

        StringBuilder answer = new StringBuilder("종합 답변");
        for (SubagentResult result : successful) {
            answer.append("\n\n[").append(result.role()).append("]\n")
                    .append(result.output().trim());
        }
        out.put("answer", answer.toString());
        return out;
    }

    private static String synthesisStatus(int successCount, int totalCount) {
        if (successCount <= 0) {
            return "FAILED";
        }
        return successCount == totalCount ? "COMPLETE" : "DEGRADED";
    }
}
