package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 실제 구현체가 없을 때만 대체 등록되는 No-Op 구현.
 * ChatServiceImpl이 사용하는 read 계열은 빈/기본값을 돌려주고,
 * 나머지는 no-op으로 둡니다.
 */
@Service
@ConditionalOnMissingBean(ChatHistoryService.class)
public class NoopChatHistoryService implements ChatHistoryService {

    @Override
    public Optional<ChatSession> startNewSession(String firstMessage, String username) {
        return Optional.empty();
    }

    @Override
    public void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage) {
        // no-op
    }

    @Override
    public void appendMessage(Long sessionId, String role, String content) {
        // no-op
    }

    @Override
    public List<ChatSession> getAllSessionsForAdmin() {
        return Collections.emptyList();
    }

    @Override
    public List<ChatSession> getSessionsForUser(String username) {
        return Collections.emptyList();
    }

    @Override
    public ChatSession getSessionWithMessages(Long id) {
        // 최소 안전 구현: 빈 세션 객체 반환(도메인에 따라 필요시 보완)
        return new ChatSession();
    }

    @Override
    public void deleteSession(Long id) {
        // no-op
    }

    @Override
    public List<String> getFormattedRecentHistory(Long sessionId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public Optional<String> getLastAssistantMessage(Long sessionId) {
        return Optional.empty();
    }
}
