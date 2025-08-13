package com.example.lms.domain.knowledge;

import jakarta.persistence.*;
import lombok.*;
import java.util.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = {
        @Index(name = "idx_domain_type", columnList = "domain,entityType"),
        @Index(name = "uq_domain_name", columnList = "domain,entityName", unique = true)
})
public class DomainKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 지식의 도메인 (예: "GENSHIN", "STARRAIL")
     */
    private String domain;

    /**
     * 개체의 타입 (예: "CHARACTER", "WEAPON")
     */
    private String entityType;

    /**
     * 개체의 고유 이름 (예: "에스코피에", "푸리나")
     */
    @Column(unique = true) // 도메인과 조합하여 유니크해야 함
    private String entityName;

    /**
     * 이 개체에 속한 속성들의 목록 (예: Element=CRYO, Rarity=5)
     */
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EntityAttribute> attributes = new ArrayList<>();
}