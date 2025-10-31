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
        log.debug("[ChatHistory] startNewSession user={} (shim)", userEmail);
        return Optional.empty();
    }

    @Override
    public void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage) {
        log.debug("[ChatHistory] addMessagesToSession id={} (shim)",
                session != null ? session.getId() : null);
    }

    @Override
    public void appendMessage(Long sessionId, String role, String content) {
        if (sessionId == null) {
            log.debug("[ChatHistory] skip append: null sid (role={}, len={})",
                    role, content != null ? content.length() : 0);
            return;
        }
        log.debug("[ChatHistory] append sid={}, role={}, len={}",
                sessionId, role, content != null ? content.length() : 0);
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
        log.debug("[ChatHistory] getSessionsForUser user={}", username);
        return Collections.emptyList();
    }

    @Override
    public ChatSession getSessionWithMessages(Long id) {
        log.debug("[ChatHistory] getSessionWithMessages sid={} (shim)", id);
        return null;
    }

    @Override
    public void deleteSession(Long id) {
        log.debug("[ChatHistory] delete sid={} (shim)", id);
    }

    @Override
    public List<String> getFormattedRecentHistory(Long sessionId, int limit) {
        if (sessionId == null || limit <= 0) return Collections.emptyList();
        log.debug("[ChatHistory] sid={}, limit={} -> empty", sessionId, limit);
        return Collections.emptyList();
    }

    // ★ 새 인터페이스 메서드 구현
    @Override
    public Optional<String> getLastAssistantMessage(Long sessionId) {
        log.debug("[ChatHistory] getLastAssistantMessage sid={} -> empty (shim)", sessionId);
        return Optional.empty();
    }
}