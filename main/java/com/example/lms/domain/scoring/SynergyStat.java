package com.example.lms.domain.scoring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;



@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = @Index(name="idx_domain_pair", columnList = "domain,subject,partner", unique = true))
public class SynergyStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String domain;   // e.g., "GENSHIN"
    private String subject;  // e.g., "에스코피에"
    private String partner;  // e.g., "푸리나"

    private long positive;   // 👍
    private long negative;   // 👎

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}