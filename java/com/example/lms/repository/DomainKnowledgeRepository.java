package com.example.lms.repository;

import com.example.lms.domain.knowledge.DomainKnowledge;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * DomainKnowledge 엔티티는 현재 entityName을 전역 유니크로 선언하고 있습니다.
     * (도메인별 유니크가 아닌) 이 제약으로 인해 동일 entityName이 이미 존재할 경우
     * 도메인별 조회가 실패할 수 있어, 오버레이(예: Nova KB persist AOP)에서 폴백 조회로 사용합니다.
     */
    Optional<DomainKnowledge> findByEntityNameIgnoreCase(String entityName);

    /**
     * 지정된 도메인과 개체 타입에 해당하는 모든 DomainKnowledge 엔티티 목록을 반환합니다.
     *
     * @param domain     검색할 도메인 (예: "GENSHIN")
     * @param entityType 검색할 개체 타입 (예: "CHARACTER")
     * @return 해당 조건에 맞는 DomainKnowledge 엔티티 리스트
     */
    List<DomainKnowledge> findByDomainAndEntityType(String domain, String entityType);

    /**
     * 모든 고유한 도메인 목록을 반환합니다.
     */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT dk.domain FROM DomainKnowledge dk")
    List<String> findAllDomains();

    /**
     * 특정 도메인에 속한 모든 고유 엔티티 타입 목록을 반환합니다.
     */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT dk.entityType FROM DomainKnowledge dk WHERE dk.domain = :domain")
    List<String> findEntityTypesByDomain(@org.springframework.data.repository.query.Param("domain") String domain);

    /**
     * 특정 도메인과 엔티티 타입에 속한 모든 엔티티 이름 목록을 반환합니다.
     */
    @org.springframework.data.jpa.repository.Query("SELECT dk.entityName FROM DomainKnowledge dk WHERE dk.domain = :domain AND dk.entityType = :entityType")
    List<String> findEntitiesByDomainAndType(@org.springframework.data.repository.query.Param("domain") String domain,
            @org.springframework.data.repository.query.Param("entityType") String entityType);


    @Query("SELECT DISTINCT dk FROM DomainKnowledge dk JOIN dk.attributes a WHERE a.attributeKey = :key AND a.attributeValue = :value")
    List<DomainKnowledge> findByAttributeValue(@Param("key") String key, @Param("value") String value, Pageable pageable);

}
