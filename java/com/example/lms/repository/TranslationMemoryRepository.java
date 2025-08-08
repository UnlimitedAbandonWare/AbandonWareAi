package com.example.lms.repository;
import com.example.lms.entity.TranslationMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;


public interface TranslationMemoryRepository extends JpaRepository<TranslationMemory, Long> {

    /** 원문(sourceHash)으로 번역 메모리 한 건 조회 */
    Optional<TranslationMemory> findBySourceHash(String sourceHash);
    List<TranslationMemory> findAllByScoreNotNullAndScoreGreaterThan(double minScore);
    /** 세션별 전체(레거시 호환용) */
    List<TranslationMemory> findBySessionId(String sessionId);

    /**
     * 세션별 상위 N개를 (score × COALESCE(cosine_similarity,1)) 가중치로 정렬하여 조회
     *  - 점수 NULL 행 제외(오염 방지)
     *  - 최신 사용 시각 우선 보조정렬
     *  - 🔸 신뢰도 필터가 없는 기본 조회(하위 호환)
     */
    @Query(value = """
        SELECT *
        FROM translation_memory
        WHERE session_id = :sessionId
          AND score IS NOT NULL
        ORDER BY (score * COALESCE(cosine_similarity, 1)) DESC,
                 COALESCE(last_used_at, created_at) DESC
        LIMIT :limit
    """, nativeQuery = true)
    List<TranslationMemory> findTopRankedBySessionId(@Param("sessionId") String sessionId,
                                                     @Param("limit") int limit);

    /* ✅ 공식 출처만 가져오기(OFFICIAL 게이트용) */
    @Query(value = """
        SELECT *
        FROM translation_memory
        WHERE session_id = :sessionId
          AND score IS NOT NULL
          AND trust_level = 'OFFICIAL'
        ORDER BY (score * COALESCE(cosine_similarity, 1)) DESC,
                 COALESCE(last_used_at, created_at) DESC
        LIMIT :limit
    """, nativeQuery = true)
    List<TranslationMemory> findTopRankedOfficialBySessionId(@Param("sessionId") String sessionId,
                                                             @Param("limit") int limit);

    /* ✅ 커뮤니티 제외(OFFICIAL 우선 + UNKNOWN 허용) */
    @Query(value = """
        SELECT *
        FROM translation_memory
        WHERE session_id = :sessionId
          AND score IS NOT NULL
          AND (trust_level IS NULL OR trust_level <> 'COMMUNITY')
        ORDER BY (score * COALESCE(cosine_similarity, 1)) DESC,
                 COALESCE(last_used_at, created_at) DESC
        LIMIT :limit
    """, nativeQuery = true)
    List<TranslationMemory> findTopRankedNoCommunityBySessionId(@Param("sessionId") String sessionId,
                                                                @Param("limit") int limit);

    /** source_hash 일치 행의 hit_count 를 1 증가 */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE translation_memory
        SET hit_count = COALESCE(hit_count,0) + 1,
            last_used_at = NOW()
        WHERE source_hash = :sourceHash
    """, nativeQuery = true)
    int incrementHitCountBySourceHash(@Param("sourceHash") String sourceHash);

    /*───────────────────────────────────────────────
     * ✅ 단일 UPSERT ― INSERT … ON DUPLICATE KEY UPDATE
     *   (전역/세션 동시 삽입·두 키 동시 삽입 경합 제거)
     *   ◇ V1: 하위 호환(기존 메서드 유지)
     *   ◇ V2: trust_level / is_advisory 추가
     *───────────────────────────────────────────────*/
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO translation_memory
          (session_id, source_hash, content, query, score,
           hit_count, success_count, failure_count, last_used_at, created_at,
           source, q_value, reward_mean, rewardm2, cosine_similarity, cosine_correction,
         status, version)
        VALUES
          (:sessionId, :sourceHash, :content, :query, :score,
           1, 1, 0, NOW(), NOW(),
           :source, :qValue, :reward, 0, :cosSim, :cosCorr,
         :status, 0)
        ON DUPLICATE KEY UPDATE
           hit_count    = COALESCE(hit_count,0) + 1,
           reward_mean  = reward_mean + (:reward - reward_mean) / (hit_count + 1),
           last_used_at = NOW()
    """, nativeQuery = true)
    int upsertReward(@Param("sessionId") String sessionId,
                     @Param("sourceHash") String sourceHash,
                     @Param("content") String content,
                     @Param("query") String query,
                     @Param("score") double score,
                     @Param("source") String source,
                     @Param("qValue") double qValue,
                     @Param("reward") double reward,
                     @Param("status") int status,
                     @Param("cosSim") Double cosSim,
                     @Param("cosCorr") Double cosCorr);

    /* ⭐ 신규: 신뢰도/자문 플래그 포함 UPSERT (점진 적용 권장) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO translation_memory
          (session_id, source_hash, content, query, score,
           hit_count, success_count, failure_count, last_used_at, created_at,
           source, q_value, reward_mean, rewardm2, cosine_similarity, cosine_correction,
           status, version, trust_level, is_advisory)
        VALUES
          (:sessionId, :sourceHash, :content, :query, :score,
           1, 1, 0, NOW(), NOW(),
           :source, :qValue, :reward, 0, :cosSim, :cosCorr,
           :status, 0, :trustLevel, :isAdvisory)
        ON DUPLICATE KEY UPDATE
           hit_count    = COALESCE(hit_count,0) + 1,
           reward_mean  = reward_mean + (:reward - reward_mean) / (hit_count + 1),
           last_used_at = NOW(),
           trust_level  = COALESCE(trust_level, :trustLevel)
    """, nativeQuery = true)
    int upsertRewardV2(@Param("sessionId") String sessionId,
                       @Param("sourceHash") String sourceHash,
                       @Param("content") String content,
                       @Param("query") String query,
                       @Param("score") double score,
                       @Param("source") String source,
                       @Param("qValue") double qValue,
                       @Param("reward") double reward,
                       @Param("status") int status,
                       @Param("cosSim") Double cosSim,
                       @Param("cosCorr") Double cosCorr,
                       @Param("trustLevel") String trustLevel,      /* 'OFFICIAL' | 'COMMUNITY' | 'UNKNOWN' */
                       @Param("isAdvisory") boolean isAdvisory);
}