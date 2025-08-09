package com.example.lms.repository;

import com.example.lms.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    /**
     * 생성 시간의 역순으로 모든 세션을 조회합니다.
     * @return 시간순으로 정렬된 ChatSession 목록
     */
    List<ChatSession> findAllByOrderByCreatedAtDesc();
}
