// src/main/java/com/example/lms/domain/TrainingJob.java
package com.example.lms.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;




@Entity
@Getter @Setter @NoArgsConstructor
public class TrainingJob {

    @Id @GeneratedValue
    private Long id;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private long total;        // 총 샘플 수
    private long processed;    // 처리된 샘플 수
    private String status;     // RUNNING | COMPLETED | FAILED
    private String message;    // 오류 메시지 등
}