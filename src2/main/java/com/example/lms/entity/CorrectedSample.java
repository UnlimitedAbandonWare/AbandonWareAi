// src/main/java/com/example/lms/entity/CorrectedSample.java
package com.example.lms.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Setter @NoArgsConstructor
@Table(name = "corrected_sample")
public class CorrectedSample {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String source;

    @Column(nullable = false, length = 1000)
    private String translated;   // (= 최종 교정본)

    /** TM 반영 전 = true */
    private boolean dirty = true;

    /** TM 에 반영됐음을 표시 */
    public void markAsLearned() { this.dirty = false; }
}
