package com.example.lms.repository;

import com.example.lms.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;




/**
 * ChatSession JPA Repository
 * - 최신 생성 순으로 모든 세션 조회
 * - 관리자(Administrator) username으로 세션 조회
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 생성일시 내림차순으로 모든 세션 조회
     */
    List<ChatSession> findAllByOrderByCreatedAtDesc();

    /**
     * 관리자 username 기준으로 생성일시 내림차순 조회
     * @param username 관리자 아이디
     */
    List<ChatSession> findByAdministrator_UsernameOrderByCreatedAtDesc(String username);



/**
 * 게스트/비회원 소유 키 기준으로 생성일시 내림차순 조회
 * @param ownerKey 소유자 키
 */
List<ChatSession> findByOwnerKeyOrderByCreatedAtDesc(String ownerKey);

}