package com.example.lms.service;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    /**
     * 새로운 대화 세션을 생성합니다.
     * @param firstMessage 사용자의 첫 번째 메시지 (제목으로 사용)
     * @return 생성된 ChatSession 객체
     */
    @Transactional
    public ChatSession startNewSession(String firstMessage) {
        // 첫 메시지의 일부를 제목으로 사용
        String title = firstMessage.length() > 20 ? firstMessage.substring(0, 20) + "..." : firstMessage;
        ChatSession session = new ChatSession(title);
        log.info("새로운 대화 세션을 시작합니다. Title: {}", title);
        return sessionRepository.save(session);
    }

    /**
     * 특정 세션에 사용자 메시지와 AI 응답 메시지를 저장합니다.
     * @param session 메시지를 추가할 세션
     * @param userMessage 사용자가 입력한 메시지
     * @param assistantMessage AI가 응답한 메시지
     */
    @Transactional
    public void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage) {
        ChatMessage userMsg = new ChatMessage(session, "user", userMessage);
        ChatMessage assistantMsg = new ChatMessage(session, "assistant", assistantMessage);
        messageRepository.saveAll(List.of(userMsg, assistantMsg));
        log.info("세션 ID {}: 사용자 및 어시스턴트 메시지 저장 완료", session.getId());
    }

    /**
     * 모든 대화 세션 목록을 조회합니다. (최신순)
     * @return ChatSession 목록
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getAllSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 특정 세션의 모든 메시지를 포함하여 조회합니다.
     * @param sessionId 조회할 세션 ID
     * @return 메시지가 포함된 ChatSession 객체
     */
    @Transactional(readOnly = true)
    public ChatSession getSessionWithMessages(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + sessionId));
    }
}
