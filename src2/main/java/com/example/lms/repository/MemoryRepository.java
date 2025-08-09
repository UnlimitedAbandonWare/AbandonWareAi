package com.example.lms.repository;

import com.example.lms.domain.TranslationMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Translation‑Memory(TMX) 전용 Repository.
 *
 *  ▸ 단순 조회(findBySourceHash) + 동시성 안전한 hitCount 증가(incrementHitCountBySourceHash)
 *  ▸ "읽기 → 증가 → 쓰기" 패턴을 피하고 DB 레벨에서 한 번의 UPDATE 로 처리한다.
 */
public interface MemoryRepository extends JpaRepository<TranslationMemory, Long> {

    /* ────────────── 조회 ────────────── */
    Optional<TranslationMemory> findBySourceHash(String sourceHash);

    /* ────────────── 통계 업데이트 ────────────── */

    /**
     * {@code sourceHash} 로 매칭되는 레코드의 {@code hitCount} 를 1 증가시킨다.
     *
     * <p>
     *  • <b>@Modifying</b>  : SELECT 가 아닌 DML(UPDATE) 쿼리임을 Spring Data 에 알림<br>
     *  • <b>@Transactional</b> : 트랜잭션 내에서 실행하지 않으면 JPA flush 가 되지 않으므로 필수
     * </p>
     * @param hash SHA‑256 해시(64자)
     * @return 영향 받은 row 수 (정상적으로는 0 또는 1)
     */
    @Transactional
    @Modifying
    @Query("UPDATE TranslationMemory tm SET tm.hitCount = tm.hitCount + 1 WHERE tm.sourceHash = :hash")
    int incrementHitCountBySourceHash(@Param("hash") String hash);

    /*
     * (필요 시 확장 예시)
     * @Modifying
     * @Query("UPDATE TranslationMemory tm SET tm.hitCount = tm.hitCount + 1, tm.qValue = :q WHERE tm.sourceHash = :hash")
     * void updateOnHit(@Param("hash") String hash, @Param("q") double newQValue);
     */
}
