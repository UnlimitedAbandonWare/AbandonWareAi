// src/main/java/com/example/lms/service/ChatHistoryService.java
package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import java.util.List;
import java.util.Optional;
import com.example.lms.domain.enums.MemoryProfile;




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

    /**
     * Start a new session while explicitly providing an already resolved ownerKey
     * and an optional MemoryProfile.  This is useful for reactive / SSE flows
     * where the HttpServletRequest is not available any more and the caller
     * has already resolved the owner identity.
     */
    Optional<ChatSession> startNewSession(
            String firstMessage,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            MemoryProfile memoryProfile);

    void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage);

    void appendMessage(Long sessionId, String role, String content);

    /**
     * Append a message and return the DB-generated message id (turnId) when available.
     *
     * <p>Used for deterministic trace auto-open (traceTurnId targeting) and other
     * UI debugging features that require a stable turn identifier.</p>
     */
    Long appendMessageReturningId(Long sessionId, String role, String content);

    /**
     * Persist the latest answer.mode and traceTurnId snapshot on the session so that:
     * <ul>
     *   <li>session list badges can be restored cross-device (server as source-of-truth)</li>
     *   <li>sidebar badge clicks can open the exact trace panel (turnId targeting)</li>
     * </ul>
     */
    void updateSessionAnswerModeAndTrace(Long sessionId, String answerMode, Long traceTurnId);

    List<ChatSession> getAllSessionsForAdmin();

    List<ChatSession> getSessionsForUser(String username);

    ChatSession getSessionWithMessages(Long id);

    void deleteSession(Long id);

    List<String> getFormattedRecentHistory(Long sessionId, int limit);

    // [NEW] 가장 최근 assistant 메시지
    Optional<String> getLastAssistantMessage(Long sessionId);
}