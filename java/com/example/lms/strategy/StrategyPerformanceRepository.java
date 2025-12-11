package com.example.lms.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
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
    interface BestRow { String getStrategyName(); }

    /** 소프트맥스 선택용 집계(성공/실패/보상) */
    @Query(nativeQuery = true, value = """
        SELECT strategy_name  AS strategyName,
               success_count  AS success,
               failure_count  AS failure,
               average_reward AS reward
        FROM strategy_performance
        WHERE query_category = :qc
    """)
    List<StatsRow> findStatsByCategory(@Param("qc") String queryCategory);

    interface StatsRow {
        String getStrategyName();
        Long   getSuccess();   // ← 이름 일치
        Long   getFailure();   // ← 이름 일치
        Double getReward();    // ← 이름 일치
    }
}