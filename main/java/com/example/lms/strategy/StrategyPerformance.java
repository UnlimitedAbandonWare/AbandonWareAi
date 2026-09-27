package com.example.lms.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "strategy_performance",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_strategy_cat",
                columnNames = {"strategy_name", "query_category"}),
        indexes = {
                @Index(name = "idx_sp_strategy", columnList = "strategy_name"),
                @Index(name = "idx_sp_updated", columnList = "updated_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyPerformance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 64)
    private String strategyName;

    @Column(name = "query_category", nullable = false, length = 64)
    private String queryCategory;

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private long successCount = 0;

    @Column(name = "failure_count", nullable = false)
    @Builder.Default
    private long failureCount = 0;

    @Column(name = "average_reward", nullable = false)
    @Builder.Default
    private double averageReward = 0.0;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
