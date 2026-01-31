package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

/**
 * Thin orchestration facade.
 * <p>
 * The heavy pipeline lives in {@link ChatWorkflow}. This class exists to keep
 * the
 * public surface stable for controllers and to provide a compact, compile-time
 * visible
 * API (incl. {@link ChatResult}) while avoiding a God-class.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatWorkflow workflow;

    public ChatResult continueChat(ChatRequestDto req) {
        return workflow.continueChat(req);
    }

    public ChatResult continueChat(ChatRequestDto req, Function<String, List<String>> externalCtxProvider) {
        return workflow.continueChat(req, externalCtxProvider);
    }

    public ChatResult ask(String userMsg) {
        return workflow.ask(userMsg);
    }

    /** Called by /api/chat/cancel */
    public void cancelSession(Long sessionId) {
        workflow.cancelSession(sessionId);
    }

    /**
     * Build a composite cache key from a chat request.
     * <p>
     * Kept here because the cache SpEL expression references this class.
     */
    public static String cacheKey(ChatRequestDto req) {
        if (req == null)
            return "";
        String sid = String.valueOf(req.getSessionId());
        String model = String.valueOf(req.getModel());
        String msg = String.valueOf(req.getMessage());
        String rag = String.valueOf(req.isUseRag());
        String web = String.valueOf(req.isUseWebSearch());
        return String.format("%s:%s:%s:%s:%s", sid, model, msg, rag, web);
    }
}
