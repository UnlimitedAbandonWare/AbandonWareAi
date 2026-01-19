package com.example.lms.repository;

import java.util.List;
import com.example.lms.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
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


    java.util.List<com.example.lms.domain.ChatMessage> findByRoleOrderByIdDesc(String role, Pageable pageable);


    java.util.Optional<com.example.lms.domain.ChatMessage> findTopByOrderByCreatedAtDesc();


    /**
     * 최근 메시지를 빠르게 스캔하기 위한 DESC 정렬 조회 (rolling summary/chunking 등).
     *
     * <p>Pageable을 통해 DB에서 필요한 개수만 가져오도록 하여,
     * 세션 메시지가 많아도 서버 부하를 최소화합니다.</p>
     */
    List<ChatMessage> findBySession_IdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    // ----- RollingSummary (RSUM) helpers -----
    /** Latest RSUM system message for the session (fast, id-desc). */
    Optional<ChatMessage> findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
            Long sessionId, String role, String contentPrefix);

    /** Messages after a given message id (asc) with optional paging (fast, id-asc). */
    List<ChatMessage> findBySession_IdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long id, Pageable pageable);

}
