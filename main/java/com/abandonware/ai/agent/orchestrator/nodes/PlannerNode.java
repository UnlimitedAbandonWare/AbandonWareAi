package com.abandonware.ai.agent.orchestrator.nodes;

import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.trace.SafeRedactor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;



/** Minimal planner that suggests a retrieval-first plan with optional web search. */
public final class PlannerNode {
    public Map<String,Object> run(Map<String,Object> input){
        String text = input != null && input.get("text") != null ? input.get("text").toString() : "";
        Map<String,Object> plan = new HashMap<>();
        plan.put("plan", "RAG 먼저 → 필요시 web.search → 결과 합성 → (동의시) message.send");
        plan.put("query", text);
        return plan;
    }

    /** Create two bounded, independently owned contexts for the explicit subagent flow. */
    public List<SubagentTask> subagentTasks(String requestId, Map<String, Object> input) {
        String question = question(input);
        return List.of(
                task(requestId, 0, "analysis", question,
                        "Analyze the request and produce a concise evidence-grounded answer."),
                task(requestId, 1, "verification", question,
                        "Independently verify the request, identify unsupported claims, and return a concise check."));
    }

    private static SubagentTask task(String requestId,
                                     int ordinal,
                                     String role,
                                     String question,
                                     String instruction) {
        String taskId = "task-" + (ordinal + 1);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("requestId", requestId);
        context.put("taskId", taskId);
        context.put("role", role);
        context.put("questionHash", SafeRedactor.hashValue(question));
        context.put("questionLength", question.length());
        String prompt = instruction + "\n\nRequest:\n" + question;
        return new SubagentTask(requestId, ordinal, taskId, role, prompt, context);
    }

    private static String question(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        for (String key : List.of("question", "text", "message")) {
            Object value = input.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }
}
