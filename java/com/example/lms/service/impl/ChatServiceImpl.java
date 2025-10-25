package com.example.lms.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.service.ChatService;
import com.example.lms.service.ChatService.ChatResult;
import com.example.lms.dto.ChatRequestDto;
import java.util.List;
import java.util.function.Function;





/**
 * 안전 스텁 구현:
 * - 외부 오케스트레이터가 없어도 컴파일/실행 가능
 * - 존재한다면 선택 주입(Object)으로 연동 지점 제공
 */
@Service
@ConditionalOnMissingBean(com.example.lms.service.ChatService.class)
public class ChatServiceImpl {

    @Autowired(required = false)
    private Object orchestrator; // optional

    public ChatServiceImpl() { }
    public ChatResult continueChat(ChatRequestDto req) {
        // 오케스트레이터가 있다면 이 자리에서 위임하도록 교체하세요.
        // return toServiceResult(orchestrator.continueChat(req));
        String echo = (req != null && req.getMessage() != null) ? req.getMessage() : "";
        return ChatResult.of("[stub] " + echo, "default", false);
    }
    public ChatResult continueChat(ChatRequestDto req,
                                   Function<String, List<String>> snippetProvider) {
        // 필요 시 snippetProvider를 활용
        return continueChat(req);
    }
    public ChatResult ask(String userMessage) {
        // 오케스트레이터가 있다면:
        // return toServiceResult(orchestrator.ask(userMessage));
        String echo = (userMessage == null) ? "" : userMessage;
        return ChatResult.of("[stub] " + echo, "default", false);
    }
    public void cancelSession(Long sessionId) {
        // 오케스트레이터가 있다면: orchestrator.cancelSession(sessionId);
    }

    @SuppressWarnings("all")
    private ChatResult toServiceResult(Object r) {
        if (r == null) return ChatResult.of("", "default", false);
        try {
            String content = String.valueOf(r.getClass().getMethod("content").invoke(r));
            String model   = String.valueOf(r.getClass().getMethod("modelUsed").invoke(r));
            Object ragObj  = r.getClass().getMethod("ragUsed").invoke(r);
            boolean rag    = (ragObj instanceof Boolean) ? (Boolean) ragObj : false;
            return ChatResult.of(content, model, rag);
        } catch (Exception ignored) {
            return ChatResult.of(String.valueOf(r), "default", false);
        }
    }
}