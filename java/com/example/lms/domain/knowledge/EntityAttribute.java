// src/main/java/com/example/lms/domain/knowledge/EntityAttribute.java
package com.example.lms.domain.knowledge;

import jakarta.persistence.*;
import lombok.*;

@Entity @Getter @Setter @NoArgsConstructor
@Table(indexes = @Index(name="idx_owner_key", columnList = "owner_id,attributeKey"))
public class EntityAttribute {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name="owner_id")
    private DomainKnowledge owner;

    private String attributeKey;   // "ELEMENT", "PAIRING_POLICY_ALLOW", "PAIRING_POLICY_DISCOURAGE"
    @Lob
    private String attributeValue; // e.g., "HYDRO" or "HYDRO,ELECTRO"
}
