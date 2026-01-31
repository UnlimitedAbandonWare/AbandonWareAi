
package com.rc111.merge21.web;

import ai.abandonware.nova.service.llm.LlmRouterServiceV2;
import ai.abandonware.nova.service.llm.LlmRouteDecision;
import ai.abandonware.nova.service.llm.LlmRouteContext;
import ai.abandonware.nova.service.llm.ProviderType;
import com.rc111.merge21.qa.ChatAnswerService;
import com.rc111.merge21.qa.Answer;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChatController {
    private final ai.abandonware.nova.service.LocalLlamaService localLlamaService;
    private final ai.abandonware.nova.service.OpenAiLlmService openAiLlmService;
    private final ChatAnswerService service;
    public ChatController(ChatAnswerService service) { this.service = service; }

    @PostMapping("/chat")
public ChatResponse chat(@RequestBody ChatRequest req) {
        LlmRouteContext ctx = LlmRouteContext.of(req);
        LlmRouteDecision decision = llmRouter.decide(ctx);
        String answer;
        if (decision.getProviderType() == ProviderType.LOCAL) {
            answer = localLlamaService.generate(decision, req);
        } else {
            answer = openAiLlmService.generate(decision, req);
        }
        return ChatResponse.ok(answer, decision);
    

    public static class Prompt { public String query; }
}

// PATCH_MARKER: ChatController updated per latest spec.
