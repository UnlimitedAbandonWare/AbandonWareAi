// src/main/java/com/example/lms/domain/scoring/SynergyStat.java
package com.example.lms.domain.scoring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Getter @Setter @NoArgsConstructor
@Table(indexes = @Index(name="idx_domain_pair", columnList = "domain,subject,partner", unique = true))
public class SynergyStat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String domain;   // "GENSHIN"
    private String subject;  // "푸리나"
    private String partner;  // "피슬"

    private long positive;   // 👍
    private long negative;   // 👎
    private Instant updatedAt = Instant.now();
}
