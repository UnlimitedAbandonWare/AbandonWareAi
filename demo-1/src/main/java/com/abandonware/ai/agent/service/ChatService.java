package com.abandonware.ai.agent.service;

import com.abandonware.ai.agent.model.*;
import com.abandonware.ai.agent.prompt.PromptBuilder;
import com.abandonware.ai.agent.service.llm.HybridEngineArbiter;
import com.abandonware.ai.agent.service.llm.LlmRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final LlmRouterService llmRouter;
    private static final ConcurrentHashMap<String, String> sessionProviderMap = new ConcurrentHashMap<>();

    public ChatService(LlmRouterService llmRouter) {
        this.llmRouter = llmRouter;
    }

    public ChatResponse handleChat(ChatRequest request) {
        ChatContext ctx = new ChatContext();
        ctx.setSessionId(request.getSessionId());
        ctx.setRequestId(request.getRequestId());
        ctx.setUserQuestion(request.getUserQuestion());
        MDC.put("sessionId", ctx.getSessionId());
        MDC.put("requestId", ctx.getRequestId());

        ChatMode mode = HybridEngineArbiter.selectMode(request);
        ctx.setMode(mode);
        if (mode == ChatMode.SAFE) ctx.setOfficialSourcesOnly(true);

        String sessionId = ctx.getSessionId();
        if (sessionId != null && sessionProviderMap.containsKey(sessionId)) {
            ctx.setProvider(sessionProviderMap.get(sessionId));
        } else {
            String provider = HybridEngineArbiter.selectInitialProvider(mode, request);
            ctx.setProvider(provider);
            if (sessionId != null) sessionProviderMap.put(sessionId, provider);
        }

        String prompt = PromptBuilder.build(ctx);
        String answer = llmRouter.generateAnswer(prompt, ctx);

        var resp = new ChatResponse();
        resp.setSessionId(ctx.getSessionId());
        resp.setRequestId(ctx.getRequestId());
        resp.setAnswer(answer);
        return resp;
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}

// PATCH_MARKER: ChatService updated per latest spec.
