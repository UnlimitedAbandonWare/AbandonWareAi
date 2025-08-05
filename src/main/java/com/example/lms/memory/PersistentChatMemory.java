package com.example.lms.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
// TextContent import 제거

import jakarta.transaction.Transactional;        // 또는 org.springframework.transaction.annotation.Transactional
import lombok.RequiredArgsConstructor;

import com.example.lms.domain.ChatSession;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;

import java.util.List;

@RequiredArgsConstructor
public class PersistentChatMemory implements ChatMemory {

    private final String sessionId;
    private final ChatMessageRepository msgRepo;
    private final ChatSessionRepository sesRepo;

    /* 현재까지의 메시지 가져오기 */
    @Override
    @Transactional
    public List<ChatMessage> messages() {
        return msgRepo.findBySessionIdOrderByCreatedAtAsc(Long.valueOf(sessionId))
                .stream()
                .map(m -> "user".equals(m.getRole())
                        ? UserMessage.from(m.getContent())
                        : AiMessage.from(m.getContent()))
                .toList();
    }


    @Override
    public String id() {
        return sessionId;
    }

    // ① import·필드 부분 그대로

    // src/main/java/com/example/lms/memory/PersistentChatMemory.java
// src/main/java/com/example/lms/memory/PersistentChatMemory.java

    @Override
    @Transactional
    public void add(ChatMessage m) {

        ChatSession session = sesRepo.getReferenceById(Long.valueOf(sessionId));
        String role = (m instanceof UserMessage) ? "user" : "assistant";

        // --- 최종 수정: 1.0.x API 전용 ---
        String text;                     // ← 공통 변수
        if (m instanceof UserMessage um) {

            text = um.singleText();          // ← 유일한 텍스트 가져오기
        } else if (m instanceof AiMessage am) {
            text = (am.text() != null) ? am.text() : am.toString();
        } else {                         // 다른 서브타입 대비(혹시 모를 시스템 메시지 등)
            text = m.toString();
        }
        msgRepo.save(new com.example.lms.domain.ChatMessage(session, role, text));
    }
    // unwrap() 삭제 – singleText()/text()로 충분
    /* 메모리 클리어 */
    @Override
    @Transactional
    public void clear() {
        // 🔧 deleteBySessionId → deleteAllBySession_Id
        msgRepo.deleteAllBySession_Id(Long.valueOf(sessionId));
    }

}
