package com.example.lms.repository;

import java.util.List;
import com.example.lms.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;





public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionId(Long sessionId);

    /** createdAt ASC 로 정렬해서 반환 (대화 순서 보존) */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
    // 세션 연관을 타고 들어가는 안전한 파생 쿼리
    List<ChatMessage> findBySession_Id(Long sessionId);
    List<ChatMessage> findBySession_IdOrderByCreatedAtAsc(Long sessionId);

    void deleteAllBySession_Id(Long sessionId);

    Optional<ChatMessage> findTopBySessionIdAndRoleOrderByCreatedAtDesc(Long sessionId, String role);

    Optional<ChatMessage> findTopBySessionIdAndRoleOrderByIdDesc(Long sessionId, String role);
}