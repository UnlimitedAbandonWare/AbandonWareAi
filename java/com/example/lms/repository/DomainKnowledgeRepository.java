// src/main/java/com/example/lms/repository/DomainKnowledgeRepository.java
package com.example.lms.repository;

import com.example.lms.domain.knowledge.DomainKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface DomainKnowledgeRepository extends JpaRepository<DomainKnowledge, Long> {
    Optional<DomainKnowledge> findByDomainAndEntityNameIgnoreCase(String domain, String entityName);
    List<DomainKnowledge> findByDomainAndEntityType(String domain, String entityType);
}
