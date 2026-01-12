package com.example.lms.strategy;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;




@Entity
@Table(name = "strategy_performance",
        uniqueConstraints = @UniqueConstraint(name = "ux_strategy_cat", columnNames = {"strategy_name","query_category"}),
        indexes = {
                @Index(name = "idx_sp_strategy", columnList = "strategy_name"),
                @Index(name = "idx_sp_updated",  columnList = "updated_at")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StrategyPerformance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="strategy_name", nullable=false, length=64)
    private String strategyName;

    @Column(name="query_category", nullable=false, length=64)
    private String queryCategory;

    @Column(name="success_count", nullable=false) @Builder.Default
    private long successCount = 0;

    @Column(name="failure_count", nullable=false) @Builder.Default
    private long failureCount = 0;

    /** 0~1 평균 보상(최근값을 EMA로 반영해도 됨) */
    @Column(name="average_reward", nullable=false) @Builder.Default
    private double averageReward = 0.0;

    @Column(name="updated_at", nullable=false)
    private LocalDateTime updatedAt;

    @PrePersist void prePersist(){ if (updatedAt==null) updatedAt=LocalDateTime.now(); }
    @PreUpdate  void preUpdate() { updatedAt=LocalDateTime.now(); }
}