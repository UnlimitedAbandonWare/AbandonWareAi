package com.example.lms.repository;

import com.example.lms.entity.TranslationMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.Collection;




@Repository
public interface TranslationMemoryRepository extends JpaRepository<TranslationMemory, Long> {

    /**
     * sourceHash로 단건 조회
     */
    Optional<TranslationMemory> findBySourceHash(String sourceHash);

    /** Bulk lookup to eliminate N+1 in training/reinforcement. */
    List<TranslationMemory> findBySourceHashIn(Collection<String> sourceHashes);

    Page<TranslationMemory> findByStatusOrderByCreatedAtAsc(TranslationMemory.MemoryStatus status, Pageable pageable);

    /**
     * Claim a batch of PENDING rows by writing locked_at/locked_by.
     *
     * <p>
     * MySQL/MariaDB support ORDER BY + LIMIT on UPDATE. This makes claim O(logN)
     * with (status, created_at) index and avoids full scan + filesort.
     * </p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE translation_memory
           SET locked_at = :now,
               locked_by = :lockedBy
         WHERE status = :pendingStatus
           AND (locked_at IS NULL OR locked_at < :expireBefore)
         ORDER BY created_at ASC
         LIMIT :limit
    """, nativeQuery = true)
    int claimPendingLease(@Param("pendingStatus") int pendingStatus,
                          @Param("lockedBy") String lockedBy,
                          @Param("now") LocalDateTime now,
                          @Param("expireBefore") LocalDateTime expireBefore,
                          @Param("limit") int limit);

    /**
     * Claim a batch of QUARANTINED rows by writing locked_at/locked_by.
     *
     * <p>
     * Shares the same lease fields with PENDING, but uses a different status.
     * This enables "격리 재처리" without full table scans.
     * </p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE translation_memory
           SET locked_at = :now,
               locked_by = :lockedBy
         WHERE status = :quarantineStatus
           AND (locked_at IS NULL OR locked_at < :expireBefore)
         ORDER BY created_at ASC
         LIMIT :limit
    """, nativeQuery = true)
    int claimQuarantineLease(@Param("quarantineStatus") int quarantineStatus,
                             @Param("lockedBy") String lockedBy,
                             @Param("now") LocalDateTime now,
                             @Param("expireBefore") LocalDateTime expireBefore,
                             @Param("limit") int limit);

    /** Fetch rows claimed by this scheduler run. */
    List<TranslationMemory> findByLockedByAndLockedAtOrderByCreatedAtAsc(String lockedBy, LocalDateTime lockedAt);

    // lazy bootstrap용: sid 후보들에서 상위 N개만 가져오기
    Page<TranslationMemory> findBySessionIdIn(List<String> sessionIds, Pageable pageable);

    /**
     * 에너지가 설정된 전체 레코드 중 상위 10개 조회
     */
    List<TranslationMemory> findTop10ByEnergyNotNullOrderByEnergyAsc();

    /**
     * 특정 세션 내에서 에너지가 설정된 상위 10개 조회
     */
    List<TranslationMemory> findTop10BySessionIdAndEnergyNotNullOrderByEnergyAsc(String sessionId);

    /**
     * sourceHash로 매칭되는 레코드의 hitCount와 last_used_at을 갱신합니다. (네이티브 쿼리)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE translation_memory
           SET hit_count = COALESCE(hit_count, 0) + 1,
               last_used_at = NOW()
         WHERE source_hash = :hash
    """, nativeQuery = true)
    int incrementHitCountBySourceHash(@Param("hash") String hash);

    /**
     * sourceHash로 매칭되는 레코드의 에너지와 온도를 갱신합니다. (세션 무관, 네이티브 쿼리)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE translation_memory
        SET energy = :energy,
            temperature = :temp,
            updated_at = NOW()
        WHERE source_hash = :hash
    """, nativeQuery = true)
    int updateEnergyByHash(@Param("hash") String hash,
                           @Param("energy") double energy,
                           @Param("temp") double temp);

    /**
     * sourceHash와 sessionId로 매칭되는 레코드의 에너지와 온도를 갱신합니다. (네이티브 쿼리)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE translation_memory
        SET energy = :energy,
            temperature = :temp,
            updated_at = NOW()
        WHERE source_hash = :hash
          AND session_id = :sid
    """, nativeQuery = true)
    int updateEnergyByHashAndSession(@Param("hash") String hash,
                                     @Param("sid") String sessionId,
                                     @Param("energy") double energy,
                                     @Param("temp") double temp);

    /**
     * 특정 세션 내에서 'COMMUNITY' 신뢰도를 제외한 상위 랭크 결과를 조회합니다. (네이티브 쿼리)
     */
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

    /**
     * 조회수(hit), 마지막 사용 시간, 점수(score)를 한 번에 갱신합니다. (네이티브 쿼리)
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE translation_memory
           SET hit_count = hit_count + 1,
               last_used_at = NOW(),
               score = GREATEST(COALESCE(score, 0), :score)
         WHERE source_hash = :hash
    """, nativeQuery = true)
    int incrementHitAndBumpLastUsed(@Param("hash") String hash,
                                    @Param("score") double score);
}