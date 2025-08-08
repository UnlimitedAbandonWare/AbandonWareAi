package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import java.util.List;
import java.util.Optional;

public interface ChatHistoryService {
    Optional<ChatSession> startNewSession(String firstMessage, String userEmail);
    void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage);
    List<ChatSession> getAllSessionsForAdmin();
    List<ChatSession> getSessionsForUser(String username);
    ChatSession getSessionWithMessages(Long id);
    void deleteSession(Long id);
    void appendMessage(Long sessionId, String role, String content);

}