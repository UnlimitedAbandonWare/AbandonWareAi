package com.example.lms.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class TrainingJob {
    @Id
    @GeneratedValue
    private Long id;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private long total;
    private long processed;
    private String status;
    private String message;
}
