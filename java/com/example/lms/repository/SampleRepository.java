// src/main/java/com/example/lms/repository/SampleRepository.java
package com.example.lms.repository;

import com.example.lms.domain.TranslationSample;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;




/**
 * 번역 샘플 로그 테이블 접근용 JPA Repository
 *
 *  ▒ 주요 용도 ▒
 *   ① 운영중 로그 적재 (save)
 *   ② 관리자 화면(Paging) 조회
 *   ③ 오프라인/배치 학습 - 사람이 교정한 레코드만 뽑기
 */
public interface SampleRepository extends JpaRepository<TranslationSample, Long> {

    /*────────────────── 학습용 쿼리 ──────────────────*/

    /** 사람이 ‘corrected’ 컬럼을 채운 레코드만 반환 */
    List<TranslationSample> findByCorrectedIsNotNull();

    /** 학습 배치 대상: 교정된 샘플 중 아직 학습 미반영(trainedAt IS NULL)만 반환 */
    Page<TranslationSample> findByCorrectedIsNotNullAndTrainedAtIsNull(Pageable pageable);

    /** 학습 대상 전체 건수(진행률용) */
    long countByCorrectedIsNotNullAndTrainedAtIsNull();

    /** Soak→Dataset 적재 시 idempotent upsert를 위해 사용 */
    Optional<TranslationSample> findBySourceHash(String sourceHash);

    /*
     * ↓ 필요 시 이런 식으로 확장 가능합니다.
     * List<TranslationSample> findByCorrectedIsNotNullAndQErrorLessThan(double maxErr);
     */
}