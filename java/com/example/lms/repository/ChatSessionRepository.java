package com.example.lms.repository;

import com.example.lms.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * ChatSession JPA Repository
 * - 최신 생성 순으로 모든 세션 조회
 * - 관리자(Administrator) username으로 세션 조회
 * - 게스트/비회원 ownerKey 기반 조회
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 생성일시 기준 전체 세션 목록 (최신순)
     */
    List<ChatSession> findAllByOrderByCreatedAtDesc();

    /**
     * 관리자 username 기준 세션 목록 (최신순)
     */
    List<ChatSession> findByAdministrator_UsernameOrderByCreatedAtDesc(String username);

    /**
     * 게스트/비회원 소유 키 기준으로 생성일시 내림차순 조회
     *
     * @param ownerKey 소유자 키
     */
    List<ChatSession> findByOwnerKeyOrderByCreatedAtDesc(String ownerKey);

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    /**
     * [IMPROVED] 다중 소유 키 기반 조회 (게스트 식별 강화용)
     * 쿠키 ID 또는 IP 해시 중 하나라도 일치하면 조회합니다.
     */
    List<ChatSession> findByOwnerKeyInOrderByCreatedAtDesc(Collection<String> ownerKeys);
}
