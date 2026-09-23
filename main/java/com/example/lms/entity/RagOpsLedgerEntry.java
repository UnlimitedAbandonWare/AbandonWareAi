package com.example.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "rag_ops_ledger",
        indexes = {
                @Index(name = "idx_rag_ops_run_id", columnList = "run_id"),
                @Index(name = "idx_rag_ops_type_created", columnList = "entry_type,created_at"),
                @Index(name = "idx_rag_ops_decision_created", columnList = "decision,created_at"),
                @Index(name = "idx_rag_ops_hotspot_created", columnList = "hotspot,created_at"),
                @Index(name = "idx_rag_ops_plan_created", columnList = "plan_id,created_at")
        }
)
@Getter
@Setter
public class RagOpsLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "run_id", length = 96, nullable = false)
    private String runId;

    @Column(name = "entry_type", length = 32, nullable = false)
    private String entryType;

    @Column(name = "session_hash", length = 64)
    private String sessionHash;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "query_hash", length = 64)
    private String queryHash;

    @Column(name = "query_length")
    private Integer queryLength;

    @Column(name = "plan_id", length = 128)
    private String planId;

    @Column(name = "strategy_name", length = 128)
    private String strategyName;

    @Column(name = "resource_tier", length = 32)
    private String resourceTier;

    @Lob
    @Column(name = "source_counts_json", columnDefinition = "longtext")
    private String sourceCountsJson;

    @Lob
    @Column(name = "quality_json", columnDefinition = "longtext")
    private String qualityJson;

    @Lob
    @Column(name = "vector_json", columnDefinition = "longtext")
    private String vectorJson;

    @Lob
    @Column(name = "kg_json", columnDefinition = "longtext")
    private String kgJson;

    @Lob
    @Column(name = "matrix_json", columnDefinition = "longtext")
    private String matrixJson;

    @Column(name = "decision", length = 32)
    private String decision;

    @Column(name = "failure_class", length = 64)
    private String failureClass;

    @Column(name = "hotspot", length = 64)
    private String hotspot;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
