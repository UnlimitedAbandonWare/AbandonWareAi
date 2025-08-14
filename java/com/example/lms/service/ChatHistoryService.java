// src/main/java/com/example/lms/service/ChatHistoryService.java
package com.example.lms.service;

import com.example.lms.domain.ChatSession;

import java.util.List;
import java.util.Optional;

public interface ChatHistoryService {

    Optional<ChatSession> startNewSession(String firstMessage, String username);

    void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage);

    void appendMessage(Long sessionId, String role, String content);

    List<ChatSession> getAllSessionsForAdmin();

    List<ChatSession> getSessionsForUser(String username);

    ChatSession getSessionWithMessages(Long id);

    void deleteSession(Long id);

    List<String> getFormattedRecentHistory(Long sessionId, int limit);

    // [NEW] 가장 최근 assistant 메시지
    Optional<String> getLastAssistantMessage(Long sessionId);
}