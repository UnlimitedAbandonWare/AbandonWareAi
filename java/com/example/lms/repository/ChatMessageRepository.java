package com.example.lms.repository;

import java.util.List;                     // ← 이걸 추가
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.lms.domain.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionId(Long sessionId);

    /** createdAt ASC 로 정렬해서 반환 (대화 순서 보존) */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
    void deleteAllBySession_Id(Long sessionId);

}
