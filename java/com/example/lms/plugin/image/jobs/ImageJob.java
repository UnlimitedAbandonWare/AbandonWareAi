package com.example.lms.plugin.image.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;




/**
 * Persistence entity representing a single image generation job.  Jobs
 * progress through a lifecycle of statuses (PENDING → IN_PROGRESS →
 * SUCCEEDED/FAILED).  Each job records timestamps for creation,
 * start and completion along with the duration of the work.  When
 * successful the generated file is referenced via {@code filePath}
 * and {@code publicUrl}.
 */
@Entity
@Table(name = "image_job")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageJob {
    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 1000)
    private String prompt;

    @Column(length = 40)
    private String model;

    @Column(length = 20)
    private String size;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status;

    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    @Column(length = 1024)
    private String filePath;
    @Column(length = 1024)
    private String publicUrl;
    @Column(length = 512)
    private String reason;

    /**
     * Enumeration of possible job states.
     */
    public enum Status {
        PENDING,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }
}