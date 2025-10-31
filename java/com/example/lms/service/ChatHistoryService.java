// src/main/java/com/example/lms/service/ChatHistoryService.java
package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import java.util.List;
import java.util.Optional;




public interface ChatHistoryService {

    /**
     * Start a new chat session.  When a user is not authenticated (anonymous)
     * the session is assigned an owner key derived from the provided client
     * IP.  When no client IP is available the owner key falls back to a
     * random UUID.
     *
     * @param firstMessage the initial user message used to derive the session title
     * @param username the authenticated username (or {@code null} / "anonymousUser" for guests)
     * @param clientIp the client IP address extracted from the request; may be null
     * @return the newly created session wrapped in an Optional
     */
    Optional<ChatSession> startNewSession(String firstMessage, String username, String clientIp);

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