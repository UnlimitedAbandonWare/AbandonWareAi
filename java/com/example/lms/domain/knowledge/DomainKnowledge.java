// src/main/java/com/example/lms/domain/knowledge/DomainKnowledge.java
package com.example.lms.domain.knowledge;

import jakarta.persistence.*;
import lombok.*;
import java.util.*;

@Entity @Getter @Setter @NoArgsConstructor
@Table(indexes = {
        @Index(name="idx_domain_type", columnList = "domain,entityType"),
        @Index(name="uq_domain_name", columnList = "domain,entityName", unique = true)
})
public class DomainKnowledge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String domain;      // e.g., "GENSHIN"
    private String entityType;  // e.g., "CHARACTER"
    private String entityName;  // e.g., "푸리나"

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EntityAttribute> attributes = new ArrayList<>();
}
