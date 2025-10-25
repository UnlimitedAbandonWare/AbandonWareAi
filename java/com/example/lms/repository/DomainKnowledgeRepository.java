package com.example.lms.repository;

import com.example.lms.domain.knowledge.DomainKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;



/**
 * DomainKnowledge 엔티티에 대한 데이터 접근을 처리하는 Spring Data JPA 리포지토리입니다.
 */
public interface DomainKnowledgeRepository extends JpaRepository<DomainKnowledge, Long> {

    /**
     * 지정된 도메인과 개체 이름(대소문자 무시)으로 DomainKnowledge 엔티티를 찾습니다.
     *
     * @param domain     검색할 도메인 (예: "GENSHIN")
     * @param entityName 검색할 개체 이름 (예: "에스코피에")
     * @return 해당 조건에 맞는 DomainKnowledge Optional 객체
     */
    Optional<DomainKnowledge> findByDomainAndEntityNameIgnoreCase(String domain, String entityName);

    /**
     * 지정된 도메인과 개체 타입에 해당하는 모든 DomainKnowledge 엔티티 목록을 반환합니다.
     *
     * @param domain     검색할 도메인 (예: "GENSHIN")
     * @param entityType 검색할 개체 타입 (예: "CHARACTER")
     * @return 해당 조건에 맞는 DomainKnowledge 엔티티 리스트
     */
    List<DomainKnowledge> findByDomainAndEntityType(String domain, String entityType);
}