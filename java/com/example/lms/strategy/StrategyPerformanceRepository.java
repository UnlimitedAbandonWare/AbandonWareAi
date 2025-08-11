package com.example.lms.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StrategyPerformanceRepository extends JpaRepository<StrategyPerformance, Long> {

    @Query("select s from StrategyPerformance s where s.strategyName=:sn and s.queryCategory=:qc")
    Optional<StrategyPerformance> findOne(@Param("sn") String strategyName,
                                          @Param("qc") String queryCategory);

    /** upsert + 누적(성공/실패/평균보상 간단 갱신) */
    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO strategy_performance (strategy_name, query_category, success_count, failure_count, average_reward, updated_at)
        VALUES (:sn, :qc, :sc, :fc, :rw, NOW())
        ON DUPLICATE KEY UPDATE
          success_count = success_count + :sc,
          failure_count = failure_count + :fc,
          average_reward = LEAST(1.0, GREATEST(0.0, (average_reward*0.8 + :rw*0.2))),
          updated_at = NOW()
        """)
    int upsertAndAccumulate(@Param("sn") String strategyName,
                            @Param("qc") String queryCategory,
                            @Param("sc") long successInc,
                            @Param("fc") long failureInc,
                            @Param("rw") double rewardSample);

    /** 카테고리별 최고 전략(성공률 우선, 동률 시 평균보상 우위) */
    @Query(nativeQuery = true, value = """
        SELECT strategy_name as strategyName
        FROM strategy_performance
        WHERE query_category = :qc
        ORDER BY (success_count / NULLIF(success_count + failure_count,0)) DESC,
                 average_reward DESC
        LIMIT 1
        """)
    Optional<BestRow> findBestStrategyFor(@Param("qc") String queryCategory);
    // Spring Data 인터페이스 프로젝션은 getter 규칙(getXxx)이어야 함
    interface BestRow { String getStrategyName(); }
}
