package com.example.lms.service.knowledge;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 도메인 특화 지식을 데이터베이스에서 조회하고 관리하는 중앙 서비스 인터페이스입니다.
 * 이 서비스는 RAG 파이프라인의 여러 단계에서 '진실의 원천(Source of Truth)' 역할을 수행합니다.
 */
public interface KnowledgeBaseService {

    /**
     * 특정 게임 도메인의 페어링 정책을 나타내는 레코드입니다. (하위 호환성 유지)
     */
    record Policy(Set<String> allowed, Set<String> discouraged) {}

    /**
     * 특정 엔티티의 단일 속성 값을 조회합니다.
     * @param domain 엔티티가 속한 도메인 (예: "GENSHIN")
     * @param entityName 엔티티의 이름 (예: "에스코피에")
     * @param attributeKey 조회할 속성의 키 (예: "ELEMENT")
     * @return 속성 값의 Optional
     */
    Optional<String> getAttribute(String domain, String entityName, String attributeKey);

    /**
     * (Legacy) 특정 게임 캐릭터의 페어링 정책(허용/비선호 원소)을 반환합니다.
     * 내부적으로 getAllRelationships를 호출하여 특정 키(PAIRING_*)를 조회하는 방식으로 구현될 수 있습니다.
     * @param domain 게임 도메인
     * @param entityName 캐릭터 이름
     * @return 페어링 정책 객체
     */
    Policy getPairingPolicy(String domain, String entityName);

    /**
     * 주어진 엔티티(주어)와 연결된 모든 '관계(Relationship)' 정보를 Map 형태로 반환합니다.
     * 이 메서드는 시스템의 추론 능력의 핵심이며, 다양한 종류의 관계를 동적으로 처리할 수 있습니다.
     *
     * <p><b>- 데이터베이스 저장 방식:</b><br>
     * EntityAttribute 테이블의 attributeKey가 "RELATIONSHIP_"으로 시작하는 모든 항목을 조회합니다.<br>
     * - Key: attributeKey (예: "RELATIONSHIP_CONTAINS")<br>
     * - Value: 쉼표(,)나 공백으로 구분된 관련 엔티티 이름들 (예: "산소, 질소")
     * </p>
     *
     * <p><b>- 활용 예시 (범용):</b><br>
     * - <b>구성 관계 (Composition):</b><br>
     * - 주어: "공기" → `RELATIONSHIP_IS_COMPOSED_OF` → {"산소", "질소"}
     * - <b>분류 관계 (Taxonomy):</b><br>
     * - 주어: "푸들" → `RELATIONSHIP_IS_A` → {"개"}
     * - <b>대립 관계 (Antagonism):</b><br>
     * - 주어: "물" → `RELATIONSHIP_IS_ANTAGONISTIC_TO` → {"불"}
     * - <b>게임 시너지 관계 (Synergy):</b><br>
     * - 주어: "에스코피에" → `RELATIONSHIP_HAS_SYNERGY_WITH` → {"푸리나", "스커크"}
     * </p>
     *
     * @param domain     지식의 범위 (예: "GENSHIN", "SCIENCE", "PHILOSOPHY")
     * @param entityName 관계를 조회할 중심 엔티티(주어)의 이름
     * @return 관계의 종류를 Key로, 관련된 엔티티 Set을 Value로 갖는 Map
     */
    Map<String, Set<String>> getAllRelationships(String domain, String entityName);

    /**
     * 특정 도메인과 타입에 속하는 모든 엔티티의 이름 목록을 반환합니다.
     * @param domain 도메인
     * @param entityType 엔티티 타입 (예: "CHARACTER")
     * @return 엔티티 이름의 Set
     */
    Set<String> listEntityNames(String domain, String entityType);

    /**
     * 주어진 텍스트 내에서 언급된, 해당 도메인에 속한 모든 엔티티를 찾습니다.
     * @param domain 도메인
     * @param text 분석할 텍스트
     * @return 발견된 엔티티 이름의 Set
     */
    Set<String> findMentionedEntities(String domain, String text);

    /**
     * 주어진 텍스트에서 주어(subject)를 제외하고 처음으로 언급된 엔티티를 찾습니다.
     * @param domain 도메인
     * @param text 분석할 텍스트
     * @param subject 제외할 주어
     * @return 발견된 첫 번째 파트너 엔티티의 Optional
     */
    default Optional<String> findFirstMentionedEntityExcluding(String domain, String text, String subject) {
        return findMentionedEntities(domain, text).stream()
                .filter(n -> subject == null || !n.equalsIgnoreCase(subject))
                .findFirst();
    }
    /**
     * ✨ 기본 도메인 추정(휴리스틱). 구현체가 따로 제공하지 않아도 바로 사용 가능.
     */
    default String inferDomain(String userPrompt) {
        if (userPrompt == null) return "GENERAL";
        String s = userPrompt.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("학원") || s.contains("아카데미") || s.contains("academy")) return "EDU";
        if (s.contains("가격") || s.contains("스펙") || s.matches(".*\\b[a-z]{1,4}\\d+[a-z]*\\b.*")) return "PRODUCT";
        return "GENERAL";
    }
}