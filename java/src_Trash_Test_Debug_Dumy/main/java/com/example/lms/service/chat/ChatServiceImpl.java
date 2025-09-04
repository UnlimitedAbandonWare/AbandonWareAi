// src/main/java/com/example/lms/service/chat/ChatServiceImpl.java
package com.example.lms.service.chat;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.routing.ModelRouter;
import dev.langchain4j.model.chat.ChatModel;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

// Import the nested ChatResult record from the interface
import com.example.lms.service.chat.ChatService.ChatResult;

@Service
public class ChatServiceImpl implements ChatService {

    private final PromptBuilder promptBuilder;
    private final ModelRouter modelRouter;
    private final com.example.lms.service.ChatHistoryService historyService;

    @Autowired
    public ChatServiceImpl(PromptBuilder promptBuilder,
                           ModelRouter modelRouter,
                           com.example.lms.service.ChatHistoryService historyService) {
        this.promptBuilder = requireNonNull(promptBuilder, "promptBuilder");
        this.modelRouter = requireNonNull(modelRouter, "modelRouter");
        this.historyService = requireNonNull(historyService, "historyService");
    }
    /** Optional convenience overload without external context provider. */
    public ChatResult continueChat(ChatRequestDto req) {
        return continueChat(req, q -> Collections.emptyList());
    }

    @Override
    public ChatResult continueChat(ChatRequestDto req, Function<String, List<String>> externalCtxProvider) {
        String userMsg = (req != null && req.getMessage() != null) ? req.getMessage().trim() : "";
        if (userMsg.isBlank()) {
            return ChatResult.of("질문이 비어 있습니다.", modelRouter.resolveModelName(null), false);
        }

        // In this codebase, ChatRequestDto does not expose extra snippet getters; use empty lists for now.
        List<String> webSnippets = Collections.emptyList();
        List<String> ragSnippets = Collections.emptyList();

        // Prompt strictly via PromptBuilder + PromptContext (no string concat)
        Long sid = req.getSessionId();
        String recent = null;
        String lastAnswer = null;
        try {
            if (sid != null) {
                java.util.List<String> hist = historyService.getFormattedRecentHistory(sid, 12);
                recent = (hist != null && !hist.isEmpty()) ? String.join("\n", hist) : null;
                java.util.Optional<String> la = historyService.getLastAssistantMessage(sid);
                lastAnswer = la.orElse(null);
            }
        } catch (Throwable __t) { /* best-effort only */ }
        PromptContext ctx = new PromptContext.Builder()
                .userQuery(userMsg)
                .history(recent)
                .lastAssistantAnswer(lastAnswer)
                .build();
        String prompt = promptBuilder.build(ctx);

        // Route using simplified overload (intent, risk, verbosity, maxTokens)
        Integer targetMaxTokens = 1024;
        ChatModel model = modelRouter.route("chat", "normal", "default", targetMaxTokens);
        String usedModel = modelRouter.resolveModelName(model);

        // Generate with LC4J 1.0.1 ChatModel API (generate -> chat)
        String content = model.chat(prompt);

        // Minimal evidence: none yet (empty collections preserved for API stability)
        Set<String> ev = new LinkedHashSet<>();

        boolean ragUsed = !ragSnippets.isEmpty();
        if (content == null || content.isBlank()) {
            content = "죄송해요, 이번에는 답변을 생성하지 못했어요.";
        }
        return new ChatResult(content, usedModel != null ? usedModel : "openai", ragUsed, ev);
    }

    @Override
    public void cancelSession(Long sessionId) {
        // no-op
    }

    /** Backwards compatible helper for WebSocket handler. */
    public ChatResult ask(String userMsg) {
        ChatRequestDto req = new ChatRequestDto();
        req.setMessage(userMsg);
        return continueChat(req, q -> Collections.emptyList());
    }
}
