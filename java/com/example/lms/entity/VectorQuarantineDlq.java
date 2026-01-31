package com.example.lms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * VectorQuarantineDlq
 *
 * <p>
 * Persisted DLQ entries for items routed to the quarantine SID due to
 * INGEST_PROTECTION. This enables safe redrive once the embedding/vector backends
 * are healthy again.
 */
@Entity
@Table(
        name = "vector_quarantine_dlq",
        indexes = {
                @Index(name = "idx_vqdlq_status_next", columnList = "status,next_attempt_at"),
                @Index(name = "idx_vqdlq_locked", columnList = "status,locked_at"),
                @Index(name = "idx_vqdlq_sid", columnList = "original_sid_base"),
                @Index(name = "idx_vqdlq_created", columnList = "created_at")
        }
)
@Getter
@Setter
public class VectorQuarantineDlq {

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

    /**
     * Idempotency key: prevents duplicate DLQ records when the same payload is
     * quarantined multiple times.
     */
    @Column(name = "dedupe_key", length = 128, nullable = false, unique = true)
    private String dedupeKey;

    @Column(name = "quarantine_vector_id", length = 192, nullable = false)
    private String quarantineVectorId;

    @Column(name = "original_vector_id", length = 192)
    private String originalVectorId;

    @Column(name = "original_sid", length = 64, nullable = false)
    private String originalSid;

    @Column(name = "original_sid_base", length = 64, nullable = false)
    private String originalSidBase;

    @Column(name = "quarantine_reason", length = 512)
    private String quarantineReason;

    @Lob
    @Column(name = "payload", columnDefinition = "longtext")
    private String payload;

    @Lob
    @Column(name = "meta_json", columnDefinition = "longtext")
    private String metaJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
