package com.abandonware.ai.agent.service.llm;

import com.abandonware.ai.agent.model.ChatMode;
import com.abandonware.ai.agent.model.ChatRequest;

public class HybridEngineArbiter {
    private static final int COST_TOKEN_THRESHOLD = 1500;
    private static final String LOCAL_PROVIDER = "local-vllm";
    private static final String REMOTE_PROVIDER = "openai";

    public static ChatMode selectMode(ChatRequest request) {
        String q = request.getUserQuestion() == null ? "" : request.getUserQuestion().toLowerCase();
        if (containsSensitiveKeywords(q)) return ChatMode.SAFE;
        if (q.length() > 120 || (q.contains("?") && q.contains("how"))) return ChatMode.BRAVE;
        return ChatMode.ZERO_BREAK;
    }

    public static String selectInitialProvider(ChatMode mode, ChatRequest request) {
        // default local-first
        return LOCAL_PROVIDER;
    }

    private static boolean containsSensitiveKeywords(String q) {
        return q.contains("self-harm") || q.contains("terror") || q.contains("violence");
    }

    @SuppressWarnings("unused")
    private static int estimateTokens(ChatRequest request) {
        String q = request.getUserQuestion() == null ? "" : request.getUserQuestion();
        return Math.max(1, q.length() / 4);
    }
}