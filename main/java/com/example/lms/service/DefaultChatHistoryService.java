// src/main/java/com/example/lms/service/DefaultChatHistoryService.java
package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

@Service
@Profile("shim")
public class DefaultChatHistoryService implements ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(DefaultChatHistoryService.class);

    @Override
    public Optional<ChatSession> startNewSession(String firstMessage, String userEmail, String clientIp) {
        log.debug("[ChatHistory] startNewSession userHash={} (shim)", hashUser(userEmail));
        return Optional.empty();
    }

    @Override
    public void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage) {
        log.debug("[ChatHistory] addMessagesToSession sessionHash={} (shim)",
                session != null ? hashSession(session.getId()) : "");
    }

    @Override
    public void appendMessage(Long sessionId, String role, String content) {
        if (sessionId == null) {
            log.debug("[ChatHistory] skip append: null sid (role={}, len={})",
                    role, content != null ? content.length() : 0);
            return;
        }
        log.debug("[ChatHistory] append sessionHash={}, role={}, len={}",
                hashSession(sessionId), role, content != null ? content.length() : 0);
    }

    @Override
    public Long appendMessageReturningId(Long sessionId, String role, String content) {
        appendMessage(sessionId, role, content);
        return null;
    }

    @Override
    public void updateSessionAnswerModeAndTrace(Long sessionId, String answerMode, Long traceTurnId) {
        log.debug("[ChatHistory] updateSessionAnswerModeAndTrace sessionHash={}, mode={}, traceTurnId={} (shim)",
                hashSession(sessionId), answerMode, traceTurnId);
    }

    @Override
    public void updateSessionMeta(Long sessionId, java.util.Map<String, Object> meta) {
        log.debug("[ChatHistory] updateSessionMeta sessionHash={}, keyCount={} (shim)",
                hashSession(sessionId), meta == null ? 0 : meta.size());
    }

    @Override
    public List<ChatSession> getAllSessionsForAdmin() {
        log.debug("[ChatHistory] getAllSessionsForAdmin -> []");
        return Collections.emptyList();
    }

    @Override
    public List<ChatSession> getSessionsForUser(String username) {
        if (username == null || username.isBlank()) {
            log.debug("[ChatHistory] getSessionsForUser: blank username -> []");
            return Collections.emptyList();
        }
        log.debug("[ChatHistory] getSessionsForUser userHash={}", hashUser(username));
        return Collections.emptyList();
    }

    @Override
    public ChatSession getSessionWithMessages(Long id) {
        log.debug("[ChatHistory] getSessionWithMessages sessionHash={} (shim)", hashSession(id));
        return null;
    }

    @Override
    public void deleteSession(Long id) {
        log.debug("[ChatHistory] delete sessionHash={} (shim)", hashSession(id));
    }

    @Override
    public List<String> getFormattedRecentHistory(Long sessionId, int limit) {
        if (sessionId == null || limit <= 0)
            return Collections.emptyList();
        log.debug("[ChatHistory] sessionHash={}, limit={} -> empty", hashSession(sessionId), limit);
        return Collections.emptyList();
    }

    @Override
    public Optional<String> getRollingSummary(Long sessionId) {
        log.debug("[ChatHistory] getRollingSummary sessionHash={} -> empty (shim)", hashSession(sessionId));
        return Optional.empty();
    }

    @Override
    public void updateRollingSummary(Long sessionId, Long lastMessageId) {
        log.debug("[ChatHistory] updateRollingSummary sessionHash={}, hasLastMessageId={} (shim)",
                hashSession(sessionId), lastMessageId != null);
    }

    private static String hashSession(Long sessionId) {
        return sessionId == null ? "" : com.example.lms.trace.SafeRedactor.hash12(String.valueOf(sessionId));
    }

    // ★ 새 인터페이스 메서드 구현
    @Override
    public Optional<String> getLastAssistantMessage(Long sessionId) {
        log.debug("[ChatHistory] getLastAssistantMessage sessionHash={} -> empty (shim)", hashSession(sessionId));
        return Optional.empty();
    }

    // ★ 5-파라미터 startNewSession 구현 (shim)
    @Override
    public Optional<ChatSession> startNewSession(
            String firstMessage,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            com.example.lms.domain.enums.MemoryProfile memoryProfile) {
        log.debug("[ChatHistory] startNewSession (5-param) userHash={}, ownerKeyHash={}, profile={} (shim)",
                hashUser(username), hashOwnerKey(preResolvedOwnerKey), memoryProfile);
        return Optional.empty();
    }

    private static String hashUser(String user) {
        return user == null || user.isBlank()
                ? ""
                : com.example.lms.trace.SafeRedactor.hash12(user);
    }

    private static String hashOwnerKey(String ownerKey) {
        return ownerKey == null || ownerKey.isBlank()
                ? ""
                : com.example.lms.trace.SafeRedactor.hash12(ownerKey);
    }
}
