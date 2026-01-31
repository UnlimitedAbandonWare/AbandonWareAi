package com.example.lms.domain.knowledge;

import jakarta.persistence.*;
import lombok.*;



@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = @Index(name = "idx_owner_key", columnList = "owner_id,attributeKey"))
public class EntityAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이 속성이 속한 부모 개체 (예: '에스코피에' 캐릭터)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private DomainKnowledge owner;

    /**
     * 속성의 키 (예: "ELEMENT", "WEAPON_TYPE", "PAIRING_POLICY_ALLOW")
     */
    private String attributeKey;

    /**
     * 속성의 값. 쉼표(,)를 사용해 다중 값을 저장할 수 있습니다.
     * (예: "HYDRO", 또는 "HYDRO,ELECTRO")
     */
    @Lob
    private String attributeValue;
}