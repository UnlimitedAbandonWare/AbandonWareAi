package com.example.lms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DLQ table for "shadow-write" staged vectors that must be merged into the stable/global index
 * only after verification gates are satisfied.
 *
 * <p>Unlike quarantine DLQ (infra/poison), this DLQ represents a deliberate staging mechanism to
 * prevent hub-vector pollution.</p>
 */
@Entity
@Table(name = "vector_shadow_merge_dlq", indexes = {
        @Index(name = "idx_vshadow_status_next", columnList = "status,next_attempt_at"),
        @Index(name = "idx_vshadow_dedupe", columnList = "dedupe_key")
})
@Getter
@Setter
public class VectorShadowMergeDlq {

    public enum Status {
        PENDING,
        INFLIGHT,
        DONE,
        BLOCKED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "dedupe_key", length = 96, nullable = false)
    private String dedupeKey;

    /** The stable vector id that will be written to the target sid when merged. */
    @Column(name = "stable_vector_id", length = 256)
    private String stableVectorId;

    /** The vector id used for the staged write into the shadow sid. */
    @Column(name = "shadow_vector_id", length = 256, nullable = false)
    private String shadowVectorId;

    /** Original logical sid requested by the caller. */
    @Column(name = "logical_sid", length = 96)
    private String logicalSid;

    /** Target sid where stableVectorId should be merged. */
    @Column(name = "target_sid", length = 96)
    private String targetSid;

    /** Shadow sid used for staging. */
    @Column(name = "shadow_sid", length = 96)
    private String shadowSid;

    @Column(name = "shadow_run_id", length = 96)
    private String shadowRunId;

    @Column(name = "shadow_reason", length = 512)
    private String shadowReason;

    @Lob
    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Lob
    @Column(name = "meta_json", columnDefinition = "LONGTEXT")
    private String metaJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (nextAttemptAt == null) nextAttemptAt = now;
        if (status == null) status = Status.PENDING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
