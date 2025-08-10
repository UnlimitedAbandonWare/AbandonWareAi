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

    /* ======================================================================
     * 단순 조회 (JPA 쿼리 메서드)
     * ==================================================================== */

    /** 원문(sourceHash)으로 번역 메모리 한 건 조회 */
    Optional<TranslationMemory> findBySourceHash(String sourceHash);

    // ⚠️ updatedAt 참조 제거: 부트 업 먼저 복구
    @Modifying
    @Query(value = """
INSERT INTO translation_memory
  (session_id, query, source, content, source_hash, score,
   hit_count, success_count, failure_count, status,
   rewardm2, reward_mean, q_value,
   created_at, last_used_at, version)
VALUES
  (:sid, :q, :src, :content, :hash, :score,
   1, 0, 0, 0,
   0, 0, 0,
   NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  hit_count    = hit_count + 1,
  last_used_at = NOW(),
  score        = GREATEST(score, VALUES(score))
""", nativeQuery = true)
    int upsertByHash(@Param("sid") String sid,
                     @Param("q")   String q,
                     @Param("src") String src,
                     @Param("content") String content,
                     @Param("hash") String hash,
                     @Param("score") double score);




    /** 특정 점수 이상인 모든 메모리 조회 */
    List<TranslationMemory> findAllByScoreNotNullAndScoreGreaterThan(double minScore);

    /** 세션 ID로 전체 조회 (레거시 호환용) */
    List<TranslationMemory> findBySessionId(String sessionId);

    /* ======================================================================
     * 복합 조회 (네이티브 쿼리)
     * ==================================================================== */

    /**
     * 세션별 상위 N개를 가중치로 정렬하여 조회 (신뢰도 필터 없음)
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

    /** 공식 출처(OFFICIAL)만 조회 */
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

    /** 커뮤니티 출처(COMMUNITY)를 제외하고 조회 */
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

    /* ======================================================================
     * 수정 (UPDATE) 쿼리
     * ==================================================================== */

    /** source_hash 일치 행의 hit_count 를 1 증가 */


    /** hit_count 증가 및 score 업데이트 */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE translation_memory
           SET hit_count = hit_count + 1,
               last_used_at = NOW(),
               score = GREATEST(score, :score)
         WHERE source_hash = :hash
    """, nativeQuery = true)
    int incrementHitAndBumpLastUsed(@Param("hash") String hash,
                                    @Param("score") double score);

    /* ======================================================================
     * UPSERT (INSERT ... ON DUPLICATE KEY UPDATE) 쿼리
     * ==================================================================== */

    /** 기본 UPSERT: 히트 카운트 및 점수 갱신 */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO translation_memory (source_hash, source, query, content, score, hit_count, last_used_at, created_at)
        VALUES (:hash, :source, :query, :content, :score, 1, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
          hit_count    = hit_count + 1,
          last_used_at = NOW(),
          score        = GREATEST(score, VALUES(score))
    """, nativeQuery = true)
    int upsertHit(@Param("hash") String hash,
                  @Param("source") String source,
                  @Param("query") String query,
                  @Param("content") String content,
                  @Param("score") double score);

    /** 보상(Reward) 시스템용 UPSERT (V1) */
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
    @Modifying
    @Transactional
    @Query(value = """
    UPDATE translation_memory
       SET hit_count = COALESCE(hit_count, 0) + 1,
           last_used_at = NOW()
     WHERE source_hash = :hash
""", nativeQuery = true)
    int incrementHitCountBySourceHash(@Param("hash") String hash);
    /** 신뢰도(Trust Level) 포함 UPSERT (V2) */
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
                       @Param("trustLevel") String trustLevel,
                       @Param("isAdvisory") boolean isAdvisory);
}