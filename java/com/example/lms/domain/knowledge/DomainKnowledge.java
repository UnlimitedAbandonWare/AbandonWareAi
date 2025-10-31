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

    /**
     * 마지막으로 이 지식이 조회된 시간입니다. 지식 감쇠(decay) 로직에서 사용됩니다.
     * 신규 엔티티는 생성 시점으로 초기화되며, getAttribute 또는 getAllRelationships 호출 시 갱신됩니다.
     */
    @Column(nullable = false)
    private java.time.Instant lastAccessedAt = java.time.Instant.now();

    /**
     * 지식의 신뢰도 점수입니다. 0.0~1.0 범위이며, Reinforcement 및 감쇠 로직에서 조정됩니다.
     * 신규 엔티티의 기본값은 1.0입니다.
     */
    @Column(nullable = false)
    private double confidenceScore = 1.0;
}