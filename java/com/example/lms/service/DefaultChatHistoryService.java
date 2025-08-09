
package com.example.lms.service;

import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.example.lms.domain.ChatSession;  // ← 엔티티 패키지 경로 확인
@Slf4j
@Service
@Profile("stub")
public class DefaultChatHistoryService implements ChatHistoryService {


    @Override
    public java.util.Optional<ChatSession> startNewSession(String firstMessage, String userEmail) {
        log.debug("[ChatHistory] startNewSession user={} (stub)", userEmail);
        return java.util.Optional.empty();
    }

    @Override
    public void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage) {
        log.debug("[ChatHistory] addMessagesToSession id={} (stub)",
                session != null ? session.getId() : null);
    }


    @Override
    public List<String> getFormattedRecentHistory(Long sessionId, int limit) {
        if (sessionId == null || limit <= 0) return Collections.emptyList();
        log.debug("[ChatHistory] sid={}, limit={} -> empty", sessionId, limit);
        return Collections.emptyList();
    }

    @Override
    public void appendMessage(Long sessionId, String role, String content) {
        // 기본 구현: 저장소 미구현 상태이므로 no-op (로그만 남김)
        if (sessionId == null) {
            log.debug("[ChatHistory] skip append: null sid (role={}, len={})",
                    role, content != null ? content.length() : 0);
            return;
        }
        log.debug("[ChatHistory] append sid={}, role={}, len={}",
                sessionId, role, content != null ? content.length() : 0);
    }

    // ▼ 인터페이스 시그니처와 **반드시 동일**해야 합니다.
    // 1) 반환형이 void 인 경우
    @Override
    public void deleteSession(Long sessionId) {
        log.debug("[ChatHistory] delete sid={}", sessionId);
        // no-op
    }

    @Override
    public ChatSession getSessionWithMessages(Long sessionId) {
        if (sessionId == null) {
            log.debug("[ChatHistory] getSessionWithMessages: null sid -> empty");
            return null; // 스텁: 저장소 미구현
        }
        log.debug("[ChatHistory] getSessionWithMessages sid={}", sessionId);
        // 저장소 미구현: no-op 스텁
        return null;
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
        return Collections.emptyList(); // 스텁
    }

    // 2) 만약 인터페이스가 boolean 을 요구한다면 위 메서드를 지우고 아래로 교체
    // @Override
    // public boolean deleteSession(Long sessionId) {
    //     log.debug("[ChatHistory] delete sid={}", sessionId);
    //     return true; // 저장소 미구현 상태의 기본 성공 처리
    // }


    // 선택: override 안 해도 됨(인터페이스 default)
    // @Override
    // public List<ChatSession> getAllSessionsForAdmin() {
    //     return Collections.emptyList();
    // }
}
