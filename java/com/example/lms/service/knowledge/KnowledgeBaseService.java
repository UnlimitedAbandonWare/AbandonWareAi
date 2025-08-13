// src/main/java/com/example/lms/service/knowledge/KnowledgeBaseService.java
package com.example.lms.service.knowledge;

import java.util.*;

public interface KnowledgeBaseService {

    record Policy(Set<String> allowed, Set<String> discouraged) {}

    Optional<String> getAttribute(String domain, String entityName, String attributeKey);

    /** 도메인/주어 기준의 궁합 정책(없으면 도메인 규칙 또는 안전 기본값) */
    Policy getPairingPolicy(String domain, String entityName);

    /** 도메인의 알려진 엔티티 이름 목록(주어 추정/파트너 추출에 사용) */
    Set<String> listEntityNames(String domain, String entityType);

    /** 텍스트 내에서 도메인 엔티티가 언급되었는지 추출 */
    Set<String> findMentionedEntities(String domain, String text);

    /** 텍스트에서 (subject 제외) 최초로 발견된 파트너 1개 */
    default Optional<String> findFirstMentionedEntityExcluding(String domain, String text, String subject) {
        return findMentionedEntities(domain, text).stream()
                .filter(n -> subject == null || !n.equalsIgnoreCase(subject))
                .findFirst();
    }
}
